package eu.neydev.birthday.app.di;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import eu.neydev.birthday.core.command.CommandRouter;
import eu.neydev.birthday.core.command.ConversationFlow;
import eu.neydev.birthday.core.command.ReplyBuilder;
import eu.neydev.birthday.core.command.handlers.AboutHandler;
import eu.neydev.birthday.core.command.handlers.AdminHandlers;
import eu.neydev.birthday.core.command.handlers.MenuHandlers;
import eu.neydev.birthday.core.command.handlers.ProfileHandlers;
import eu.neydev.birthday.core.command.MenuFactory;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.conversation.ConversationStore;
import eu.neydev.birthday.core.i18n.LocaleResolver;
import eu.neydev.birthday.core.i18n.MessageBundleHolder;
import eu.neydev.birthday.core.i18n.ZoneCityNames;
import eu.neydev.birthday.core.metrics.MetricsRegistry;
import eu.neydev.birthday.core.metrics.PlatformHealth;
import eu.neydev.birthday.core.pipeline.InboundRouter;
import eu.neydev.birthday.core.pipeline.OutboundDispatcher;
import eu.neydev.birthday.core.schedule.NextReminderCalculator;
import eu.neydev.birthday.core.schedule.ReminderScheduler;
import eu.neydev.birthday.core.service.ProfileService;
import eu.neydev.birthday.core.service.ReminderService;
import eu.neydev.birthday.core.storage.JdbcStorage;
import eu.neydev.birthday.core.storage.ProfileRepository;
import eu.neydev.birthday.core.storage.DeliveryLogRepository;
import eu.neydev.birthday.core.webapp.WebAppServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * Core DI module: each service is an explicit {@code @Provides @Singleton} with
 * constructor dependencies. The graph is assembled by Guice but stays
 * traceable: not a single hidden dependency via field reflection.
 */
/**
 * The whole object graph of the core, assembled by Guice.
 *
 * <p>Every {@code @Provides} method below is called by Guice at injection time and never
 * from hand-written code: an IDE "method is never used" warning on them is expected
 * and must stay a warning, not become a deletion.
 */
public final class CoreModule extends AbstractModule {

    private final AppConfig config;
    private final Clock clock;

