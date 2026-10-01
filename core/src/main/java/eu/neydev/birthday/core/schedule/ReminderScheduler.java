package eu.neydev.birthday.core.schedule;

import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.conversation.ConversationStore;
import eu.neydev.birthday.core.domain.Profile;
import eu.neydev.birthday.core.metrics.MetricsRegistry;
import eu.neydev.birthday.core.service.ReminderService;
import eu.neydev.birthday.core.storage.DeliveryLogRepository;
import eu.neydev.birthday.core.storage.ProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The reminder tick scheduler. Bound to REAL time (scheduleWithFixedDelay
 * from the tick, not "a day from start"): each tick selects a batch of profiles from the DB
 * whose persistent {@code next_reminder_at} cursor has come due.
 *
 * <p>Why this survives surges and downtime:
 * <ul>
 *   <li>one indexed {@code findDue} query instead of a per-user timer -
 *       100k profiles = the same 2 requests per minute;</li>
 *   <li>the in-flight set excludes double processing of one profile,
 *       while an asynchronous send is in flight;</li>
 *   <li>exhausting the day's attempts moves the cursor (the day is skipped deliberately,
 *       the bot does not get stuck forever), the metric records it;</li>
 *   <li>batch processing + asynchronous sending via the outbound dispatcher -
 *       the tick never blocks on network calls.</li>
 * </ul>
 */
public final class ReminderScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ProfileRepository profiles;
    private final DeliveryLogRepository deliveryLog;
    private final ReminderService reminderService;
    private final ConversationStore conversationStore;
    private final AppConfig.Scheduler config;
    private final MetricsRegistry metrics;
    private final Clock clock;

    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();
    private ScheduledExecutorService executor;
    /** Touched only from the single scheduler thread; no synchronization needed. */
    private LocalDate lastPruneDate;

    public ReminderScheduler(ProfileRepository profiles,
                             DeliveryLogRepository deliveryLog,
                             ReminderService reminderService,
                             ConversationStore conversationStore,
                             AppConfig.Scheduler config,
                             MetricsRegistry metrics,
                             Clock clock) {
        this.profiles = profiles;
        this.deliveryLog = deliveryLog;
        this.reminderService = reminderService;
        this.conversationStore = conversationStore;
        this.config = config;
        this.metrics = metrics;
        this.clock = clock;
    }

    public void start() {

        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {

            Thread thread = new Thread(runnable, "reminder-scheduler");
            thread.setDaemon(true);

            return thread;

        });

        long intervalMillis = Math.max(1_000, config.tickInterval().toMillis());
        executor.scheduleWithFixedDelay(this::tickSafely, intervalMillis / 2, intervalMillis,
                TimeUnit.MILLISECONDS);

        log.info("Reminder scheduler started: tick={} ms, batch={}", intervalMillis, config.batchSize());

    }

    private void tickSafely() {
        try {
            tick(clock.instant());
        } catch (Throwable t) {
            metrics.increment("scheduler_tick_errors_total");
            log.error("Scheduler tick failed", t);
        }
    }

    void tick(Instant now) {

        int purged = conversationStore.purgeExpired();
        if (purged > 0) {
            log.debug("Purged {} expired conversation sessions", purged);
        }

        pruneDeliveryLog(now);
        List<Profile> due = profiles.findDue(now, config.batchSize());

        metrics.gauge("scheduler_due_last_tick", () -> due.size());

        if (due.isEmpty()) {
            return;
        }

        metrics.add("scheduler_due_total", due.size());

        for (Profile profile : due) {

            String key = profile.user().key();

            if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) {
                continue;
            }

            reminderService.processAsync(profile, now).whenComplete((result, error) -> {

                inFlight.remove(key);

                if (error == null) {
                    return;
                }

                Profile failed = reminderService.registerFailure(profile);

                if (failed.attempts() >= config.maxAttempts()) {
                    reminderService.skipDay(failed, clock.instant(), "attempts_exhausted");
                }

            });

        }

    }

    /**
     * Once a day, drop the delivery-log rows older than the configured retention. The log
     * is the deduplication key (user, kind, local date) and only has to cover the window in
     * which a reminder may still be retried or caught up - keeping it forever turns SQLite
     * into an archive nobody reads. A failure here must not cost the tick its reminders.
     */
    private void pruneDeliveryLog(Instant now) {

        if (!config.prunesDeliveryLog()) {
            return;
        }

        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();

        if (today.equals(lastPruneDate)) {
            return;
        }

        lastPruneDate = today;

        try {

            LocalDate cutoff = today.minusDays(config.deliveryLogRetention().toDays());
            int removed = deliveryLog.pruneBefore(cutoff);

            metrics.add("delivery_log_pruned_total", removed);

            if (removed > 0) {
                log.info("Delivery log pruned: {} rows older than {}", removed, cutoff);
            }

        } catch (RuntimeException e) {
            metrics.increment("delivery_log_prune_errors_total");
            log.warn("Delivery log prune failed (will retry tomorrow): {}", e.getMessage());
        }

    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

}
