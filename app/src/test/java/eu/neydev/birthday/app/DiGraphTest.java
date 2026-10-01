package eu.neydev.birthday.app;

import com.google.inject.Guice;
import com.google.inject.Injector;
import eu.neydev.birthday.app.di.Bootstrap;
import eu.neydev.birthday.app.di.CoreModule;
import eu.neydev.birthday.app.di.PlatformsModule;
import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A DI graph test: the injector is assembled without active platforms, all singletons
 * are bound, Bootstrap starts and stops without side effects.
 */
class DiGraphTest {

    @TempDir
    Path tempDir;

    private AppConfig config() {
        return new AppConfig(
                new AppConfig.Storage(AppConfig.Storage.Type.SQLITE,
                        tempDir.resolve("di.db").toString(), null, null, null, 2),
                new AppConfig.Community("https://github.com/example/repo"),
                new AppConfig.Scheduler(Duration.ofSeconds(30), 10, 3, Duration.ofSeconds(5),
                        AppConfig.Scheduler.CatchUpPolicy.SEND_ONCE, 0,
                        LocalTime.NOON, ZoneId.of("UTC"),
                        eu.neydev.birthday.core.domain.LeapDayPolicy.LAST_OF_FEBRUARY),
                new AppConfig.Pipeline(100, 2, 50, 1, 50, 50, Duration.ofSeconds(5)),
                new AppConfig.WebApp(false, "127.0.0.1", 18080, null, Duration.ofHours(1)),
                new AppConfig.Locale("ru", Set.of("ru", "en")),
                Map.of(),
                List.of(),
                // no liveness file in tests: the heartbeat must not write into the tree
                new AppConfig.Status(Duration.ofMinutes(5), null));
    }

    @Test
    void graphBuildsAndBootstrapLifecycleWorks() throws Exception {

        Injector injector = Guice.createInjector(
                new CoreModule(config(), Clock.systemUTC()));
        Injector full = injector.createChildInjector(new PlatformsModule(config(),
                injector.getInstance(eu.neydev.birthday.core.i18n.MessageBundleHolder.class)));

        Bootstrap bootstrap = full.getInstance(Bootstrap.class);
        assertThat(bootstrap.adapters()).isEmpty();

        bootstrap.start();
        bootstrap.stop();

    }

    @Test
    void inactivePlatformsProduceNoAdapters() {

        Injector injector = Guice.createInjector(
                new CoreModule(config(), Clock.systemUTC()));
        Injector full = injector.createChildInjector(new PlatformsModule(config(),
                injector.getInstance(eu.neydev.birthday.core.i18n.MessageBundleHolder.class)));

        Set<eu.neydev.birthday.core.api.PlatformAdapter> adapters = full.getInstance(
                new com.google.inject.Key<>() {
                });

        assertThat(adapters).isEmpty();

    }

    @Test
    void platformEnumCoversAllShippedAdapters() {
        assertThat(Platform.values()).containsExactly(
                Platform.TELEGRAM, Platform.VK, Platform.DISCORD,
                Platform.SLACK, Platform.WHATSAPP, Platform.VIBER);
    }

    @Test
    void failingAdapterDoesNotKillStartup() throws Exception {

        Injector injector = Guice.createInjector(
                new CoreModule(config(), Clock.systemUTC()));
        Injector full = injector.createChildInjector(
                new PlatformsModule(config(),
                        injector.getInstance(eu.neydev.birthday.core.i18n.MessageBundleHolder.class)),
                new FailingAdapterModule());

        Bootstrap bootstrap = full.getInstance(Bootstrap.class);

        bootstrap.start();
        bootstrap.stop();

    }

    @Test
    void oneFatallyBrokenPlatformDoesNotKillTheOthers() throws Exception {

        Injector injector = Guice.createInjector(
                new CoreModule(config(), Clock.systemUTC()));
        Injector full = injector.createChildInjector(
                new PlatformsModule(config(),
                        injector.getInstance(eu.neydev.birthday.core.i18n.MessageBundleHolder.class)),
                new AdapterModule(List.of(
                        new DeniedAdapter(Platform.VK), new HealthyAdapter(Platform.TELEGRAM))));

        Bootstrap bootstrap = full.getInstance(Bootstrap.class);
        bootstrap.start();

        assertThat(bootstrap.adapters()).hasSize(2);
        bootstrap.stop();

    }

