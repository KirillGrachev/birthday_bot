package eu.neydev.birthday.core.service;

import eu.neydev.birthday.core.FakeAdapter;
import eu.neydev.birthday.core.InMemoryStorage;
import eu.neydev.birthday.core.api.OutboundMessage;
import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.api.PlatformException;
import eu.neydev.birthday.core.api.PlatformUser;
import eu.neydev.birthday.core.command.MenuFactory;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.domain.BirthDate;
import eu.neydev.birthday.core.domain.LeapDayPolicy;
import eu.neydev.birthday.core.domain.Profile;
import eu.neydev.birthday.core.i18n.MessageBundleHolder;
import eu.neydev.birthday.core.metrics.MetricsRegistry;
import eu.neydev.birthday.core.pipeline.OutboundDispatcher;
import eu.neydev.birthday.core.schedule.NextReminderCalculator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReminderServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    private InMemoryStorage storage;
    private FakeAdapter adapter;
    private OutboundDispatcher dispatcher;
    private ReminderService service;
    private AppConfig.Scheduler schedulerConfig;

    @BeforeEach
    void setUp() {

        storage = new InMemoryStorage();
        adapter = new FakeAdapter(Platform.TELEGRAM);

        dispatcher = new OutboundDispatcher(2, 3, 50, 100, 100, new MetricsRegistry());
        dispatcher.register(adapter);
        dispatcher.start();

        schedulerConfig = new AppConfig.Scheduler(
                java.time.Duration.ofSeconds(30), 100, 3, java.time.Duration.ofSeconds(20),
                AppConfig.Scheduler.CatchUpPolicy.SEND_ONCE, 0,
                LocalTime.of(12, 0), ZoneId.of("UTC"), LeapDayPolicy.LAST_OF_FEBRUARY);

        MessageBundleHolder holder = new MessageBundleHolder("messages", null,
                Set.of("ru", "en"), "ru");

        service = new ReminderService(
                storage.profiles(), storage.deliveryLog(), dispatcher, holder,
                new MenuFactory(holder, null), new NextReminderCalculator(schedulerConfig),
                schedulerConfig, null, new MetricsRegistry(), Clock.fixed(NOW, ZoneId.of("UTC")));

    }

    @AfterEach
    void tearDown() {
        dispatcher.close();
    }

    private Profile dueProfile(String id, BirthDate birthDate) {
        return new Profile(
                new PlatformUser(Platform.TELEGRAM, id), "1".equals(id) ? "1" : id,
                Locale.forLanguageTag("ru"), ZoneId.of("UTC"), birthDate, true,
                LocalTime.of(12, 0), NOW.minusSeconds(1), NOW, NOW);
    }

    @Test
    void sendsCountdownAndAdvancesCursor() throws Exception {

        Profile profile = dueProfile("1", new BirthDate(6, 15, 1990));
        storage.profiles().save(profile);

        service.processAsync(profile, NOW).get(5, TimeUnit.SECONDS);

        assertThat(adapter.sentOfType(OutboundMessage.Send.class)).hasSize(1);
        Profile reloaded = storage.profiles().find(profile.user()).orElseThrow();
        assertThat(reloaded.nextReminderAt()).isAfter(NOW);

    }

    @Test
    void secondDeliverySameDayIsDeduplicated() throws Exception {

        Profile profile = dueProfile("1", new BirthDate(6, 15, 1990));
        storage.profiles().save(profile);

        service.processAsync(profile, NOW).get(5, TimeUnit.SECONDS);
        // The cursor moved ahead, but even a forced repeated tick on the same day does not send
        Profile stale = storage.profiles().find(profile.user()).orElseThrow()
                .withNextReminderAt(NOW.minusSeconds(1), NOW);
        storage.profiles().save(stale);
        service.processAsync(stale, NOW).get(5, TimeUnit.SECONDS);

        assertThat(adapter.sentOfType(OutboundMessage.Send.class)).hasSize(1);

    }

    @Test
    void birthdayKindOnAnniversary() throws Exception {

        Profile profile = dueProfile("2", new BirthDate(9, 30, 1990));
        storage.profiles().save(profile);

        service.processAsync(profile, NOW).get(5, TimeUnit.SECONDS);

        OutboundMessage.Send send = adapter.sentOfType(OutboundMessage.Send.class).get(0);
        assertThat(send.text().toPlainText()).contains("С днём рождения, 36");

    }

    @Test
    void countdownWindowSkipsFarDates() throws Exception {

        AppConfig.Scheduler windowed = new AppConfig.Scheduler(
                java.time.Duration.ofSeconds(30), 100, 3, java.time.Duration.ofSeconds(20),
                AppConfig.Scheduler.CatchUpPolicy.SEND_ONCE, 7,
                LocalTime.of(12, 0), ZoneId.of("UTC"), LeapDayPolicy.LAST_OF_FEBRUARY);

        MessageBundleHolder holder = new MessageBundleHolder("messages", null,
                Set.of("ru", "en"), "ru");

        ReminderService windowedService = new ReminderService(
                storage.profiles(), storage.deliveryLog(), dispatcher, holder,
                new MenuFactory(holder, null), new NextReminderCalculator(windowed),
                windowed, null, new MetricsRegistry(), Clock.fixed(NOW, ZoneId.of("UTC")));

        Profile profile = dueProfile("3", new BirthDate(6, 15, 1990)); // ~258 days
        storage.profiles().save(profile);
        windowedService.processAsync(profile, NOW).get(5, TimeUnit.SECONDS);

        assertThat(adapter.sent()).isEmpty();
        assertThat(storage.profiles().find(profile.user()).orElseThrow().nextReminderAt())
                .isAfter(NOW);

    }

    @Test
    void catchUpSendOnceDeliversLateExactlyOnce() throws Exception {

        Profile late = dueProfile("5", new BirthDate(6, 15, 1990))
                .withNextReminderAt(NOW.minus(2, java.time.temporal.ChronoUnit.DAYS), NOW);
        storage.profiles().save(late);

        service.processAsync(late, NOW).get(5, TimeUnit.SECONDS);
        assertThat(adapter.sentOfType(OutboundMessage.Send.class)).hasSize(1);

        // a forced repeat of the same "overdue" cursor does not duplicate
        Profile stale = storage.profiles().find(late.user()).orElseThrow()
                .withNextReminderAt(NOW.minus(2, java.time.temporal.ChronoUnit.DAYS), NOW);
        storage.profiles().save(stale);
        service.processAsync(stale, NOW).get(5, TimeUnit.SECONDS);

        assertThat(adapter.sentOfType(OutboundMessage.Send.class)).hasSize(1);

    }

    @Test
    void failureIncrementsPersistentAttempts() throws Exception {

        FakeAdapter failing = new FakeAdapter(Platform.TELEGRAM)
                .failWith(new PlatformException.PermanentDeliveryException("bot blocked"));

        OutboundDispatcher failingDispatcher = new OutboundDispatcher(
                1, 1, 10, 100, 100, new MetricsRegistry());
        failingDispatcher.register(failing);
        failingDispatcher.start();

        try {

            MessageBundleHolder holder = new MessageBundleHolder("messages", null,
                    Set.of("ru", "en"), "ru");
            ReminderService failingService = new ReminderService(
                    storage.profiles(), storage.deliveryLog(), failingDispatcher, holder,
                    new MenuFactory(holder, null), new NextReminderCalculator(schedulerConfig),
                    schedulerConfig, null, new MetricsRegistry(),
                    Clock.fixed(NOW, ZoneId.of("UTC")));

            Profile profile = dueProfile("6", new BirthDate(6, 15, 1990));
            storage.profiles().save(profile);

            // a permanent failure completes the future exceptionally - the scheduler counts the attempt
            assertThatThrownBy(() -> failingService.processAsync(profile, NOW).get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(PlatformException.PermanentDeliveryException.class);
            Profile updated = failingService.registerFailure(
                    storage.profiles().find(profile.user()).orElseThrow());

            assertThat(updated.attempts()).isEqualTo(1);
            assertThat(storage.profiles().find(profile.user()).orElseThrow().attempts()).isEqualTo(1);

        } finally {
            failingDispatcher.close();
        }

    }

    @Test
    void catchUpSkipPolicyDoesNotSendLate() throws Exception {

        AppConfig.Scheduler skip = new AppConfig.Scheduler(
                java.time.Duration.ofSeconds(30), 100, 3, java.time.Duration.ofSeconds(20),
                AppConfig.Scheduler.CatchUpPolicy.SKIP, 0,
                LocalTime.of(12, 0), ZoneId.of("UTC"), LeapDayPolicy.LAST_OF_FEBRUARY);
        MessageBundleHolder holder = new MessageBundleHolder("messages", null,
                Set.of("ru", "en"), "ru");
        ReminderService skipService = new ReminderService(
                storage.profiles(), storage.deliveryLog(), dispatcher, holder,
                new MenuFactory(holder, null), new NextReminderCalculator(skip),
                skip, null, new MetricsRegistry(), Clock.fixed(NOW, ZoneId.of("UTC")));

        Profile late = dueProfile("4", new BirthDate(6, 15, 1990))
                .withNextReminderAt(NOW.minus(2, java.time.temporal.ChronoUnit.DAYS), NOW);
        storage.profiles().save(late);
        skipService.processAsync(late, NOW).get(5, TimeUnit.SECONDS);

        assertThat(adapter.sent()).isEmpty();

    }

}