    public CoreModule(AppConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    @Override
    protected void configure() {
        bind(AppConfig.class).toInstance(config);
        bind(Clock.class).toInstance(clock);
    }

    @Provides
    @Singleton
    MetricsRegistry metrics() {
        return new MetricsRegistry();
    }

    @Provides
    @Singleton
    JdbcStorage storage(AppConfig config) {
        return JdbcStorage.open(config.storage());
    }

    @Provides
    ProfileRepository profiles(JdbcStorage storage) {
        return storage.profiles();
    }

    @Provides
    DeliveryLogRepository deliveryLog(JdbcStorage storage) {
        return storage.deliveryLog();
    }

    @Provides
    @Singleton
    MessageBundleHolder bundleHolder(AppConfig config, MetricsRegistry metrics) {
        Path external = Path.of("config/messages");
        return new MessageBundleHolder("messages",
                Files.isDirectory(external) ? external : null,
                config.locale().supported(), config.locale().defaultLanguage(), metrics);
    }

    @Provides
    @Singleton
    PlatformHealth platformHealth() {
        return new PlatformHealth();
    }

    @Provides
    @Singleton
    LocaleResolver localeResolver(AppConfig config) {
        return new LocaleResolver(config.locale().supported(), config.locale().defaultLanguage());
    }

    @Provides
    @Singleton
    ZoneCityNames zoneCityNames(AppConfig config) {
        return ZoneCityNames.load(config.locale().supported());
    }

    @Provides
    @Singleton
    MenuFactory menuFactory(MessageBundleHolder holder, AppConfig config, ZoneCityNames zoneCities) {
        return new MenuFactory(holder,
                config.community().hasGithub() ? config.community().githubUrl() : null,
                config.locale(), zoneCities);
    }

    @Provides
    @Singleton
    NextReminderCalculator calculator(AppConfig config) {
        return new NextReminderCalculator(config.scheduler());
    }

    @Provides
    @Singleton
    ConversationStore conversationStore() {
        return new ConversationStore(Duration.ofMinutes(5));
    }

    @Provides
    @Singleton
    OutboundDispatcher dispatcher(AppConfig config, MetricsRegistry metrics) {
        return new OutboundDispatcher(
                config.pipeline().outboundWorkersPerPlatform(),
                config.scheduler().maxAttempts(),
                config.scheduler().retryBackoff().toMillis(),
                config.pipeline().outboundGlobalPerSecond(),
                config.pipeline().outboundPerChatPerMinute(),
                metrics);
    }

    @Provides
    @Singleton
    ProfileService profileService(ProfileRepository repository, DeliveryLogRepository deliveryLog,
                                  LocaleResolver localeResolver,
                                  NextReminderCalculator calculator, AppConfig config, Clock clock) {
        return new ProfileService(repository, deliveryLog, localeResolver, calculator,
                config.scheduler(), clock);
    }

    @Provides
    @Singleton
    ReminderService reminderService(ProfileRepository profiles, DeliveryLogRepository deliveryLog,
                                    OutboundDispatcher dispatcher, MessageBundleHolder holder,
                                    MenuFactory menuFactory, NextReminderCalculator calculator,
                                    AppConfig config, MetricsRegistry metrics, Clock clock) {
        return new ReminderService(profiles, deliveryLog, dispatcher, holder, menuFactory,
                calculator, config.scheduler(),
                metrics, clock);
    }

    @Provides
    @Singleton
    ReplyBuilder replyBuilder(MessageBundleHolder holder, MenuFactory menuFactory, AppConfig config,
                              Clock clock) {
        return new ReplyBuilder(holder, menuFactory, config, clock);
    }

    @Provides
    @Singleton
    ConversationFlow conversationFlow(ConversationStore conversationStore,
                                      ProfileService profileService, ReplyBuilder replyBuilder) {
        return new ConversationFlow(conversationStore, profileService, replyBuilder);
    }

    @Provides
    @Singleton
    CommandRouter commandRouter(ProfileService profileService, ConversationFlow conversationFlow,
                                ReplyBuilder replyBuilder, MenuFactory menuFactory,
                                MessageBundleHolder holder, AppConfig config,
                                OutboundDispatcher dispatcher, MetricsRegistry metrics) {
        return new CommandRouter(profileService, conversationFlow, replyBuilder,
                new MenuHandlers(replyBuilder),
                new ProfileHandlers(profileService, conversationFlow, replyBuilder),
                new AboutHandler(replyBuilder),
                new AdminHandlers(holder, config, replyBuilder),
                dispatcher, metrics, config);
    }

    @Provides
    @Singleton
    InboundRouter inboundRouter(AppConfig config, CommandRouter router, MetricsRegistry metrics) {
        return new InboundRouter(config.pipeline().inboundQueueCapacity(),
                config.pipeline().workerThreads(), router, metrics);
    }

    @Provides
    @Singleton
    ReminderScheduler reminderScheduler(ProfileRepository profiles,
                                        DeliveryLogRepository deliveryLog,
                                        ReminderService reminderService,
                                        ConversationStore conversationStore, AppConfig config,
                                        MetricsRegistry metrics, Clock clock) {
        return new ReminderScheduler(profiles, deliveryLog, reminderService, conversationStore,
                config.scheduler(), metrics, clock);
    }

    @Provides
    @Singleton
    WebAppServer webAppServer(AppConfig config, ProfileService profileService,
                              MessageBundleHolder holder, MetricsRegistry metrics, Clock clock) {
        return new WebAppServer(config, profileService, holder, metrics, clock);
    }

}
