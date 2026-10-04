package eu.neydev.birthday.core.service;

import eu.neydev.birthday.core.api.OutboundMessage;
import eu.neydev.birthday.core.api.PlatformException;
import eu.neydev.birthday.core.command.MenuFactory;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.domain.BirthdayMath;
import eu.neydev.birthday.core.domain.Profile;
import eu.neydev.birthday.core.domain.ReminderKind;
import eu.neydev.birthday.core.i18n.LocalizedFormats;
import eu.neydev.birthday.core.i18n.MessageBundleHolder;
import eu.neydev.birthday.core.metrics.MetricsRegistry;
import eu.neydev.birthday.core.pipeline.OutboundDispatcher;
import eu.neydev.birthday.core.schedule.NextReminderCalculator;
import eu.neydev.birthday.core.storage.DeliveryLogRepository;
import eu.neydev.birthday.core.storage.ProfileRepository;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Handling a due reminder. Guarantees:
 *
 * <ul>
 *   <li><b>Do not miss a day:</b> the {@code next_reminder_at} cursor is persistent;
 *       after downtime, catch-up delivery per the catch-up policy;</li>
 *   <li><b>Do not duplicate:</b> the {@code delivery_log} barrier by (user, kind, local_date) -
 *       even a crash between sending and moving the cursor will not cause a repeat;</li>
 *   <li><b>Do not hammer the dead:</b> permanent non-delivery (bot deleted/blocked)
 *       disables the profile's subscription;</li>
 *   <li><b>Quiet period:</b> the countdown starts N days before the date (config),
 *       the greeting - always on the birthday.</li>
 * </ul>
 */
