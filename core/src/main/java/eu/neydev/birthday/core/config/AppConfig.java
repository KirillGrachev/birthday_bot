package eu.neydev.birthday.core.config;

import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.domain.LeapDayPolicy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fully typed application configuration. Immutable: hot edits
 * performed by a restart (messages are re-read hot in the process).
 *
 * <p>Secrets are NOT stored in the file: values like {@code ${TELEGRAM_BOT_TOKEN}}
 * substituted from environment variables at load time.
 */
public record AppConfig(@NotNull Storage storage,
                        @NotNull Community community,
                        @NotNull Scheduler scheduler,
                        @NotNull Pipeline pipeline,
                        @NotNull WebApp webApp,
                        @NotNull Locale locale,
                        @NotNull Map<Platform, PlatformSection> platforms,
                        @NotNull List<String> ownerKeys,
                        @NotNull Status status) {

    public AppConfig {
        platforms = Map.copyOf(platforms);
        ownerKeys = List.copyOf(ownerKeys);
    }

    /** Constructor without the {@code status} section - its defaults suit tests and old configs. */
    public AppConfig(@NotNull Storage storage,
                     @NotNull Community community,
                     @NotNull Scheduler scheduler,
                     @NotNull Pipeline pipeline,
                     @NotNull WebApp webApp,
                     @NotNull Locale locale,
                     @NotNull Map<Platform, PlatformSection> platforms,
                     @NotNull List<String> ownerKeys) {
        this(storage, community, scheduler, pipeline, webApp, locale, platforms, ownerKeys,
                Status.DEFAULT);
    }

    /** Platform section; for those not specified in the config - guaranteed disabled. */
    public PlatformSection platform(Platform platform) {
        return platforms.getOrDefault(platform, PlatformSection.DISABLED);
    }

    /** Storage for profiles and the delivery log. */
    public record Storage(@NotNull Type type,
                          @NotNull String sqlitePath,
                          @Nullable String jdbcUrl,
                          @Nullable String username,
                          @Nullable String password,
                          int poolSize) {
        public enum Type { SQLITE, POSTGRES }
    }

    /** The reminder scheduler. */
    public record Scheduler(Duration tickInterval,
                            int batchSize,
                            int maxAttempts,
                            Duration retryBackoff,
                            CatchUpPolicy catchUpPolicy,
                            int countdownStartDaysBefore,
                            LocalTime defaultNotifyTime,
                            ZoneId defaultZone,
                            LeapDayPolicy leapDayPolicy,
                            Duration deliveryLogRetention) {
        /**
         * How long {@code delivery_log} rows are kept before the daily prune removes them.
         * The log is a deduplication key (user, kind, local date), so a year plus a margin
         * is more than enough; without a prune the table grows forever.
         */
        public static final Duration DEFAULT_DELIVERY_LOG_RETENTION = Duration.ofDays(400);

        /** Constructor without the retention: the default keeps the log for 400 days. */
        public Scheduler(Duration tickInterval,
                         int batchSize,
                         int maxAttempts,
                         Duration retryBackoff,
                         CatchUpPolicy catchUpPolicy,
                         int countdownStartDaysBefore,
                         LocalTime defaultNotifyTime,
                         ZoneId defaultZone,
                         LeapDayPolicy leapDayPolicy) {
            this(tickInterval, batchSize, maxAttempts, retryBackoff, catchUpPolicy,
                    countdownStartDaysBefore, defaultNotifyTime, defaultZone, leapDayPolicy,
                    DEFAULT_DELIVERY_LOG_RETENTION);
        }

        /** Zero or negative retention means "keep forever" - the prune is then skipped. */
        public boolean prunesDeliveryLog() {
            return deliveryLogRetention != null && !deliveryLogRetention.isNegative()
                    && !deliveryLogRetention.isZero();
        }

        /**
         * What to do with reminders missed during downtime.
         * SEND_ONCE - send once on the first tick after start (no later than
         * {@code catchUpWindow} from the scheduled moment), SKIP - silently skip.
         */

        public enum CatchUpPolicy { SEND_ONCE, SKIP }
    }

    /** Inbound/outbound message pipelines and rate limiting. */
    public record Pipeline(int inboundQueueCapacity,
                           int workerThreads,
                           int inboundPerUserPerWindow,
                           int outboundWorkersPerPlatform,
                           double outboundGlobalPerSecond,
                           int outboundPerChatPerMinute,
                           Duration sendTimeout) {
    }

    /** The built-in web app (Mini App) and service HTTP endpoints. */
    public record WebApp(boolean enabled,
                         @NotNull String bindHost,
                         int port,
                         @Nullable String publicUrl,
                         Duration initDataMaxAge,
                         @Nullable String metricsToken) {
        /** Constructor without the metrics token: {@code /metrics} stays open. */
        public WebApp(boolean enabled, @NotNull String bindHost, int port,
                      @Nullable String publicUrl, Duration initDataMaxAge) {
            this(enabled, bindHost, port, publicUrl, initDataMaxAge, null);
        }

        /** Every call site asked the negative, so the config speaks the negative. */
        public boolean metricsTokenMissing() {
            return metricsToken == null || metricsToken.isBlank();
        }

        /**
         * A non-loopback bind address exposes {@code /metrics} and {@code /healthz} to the
         * network; without a token anyone reaching the port can read the counters.
         */
        public boolean bindsPublicly() {
            return !bindHost.isBlank() && !bindHost.equals("127.0.0.1")
                    && !bindHost.equals("localhost") && !bindHost.equals("::1");
        }
    }

    /**
     * Operational visibility without an HTTP port: one INFO line with the key counters
     * every {@code heartbeatInterval}, plus a liveness file touched on the same beat.
     * The Minecraft launcher plugin reads that line to tell a live bot from a hung one,
     * a container HEALTHCHECK looks at the file's mtime.
     */
    public record Status(Duration heartbeatInterval, @Nullable String livenessFile) {

        public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofMinutes(5);
        public static final String DEFAULT_LIVENESS_FILE = "data/bot.liveness";
        public static final Status DEFAULT =
                new Status(DEFAULT_HEARTBEAT_INTERVAL, DEFAULT_LIVENESS_FILE);

    }

    /** Public community: repository, star CTA in the bot and Mini App. */
    public record Community(@Nullable String githubUrl) {

        public boolean hasGithub() {
            return githubUrl != null && !githubUrl.isBlank();
        }

    }

    /**
     * Languages. The iteration order of {@code supported} is preserved and defines
     * the button order in the language picker; {@code displayNames} maps a language
     * code to its human-readable name (each language in its own script).
     */
    public record Locale(@NotNull String defaultLanguage,
                         @NotNull Set<String> supported,
                         @NotNull Map<String, String> displayNames) {

        public Locale {
            supported = Collections.unmodifiableSet(new LinkedHashSet<>(supported));
            displayNames = Map.copyOf(displayNames);
        }

        public Locale(@NotNull String defaultLanguage, @NotNull Set<String> supported) {
            this(defaultLanguage, supported, Map.of());
        }
    }

    /** Platform settings: token from the environment + platform details. */
    public record PlatformSection(boolean enabled,
                                  @Nullable String token,
                                  @Nullable String extraId,
                                  @Nullable String secret,
                                  @Nullable String proxy) {

        public static final PlatformSection DISABLED =
                new PlatformSection(false, null, null, null, null);

        public boolean hasToken() {
            return token != null && !token.isBlank();
        }

        /** Active = enabled AND token present: the bot starts partially degraded instead of crashing. */
        public boolean isActive() {
            return enabled && hasToken();
        }
    }

}