    @Test
    void everyPlatformFatallyBrokenStopsTheBot() {

        Injector injector = Guice.createInjector(
                new CoreModule(config(), Clock.systemUTC()));
        Injector full = injector.createChildInjector(
                new PlatformsModule(config(),
                        injector.getInstance(eu.neydev.birthday.core.i18n.MessageBundleHolder.class)),
                new AdapterModule(List.of(new DeniedAdapter(Platform.VK))));

        Bootstrap bootstrap = full.getInstance(Bootstrap.class);

        assertThatThrownBy(bootstrap::start)
                .isInstanceOf(eu.neydev.birthday.core.api.PlatformException.class)
                .hasMessageContaining("fix the configuration");

        bootstrap.stop();

    }

    /** Binds an explicit adapter set on top of the (inactive) platform configuration. */
    private static final class AdapterModule extends com.google.inject.AbstractModule {

        private final List<eu.neydev.birthday.core.api.PlatformAdapter> adapters;

        AdapterModule(List<eu.neydev.birthday.core.api.PlatformAdapter> adapters) {
            this.adapters = adapters;
        }

        @Override
        protected void configure() {
            com.google.inject.multibindings.Multibinder<eu.neydev.birthday.core.api.PlatformAdapter>
                    binder = com.google.inject.multibindings.Multibinder
                    .newSetBinder(binder(), eu.neydev.birthday.core.api.PlatformAdapter.class);
            adapters.forEach(adapter -> binder.addBinding().toInstance(adapter));
        }

    }

    /** Reports a fatal configuration error from the background connection (VK 15 style). */
    private static final class DeniedAdapter
            implements eu.neydev.birthday.core.api.PlatformAdapter,
            eu.neydev.birthday.core.api.StartupFailureProbe {

        private final Platform platform;

        DeniedAdapter(Platform platform) {
            this.platform = platform;
        }

        @Override
        public Platform platform() {
            return platform;
        }

        @Override
        public void start(eu.neydev.birthday.core.api.PlatformContext context) {
        }

        @Override
        public void stop() {
        }

        @Override
        public void execute(eu.neydev.birthday.core.api.OutboundMessage message) {
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public eu.neydev.birthday.core.api.PlatformException fatalStartupError() {
            return new eu.neydev.birthday.core.api.PlatformException(
                    "token has no rights", null, 15);
        }
    }

    /** Starts cleanly and reports an established connection. */
    private static final class HealthyAdapter
            implements eu.neydev.birthday.core.api.PlatformAdapter,
            eu.neydev.birthday.core.api.ConnectionProbe {
        private final Platform platform;

        HealthyAdapter(Platform platform) {
            this.platform = platform;
        }

        @Override
        public Platform platform() {
            return platform;
        }

        @Override
        public void start(eu.neydev.birthday.core.api.PlatformContext context) {
        }

        @Override
        public void stop() {
        }

        @Override
        public void execute(eu.neydev.birthday.core.api.OutboundMessage message) {
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public boolean connected() {
            return true;
        }
    }

    /** One platform fails at start - the bot must survive and continue without it. */
    private static final class FailingAdapterModule extends com.google.inject.AbstractModule {

        @Override
        protected void configure() {
            com.google.inject.multibindings.Multibinder
                    .newSetBinder(binder(), eu.neydev.birthday.core.api.PlatformAdapter.class)
                    .addBinding().toInstance(new FailingAdapter());
        }

    }

    private static final class FailingAdapter implements eu.neydev.birthday.core.api.PlatformAdapter {

        @Override
        public Platform platform() {
            return Platform.TELEGRAM;
        }

        @Override
        public void start(eu.neydev.birthday.core.api.PlatformContext context) {
            throw new eu.neydev.birthday.core.api.PlatformException("network down");
        }

        @Override
        public void stop() {
        }

        @Override
        public void execute(eu.neydev.birthday.core.api.OutboundMessage message) {
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

    }

}