public final class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final ProfileRepository profiles;
    private final DeliveryLogRepository deliveryLog;
    private final OutboundDispatcher dispatcher;
    private final MessageBundleHolder bundleHolder;
    private final MenuFactory menuFactory;
    private final NextReminderCalculator calculator;
    private final AppConfig.Scheduler schedulerConfig;
    private final MetricsRegistry metrics;
    private final Clock clock;

    public ReminderService(ProfileRepository profiles,
                           DeliveryLogRepository deliveryLog,
                           OutboundDispatcher dispatcher,
                           MessageBundleHolder bundleHolder,
                           MenuFactory menuFactory,
                           NextReminderCalculator calculator,
                           AppConfig.Scheduler schedulerConfig,
                           MetricsRegistry metrics,
                           Clock clock) {
        this.profiles = profiles;
        this.deliveryLog = deliveryLog;
        this.dispatcher = dispatcher;
        this.bundleHolder = bundleHolder;
        this.menuFactory = menuFactory;
        this.calculator = calculator;
        this.schedulerConfig = schedulerConfig;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * @return a future completed after delivery is confirmed (or fails) -
     *         the scheduler keeps an attempt counter for it.
     */
    public CompletableFuture<Void> processAsync(@NotNull Profile profile, @NotNull Instant now) {

        String trace = eu.neydev.birthday.core.util.TraceId.newId();
        eu.neydev.birthday.core.util.TraceId.put(trace);

        try {
            return processAsyncTraced(profile, now);
        } finally {
            eu.neydev.birthday.core.util.TraceId.clear();
        }

    }

    private CompletableFuture<Void> processAsyncTraced(@NotNull Profile profile, @NotNull Instant now) {

        // The scheduler's `now` is authoritative: `profile.today()` would read the system
        // clock and drift apart from it at midnight and on a catch-up after downtime.
        LocalDate localToday = profile.todayAt(now);

        LocalDate scheduled = calculator.scheduledLocalDate(profile);

        if (scheduled.isBefore(localToday)
                && schedulerConfig.catchUpPolicy() == AppConfig.Scheduler.CatchUpPolicy.SKIP) {
            return skipDay(profile, now, "catch-up skipped");
        }

        if (profile.birthDate() == null) {
            return skipDay(profile, now, "no birth date");
        }

        ReminderKind kind = BirthdayMath.isBirthdayToday(
                profile.birthDate(), localToday, schedulerConfig.leapDayPolicy())
                ? ReminderKind.BIRTHDAY
                : ReminderKind.COUNTDOWN;

        if (kind == ReminderKind.COUNTDOWN && schedulerConfig.countdownStartDaysBefore() > 0) {
            long days = BirthdayMath.daysUntil(profile.birthDate(), localToday,
                    schedulerConfig.leapDayPolicy());

            if (days > schedulerConfig.countdownStartDaysBefore()) {
                return skipDay(profile, now, "outside countdown window");
            }
        }

        if (!deliveryLog.markSent(profile.user(), kind, localToday, now)) {
            return skipDay(profile, now, "already delivered today");
        }

        OutboundMessage message = buildMessage(profile, kind, localToday);

        return dispatcher.submit(message).whenComplete((result, error) -> {

            if (error == null) {
                metrics.increment("reminders_sent_total",
                        "platform", profile.user().platform().id(), "kind", kind.name());
                advance(profile, now);
                return;
            }

            Throwable cause = unwrap(error);

            if (cause instanceof PlatformException.InvalidMessageException) {
                // Our bug, not the user's: the day is lost, the subscription stays.
                metrics.increment("reminders_invalid_payload_total",
                        "platform", profile.user().platform().id());
                return;
            }

            if (cause instanceof PlatformException.PermanentDeliveryException) {
                metrics.increment("reminders_dead_chat_total",
                        "platform", profile.user().platform().id());
                profiles.save(profile.withNotifyEnabled(false, now));
                return;
            }

            log.warn("Reminder {} not delivered (will retry): {}",
                    profile.user().key(), cause.getMessage());

        });

    }

    /** Move the cursor to the next slot without sending (quiet period, deduplication). */
    public CompletableFuture<Void> skipDay(@NotNull Profile profile, @NotNull Instant now, String reason) {

        metrics.increment("reminders_skipped_total",
                "platform", profile.user().platform().id(), "reason", reason.replaceAll("\\W+", "_"));
        advance(profile, now);

        return CompletableFuture.completedFuture(null);

    }

    /**
     * Accounted for a failed delivery: the attempt counter lives in the profile (DB),
     * so a process restart does not reset the failure history.
     *
     * @return the profile with an incremented counter.
     */
    public Profile registerFailure(@NotNull Profile profile) {

        Profile updated = profile.withAttempts(profile.attempts() + 1, clock.instant());
        profiles.save(updated);

        return updated;

    }

    private void advance(Profile profile, Instant now) {

        Profile advanced = profile.withNextReminderAt(calculator.nextSlot(profile, now), now);

        if (advanced.attempts() != 0) {
            advanced = advanced.withAttempts(0, now);
        }

        profiles.save(advanced);

    }

    private OutboundMessage buildMessage(Profile profile, ReminderKind kind, LocalDate localToday) {

        var renderer = bundleHolder.renderer();
        Map<String, Object> params = new HashMap<>();
        String key;

        if (kind == ReminderKind.BIRTHDAY) {

            if (profile.birthDate().hasYear()) {
                key = "reminder.birthday";
                params.put("age", BirthdayMath.turningAge(profile.birthDate(), localToday,
                        schedulerConfig.leapDayPolicy()));
            } else {
                key = "reminder.birthday.no_year";
            }

        } else {

            key = "reminder.countdown";
            long days = BirthdayMath.daysUntil(profile.birthDate(), localToday,
                    schedulerConfig.leapDayPolicy());
            LocalDate anniversary = BirthdayMath.nextAnniversary(profile.birthDate(), localToday,
                    schedulerConfig.leapDayPolicy());
            params.putAll(countdownParams(profile, days, anniversary));

        }

        return new OutboundMessage.Send(
                profile.user().platform(),
                profile.chatId(),
                renderer.rich(key, profile.locale(), params),
                menuFactory.menuButton(profile.locale()));

    }

    /**
     * Countdown parameters: how many days are left, plus the date, the wall clock and the
     * zone of the greeting, so the daily reminder also answers "when will it arrive".
     */
    private static Map<String, Object> countdownParams(Profile profile, long days,
                                                       LocalDate anniversary) {

        Locale locale = profile.locale();
        Map<String, Object> params = new HashMap<>();
        params.put("days", days);
        params.put("date", LocalizedFormats.dateFull(anniversary, locale));
        params.put("time", LocalizedFormats.time(profile.notifyTime(), locale));
        params.put("zone", LocalizedFormats.zone(profile.zone(), locale));

        return params;

    }

    private static Throwable unwrap(Throwable error) {

        Throwable current = error;

        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }

        return current;

    }

    public Clock clock() {
        return clock;
    }

}
