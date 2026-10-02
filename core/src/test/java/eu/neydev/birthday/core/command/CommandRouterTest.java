package eu.neydev.birthday.core.command;

import eu.neydev.birthday.core.FakeAdapter;
import eu.neydev.birthday.core.InMemoryStorage;
import eu.neydev.birthday.core.api.IncomingUpdate;
import eu.neydev.birthday.core.api.OutboundMessage;
import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.api.PlatformUser;
import eu.neydev.birthday.core.command.handlers.AboutHandler;
import eu.neydev.birthday.core.command.handlers.AdminHandlers;
import eu.neydev.birthday.core.command.handlers.MenuHandlers;
import eu.neydev.birthday.core.command.handlers.ProfileHandlers;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.conversation.ConversationStore;
import eu.neydev.birthday.core.domain.LeapDayPolicy;
import eu.neydev.birthday.core.i18n.LocaleResolver;
import eu.neydev.birthday.core.i18n.MessageBundleHolder;
import eu.neydev.birthday.core.metrics.MetricsRegistry;
import eu.neydev.birthday.core.pipeline.OutboundDispatcher;
import eu.neydev.birthday.core.schedule.NextReminderCalculator;
import eu.neydev.birthday.core.service.ProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An end-to-end core test: an inbound event -> router -> profile -> outbound commands,
 * without a single platform class (FakeAdapter instead of Telegram).
 */
class CommandRouterTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private static final PlatformUser USER = new PlatformUser(Platform.TELEGRAM, "1");
    private static final String CHAT = "1";

    private InMemoryStorage storage;
    private FakeAdapter adapter;
    private OutboundDispatcher dispatcher;
    private CommandRouter router;
    private ProfileService profileService;

    @BeforeEach
    void setUp() {

        storage = new InMemoryStorage();
        adapter = new FakeAdapter(Platform.TELEGRAM);

        dispatcher = new OutboundDispatcher(2, 3, 50, 100, 100, new MetricsRegistry());
        dispatcher.register(adapter);
        dispatcher.start();

        AppConfig.Scheduler scheduler = new AppConfig.Scheduler(
                Duration.ofSeconds(30), 100, 3, Duration.ofSeconds(20),
                AppConfig.Scheduler.CatchUpPolicy.SEND_ONCE, 0,
                LocalTime.of(12, 0), ZoneId.of("UTC"), LeapDayPolicy.LAST_OF_FEBRUARY);

        AppConfig config = new AppConfig(
                new AppConfig.Storage(AppConfig.Storage.Type.SQLITE, "x", null, null, null, 1),
                new AppConfig.Community("https://github.com/example/repo"),
                scheduler,
                new AppConfig.Pipeline(10, 2, 50, 2, 50, 50, Duration.ofSeconds(5)),
                new AppConfig.WebApp(false, "127.0.0.1", 8080, null, Duration.ofHours(1)),
                new AppConfig.Locale("ru", Set.of("ru", "en")),
                Map.of(), List.of());

        MessageBundleHolder holder = new MessageBundleHolder("messages", null,
                Set.of("ru", "en"), "ru");
        profileService = new ProfileService(storage.profiles(), storage.deliveryLog(),
                new LocaleResolver(Set.of("ru", "en"), "ru"),
                new NextReminderCalculator(scheduler), scheduler,
                Clock.fixed(NOW, ZoneId.of("UTC")));

        ConversationStore conversationStore = new ConversationStore(Duration.ofMinutes(5));
        MenuFactory menuFactory = new MenuFactory(holder, "https://github.com/example/repo");
        ReplyBuilder replies = new ReplyBuilder(holder, menuFactory, config,
                Clock.fixed(NOW, ZoneId.of("UTC")));
        ConversationFlow flow = new ConversationFlow(conversationStore, profileService, replies);

        router = new CommandRouter(profileService, flow, replies,
                new MenuHandlers(replies),
                new ProfileHandlers(profileService, flow, replies),
                new AboutHandler(replies),
                new AdminHandlers(holder, config, replies),
                dispatcher, new MetricsRegistry(), config);

    }

    @Test
    void theQuietMenuButtonSummonsTheFullMenuAsAFreshMessage() throws InterruptedException {

        router.accept(new IncomingUpdate.Callback(USER, CHAT, Actions.MENU_OPEN,
                "42", "cb1", "ru"));
        awaitSent(2);

        List<OutboundMessage.Send> sends = adapter.sentOfType(OutboundMessage.Send.class);
        assertThat(sends).hasSize(1);
        // the reminder-sized text stays on the screen: the menu is a NEW message, not an edit
        assertThat(sends.get(0).text().toPlainText()).contains("Старт");
        assertThat(sends.get(0).keyboard().rows()).hasSizeGreaterThan(3);

    }

    @AfterEach
    void tearDown() {
        dispatcher.close();
    }

    private void awaitSent(int count) throws InterruptedException {
        for (int i = 0; i < 200 && adapter.sent().size() < count; i++) {
            Thread.sleep(10);
        }
    }

    @Test
    void startCreatesProfileAndSendsMenu() throws Exception {

        router.accept(new IncomingUpdate.TextMessage(USER, CHAT, "/start", "ru"));
        awaitSent(1);

        OutboundMessage.Send send = adapter.sentOfType(OutboundMessage.Send.class).get(0);
        assertThat(send.text().toPlainText()).isEqualTo("Старт");
        assertThat(send.keyboard().rows()).isNotEmpty();
        assertThat(storage.profiles().find(USER)).isPresent();

    }

    @Test
    void setDateConversationFlow() throws Exception {

        router.accept(new IncomingUpdate.TextMessage(USER, CHAT, "/date", "ru"));
        awaitSent(1);

        router.accept(new IncomingUpdate.TextMessage(USER, CHAT, "05.03.1998", "ru"));
        awaitSent(2);

        var profile = storage.profiles().find(USER).orElseThrow();

        assertThat(profile.birthDate().day()).isEqualTo(5);
        assertThat(profile.birthDate().month()).isEqualTo(3);
        assertThat(profile.birthDate().year()).isEqualTo(1998);
        assertThat(profile.nextReminderAt()).isNotNull();

        OutboundMessage.Send confirm = adapter.sentOfType(OutboundMessage.Send.class).get(1);
        assertThat(confirm.text().toPlainText()).contains("1998");

    }

    @Test
    void invalidDateKeepsConversation() throws Exception {

        router.accept(new IncomingUpdate.TextMessage(USER, CHAT, "/date", "ru"));
        awaitSent(1);

        router.accept(new IncomingUpdate.TextMessage(USER, CHAT, "nonsense", "ru"));
        awaitSent(2);

        assertThat(storage.profiles().find(USER).orElseThrow().birthDate()).isNull();
        assertThat(adapter.sentOfType(OutboundMessage.Send.class).get(1).text().toPlainText())
                .isEqualTo("Не разобрал");

    }

    @Test
    void callbackNavigationEditsMessage() throws Exception {

        router.accept(new IncomingUpdate.Callback(USER, CHAT, Actions.SETTINGS, "77", "int-1", "ru"));
        awaitSent(2); // AnswerCallback + Edit

        assertThat(adapter.sentOfType(OutboundMessage.AnswerCallback.class)).hasSize(1);
        OutboundMessage.Edit edit = adapter.sentOfType(OutboundMessage.Edit.class).get(0);
        assertThat(edit.messageId()).isEqualTo("77");
        assertThat(edit.text().toPlainText()).isEqualTo("Настройки");

    }

    @Test
    void daysToWithoutDateAsksToSet() throws Exception {

        router.accept(new IncomingUpdate.TextMessage(USER, CHAT, "/daysto", "ru"));
        awaitSent(1);
        assertThat(adapter.sentOfType(OutboundMessage.Send.class).get(0).text().toPlainText())
                .isEqualTo("Даты нет");

    }

    @Test
    void localeHintFromPlatformProfileIsRespected() throws Exception {

        router.accept(new IncomingUpdate.TextMessage(USER, CHAT, "/start", "en"));
        awaitSent(1);
        assertThat(storage.profiles().find(USER).orElseThrow().locale().getLanguage()).isEqualTo("en");
        assertThat(adapter.sentOfType(OutboundMessage.Send.class).get(0).text().toPlainText())
                .isEqualTo("Start");

    }

}
