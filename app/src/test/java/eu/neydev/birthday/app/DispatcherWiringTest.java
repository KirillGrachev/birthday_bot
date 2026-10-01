package eu.neydev.birthday.app;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.multibindings.Multibinder;
import eu.neydev.birthday.app.di.Bootstrap;
import eu.neydev.birthday.app.di.CoreModule;
import eu.neydev.birthday.core.api.ConnectionProbe;
import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.api.OutboundMessage;
import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.api.PlatformAdapter;
import eu.neydev.birthday.core.api.PlatformContext;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.domain.LeapDayPolicy;
import eu.neydev.birthday.core.pipeline.OutboundDispatcher;
import eu.neydev.birthday.core.text.RichText;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A regression test for the wiring between {@link Bootstrap} and the outbound dispatcher.
 * The dispatcher sends only to the adapters it was given, and a platform that was never
 * registered makes every reply and every reminder disappear without a single log line -
 * the bot looks perfectly healthy while staying mute. The graph must never start that way.
 */
class DispatcherWiringTest {

    @TempDir
    Path tempDir;

    private AppConfig config() {
        return new AppConfig(
                new AppConfig.Storage(AppConfig.Storage.Type.SQLITE,
                        tempDir.resolve("wiring.db").toString(), null, null, null, 2),
                new AppConfig.Community("https://github.com/example/repo"),
                new AppConfig.Scheduler(Duration.ofSeconds(30), 10, 3, Duration.ofSeconds(5),
                        AppConfig.Scheduler.CatchUpPolicy.SEND_ONCE, 0,
                        LocalTime.NOON, ZoneId.of("UTC"), LeapDayPolicy.LAST_OF_FEBRUARY),
                new AppConfig.Pipeline(100, 2, 50, 1, 50, 50, Duration.ofSeconds(5)),
                new AppConfig.WebApp(false, "127.0.0.1", 18080, null, Duration.ofHours(1)),
                new AppConfig.Locale("ru", Set.of("ru", "en")),
                Map.of(),
                List.of(),
                // no liveness file in tests: the heartbeat must not write into the tree
                new AppConfig.Status(Duration.ofMinutes(5), null));
    }

    @Test
    void aReplySubmittedAfterStartReachesTheAdapter() throws Exception {

        RecordingAdapter adapter = new RecordingAdapter(Platform.TELEGRAM);

        Injector injector = Guice.createInjector(new CoreModule(config(), Clock.systemUTC()));
        Injector full = injector.createChildInjector(new AdapterModule(List.of(adapter)));

        Bootstrap bootstrap = full.getInstance(Bootstrap.class);
        bootstrap.start();

        try {

            full.getInstance(OutboundDispatcher.class)
                    .submit(new OutboundMessage.Send(Platform.TELEGRAM, "42",
                            RichText.plain("ping"), InlineKeyboard.empty(), false))
                    .get(10, TimeUnit.SECONDS);

            assertThat(adapter.sent()).containsExactly("ping");

        } finally {
            bootstrap.stop();
        }

    }

    /** Binds an explicit adapter set on top of the (inactive) platform configuration. */
    private static final class AdapterModule extends AbstractModule {

        private final List<PlatformAdapter> adapters;

        AdapterModule(List<PlatformAdapter> adapters) {
            this.adapters = adapters;
        }

        @Override
        protected void configure() {
            Multibinder<PlatformAdapter> binder =
                    Multibinder.newSetBinder(binder(), PlatformAdapter.class);
            adapters.forEach(adapter -> binder.addBinding().toInstance(adapter));
        }

    }

    /** A platform that is up and records everything the dispatcher hands it. */
    private static final class RecordingAdapter implements PlatformAdapter, ConnectionProbe {

        private final Platform platform;
        private final List<String> sent = new CopyOnWriteArrayList<>();

        RecordingAdapter(Platform platform) {
            this.platform = platform;
        }

        List<String> sent() {
            return sent;
        }

        @Override
        public Platform platform() {
            return platform;
        }

        @Override
        public void start(PlatformContext context) {
        }

        @Override
        public void stop() {
        }

        @Override
        public void execute(OutboundMessage message) {
            if (message instanceof OutboundMessage.Send send) {
                sent.add(send.text().toPlainText());
            }
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

}
