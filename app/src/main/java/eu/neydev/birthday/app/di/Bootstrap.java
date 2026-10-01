package eu.neydev.birthday.app.di;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import eu.neydev.birthday.core.api.ConnectionProbe;
import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.api.PlatformAdapter;
import eu.neydev.birthday.core.api.PlatformException;
import eu.neydev.birthday.core.api.StartupFailureProbe;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.api.PlatformContext;
import eu.neydev.birthday.core.http.WebhookHandler;
import eu.neydev.birthday.core.metrics.PlatformHealth;
import eu.neydev.birthday.core.metrics.MetricsRegistry;
import eu.neydev.birthday.core.metrics.StatusHeartbeat;
import eu.neydev.birthday.core.pipeline.InboundRouter;
import eu.neydev.birthday.core.pipeline.OutboundDispatcher;
import eu.neydev.birthday.core.schedule.ReminderScheduler;
import eu.neydev.birthday.core.storage.JdbcStorage;
import eu.neydev.birthday.core.storage.ProfileRepository;
import eu.neydev.birthday.core.webapp.WebAppServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * The lifecycle orchestrator: the single point that knows the start order
 * and stopping. Injection via the constructor - Guice binds the graph, Bootstrap
 * only manages the lifetime.
 */
@Singleton
public final class Bootstrap {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    private final AppConfig config;
    private final JdbcStorage storage;
    private final ProfileRepository profiles;
    private final MetricsRegistry metrics;
    private final InboundRouter inboundRouter;
    private final OutboundDispatcher dispatcher;
    private final ReminderScheduler scheduler;
    private final WebAppServer webAppServer;
    private final PlatformHealth platformHealth;
    private final List<PlatformAdapter> adapters;
    private final Clock clock;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    /** Stopping happens either from the shutdown hook or from main - whichever comes first. */
    private final java.util.concurrent.atomic.AtomicBoolean stopped =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile StatusHeartbeat heartbeat;

    @Inject
    Bootstrap(AppConfig config,
              JdbcStorage storage,
              ProfileRepository profiles,
              MetricsRegistry metrics,
              InboundRouter inboundRouter,
              OutboundDispatcher dispatcher,
              ReminderScheduler scheduler,
              WebAppServer webAppServer,
              PlatformHealth platformHealth,
              Clock clock,
              Set<PlatformAdapter> adapterSet) {

        this.config = config;
        this.storage = storage;
        this.profiles = profiles;
        this.metrics = metrics;
        this.inboundRouter = inboundRouter;
        this.dispatcher = dispatcher;
        this.scheduler = scheduler;
        this.webAppServer = webAppServer;
        this.platformHealth = platformHealth;
        this.clock = clock;

        this.adapters = List.copyOf(adapterSet);

    }

    public void start() throws Exception {

        boolean webNeeded = config.webApp().enabled()
                || adapters.stream().anyMatch(adapter -> adapter instanceof WebhookHandler);

        if (webNeeded) {
            adapters.stream()
                    .filter(adapter -> adapter instanceof WebhookHandler)
                    .forEach(adapter -> webAppServer.registerWebhook((WebhookHandler) adapter));
            webAppServer.start();
        }

        metrics.gauge("profiles_total", profiles::count);
        platformHealth.register(metrics, adapters.stream().map(PlatformAdapter::platform).toList());

        // The dispatcher builds a worker pool only for the platforms it knows about and
        // rejects submit() for anything else, so registration has to happen BEFORE start():
        // an unregistered platform loses every reply and every reminder without a log line.
        adapters.forEach(adapter -> {
            dispatcher.register(adapter);
            log.debug("Outbound dispatcher: platform {} registered", adapter.platform().id());
        });

        inboundRouter.start();
        dispatcher.start();

        PlatformContext context = new PlatformContext(inboundRouter, platformHealth);
        List<String> failed = new ArrayList<>();
        List<PlatformException> fatal = new ArrayList<>();

        adapters.forEach(adapter -> {

            try {

                adapter.start(context);
                PlatformException startupError = adapter instanceof StartupFailureProbe probe
                        ? probe.fatalStartupError()
                        : null;

                if (startupError != null) {
                    failed.add(adapter.platform().id());
                    fatal.add(startupError);
                } else if (adapter instanceof ConnectionProbe probe && !probe.connected()) {
                    log.info("Platform {} is connecting in the background (network unavailable - retrying)",
                            adapter.platform().id());
                } else {
                    log.info("Platform active: {}", adapter.platform().id());
                }

            } catch (RuntimeException e) {
                failed.add(adapter.platform().id());
                log.error("Platform {} did not start - the bot continues without it",
                        adapter.platform().id(), e);
            }

        });

        scheduler.start();
        heartbeat = new StatusHeartbeat(config.status().heartbeatInterval(), livenessFile(),
                metrics, platformHealth, adapters, profiles::count, clock);
        heartbeat.start();

        // The hook performs the stop itself: the JVM halts as soon as the hooks return and
        // never waits for the main thread, so leaving stop() to main races the exit and the
        // scheduler, adapters and the connection pool are closed only by luck.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop();
            shutdownLatch.countDown();
        }, "shutdown-trigger"));

        log.info("Birthday Bot started: platforms={}, profiles={}",
                adapters.size() - failed.size(), profiles.count());

        if (!failed.isEmpty()) {
            log.warn("Platforms that failed to start: {}. Check tokens/network/proxy; "
                    + "the bot keeps running with the rest", String.join(", ", failed));
        }

        if (adapters.isEmpty() || failed.size() == adapters.size()) {
            log.warn("No platform connected: reminders accumulate in storage "
                    + "and will be delivered once connectivity is restored");
        }

        if (!fatal.isEmpty()) {

            fatal.forEach(error -> log.error("Platform startup error: {} - fix the configuration",
                    error.getMessage()));

            if (failed.size() == adapters.size()) {
                PlatformException first = fatal.get(0);
                throw new PlatformException(first.getMessage() + " - fix the configuration", first);
            }

            log.warn("The bot keeps running with the platforms that started: {}",
                    adapters.stream()
                            .map(PlatformAdapter::platform)
                            .map(Platform::id)
                            .filter(id -> !failed.contains(id))
                            .toList());

        }

    }

    public void awaitShutdown() {
        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Idempotent: called from the shutdown hook or from main, whichever comes first. */
    public void stop() {

        if (!stopped.compareAndSet(false, true)) {
            return;
        }

        log.info("Stopping...");

        StatusHeartbeat beat = heartbeat;
        if (beat != null) beat.close();

        scheduler.close();
        adapters.forEach(PlatformAdapter::stop);
        inboundRouter.close();
        dispatcher.close();
        webAppServer.close();
        storage.close();

        log.info("Stopped cleanly");

    }

    /** Where watchers read the last heartbeat from, or {@code null} when disabled. */
    private @Nullable Path livenessFile() {
        String path = config.status().livenessFile();
        return path != null && !path.isBlank() ? Path.of(path) : null;
    }

    public List<PlatformAdapter> adapters() {
        return adapters;
    }

}
