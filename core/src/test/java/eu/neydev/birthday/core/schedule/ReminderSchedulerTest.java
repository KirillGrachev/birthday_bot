package eu.neydev.birthday.core.schedule;

import eu.neydev.birthday.core.FakeAdapter;
import eu.neydev.birthday.core.InMemoryStorage;
import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.api.PlatformUser;
import eu.neydev.birthday.core.command.MenuFactory;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.conversation.ConversationStore;
import eu.neydev.birthday.core.domain.BirthDate;
import eu.neydev.birthday.core.domain.LeapDayPolicy;
import eu.neydev.birthday.core.domain.Profile;
import eu.neydev.birthday.core.i18n.MessageBundleHolder;
import eu.neydev.birthday.core.metrics.MetricsRegistry;
import eu.neydev.birthday.core.pipeline.OutboundDispatcher;
import eu.neydev.birthday.core.service.ReminderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReminderSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    @AfterEach
    void noop() {
    }

    @Test
    void tickProcessesDueProfilesOnce() throws Exception {

        InMemoryStorage storage = new InMemoryStorage();
        FakeAdapter adapter = new FakeAdapter(Platform.TELEGRAM);

        OutboundDispatcher dispatcher = new OutboundDispatcher(2, 3, 50, 100, 100,
                new MetricsRegistry());
        dispatcher.register(adapter);
        dispatcher.start();

        try {

            AppConfig.Scheduler schedulerConfig = new AppConfig.Scheduler(
                    Duration.ofSeconds(30), 100, 3, Duration.ofSeconds(20),
                    AppConfig.Scheduler.CatchUpPolicy.SEND_ONCE, 0,
                    LocalTime.of(12, 0), ZoneId.of("UTC"), LeapDayPolicy.LAST_OF_FEBRUARY);

            MessageBundleHolder holder = new MessageBundleHolder("messages", null,
                    Set.of("ru", "en"), "ru");

            ReminderService reminderService = new ReminderService(
                    storage.profiles(), storage.deliveryLog(), dispatcher, holder,
                    new MenuFactory(holder, null), new NextReminderCalculator(schedulerConfig),
                    schedulerConfig, new MetricsRegistry(),
                    Clock.fixed(NOW, ZoneId.of("UTC")));

            ReminderScheduler scheduler = new ReminderScheduler(
                    storage.profiles(), storage.deliveryLog(), reminderService,
                    new ConversationStore(Duration.ofMinutes(5)),
                    schedulerConfig, new MetricsRegistry(), Clock.fixed(NOW, ZoneId.of("UTC")));

            Profile due = new Profile(new PlatformUser(Platform.TELEGRAM, "1"), "1",
                    Locale.forLanguageTag("ru"), ZoneId.of("UTC"), new BirthDate(6, 15, 1990),
                    true, LocalTime.of(12, 0), NOW.minusSeconds(5), NOW, NOW);

            Profile future = new Profile(new PlatformUser(Platform.TELEGRAM, "2"), "2",
                    Locale.forLanguageTag("ru"), ZoneId.of("UTC"), new BirthDate(6, 15, 1990),
                    true, LocalTime.of(12, 0), NOW.plusSeconds(3600), NOW, NOW);

            storage.profiles().save(due);
            storage.profiles().save(future);

            scheduler.tick(NOW);

            for (int i = 0; i < 200 && adapter.sent().isEmpty(); i++) {
                Thread.sleep(10);
            }

            Thread.sleep(150); // give the async cursor time to update
            assertThat(adapter.sent()).hasSize(1);

            // Another tick in the same minute: in-flight/log prevent duplicates
            scheduler.tick(NOW);
            Thread.sleep(150);
            assertThat(adapter.sent()).hasSize(1);

        } finally {
            dispatcher.close();
        }

    }

}
