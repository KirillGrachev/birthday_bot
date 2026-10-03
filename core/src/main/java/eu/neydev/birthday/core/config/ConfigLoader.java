package eu.neydev.birthday.core.config;

import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.domain.DateParsers;
import eu.neydev.birthday.core.domain.LeapDayPolicy;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code application.yml} loader: SnakeYAML -> Map -> typed records
 * with validation and human-readable errors. Substituting secrets from the environment:
 * {@code ${VAR}} and {@code ${VAR:default}}.
 */
public record ConfigLoader(Function<String, String> environment) {

    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?}");

    private static final Set<String> ROOT_KEYS = Set.of(
            "storage", "scheduler", "pipeline", "webapp", "community", "locale", "platforms",
            "owners", "status");

    private static final Map<String, Set<String>> SECTION_KEYS = Map.of(
            "storage", Set.of("type", "sqlite-path", "jdbc-url", "username", "password", "pool-size"),
            "scheduler", Set.of("tick-interval", "batch-size", "max-attempts", "retry-backoff",
                    "catch-up-policy", "countdown-start-days-before", "default-notify-time",
                    "default-zone", "leap-day-policy", "delivery-log-retention"),
            "pipeline", Set.of("inbound-queue-capacity", "worker-threads",
                    "inbound-per-user-per-window", "outbound-workers-per-platform",
                    "outbound-global-per-second", "outbound-per-chat-per-minute", "send-timeout"),
            "webapp", Set.of("enabled", "bind-host", "port", "public-url", "init-data-max-age",
                    "metrics-token"),
            "community", Set.of("github-url"),
            "locale", Set.of("default", "supported", "display-names"),
            "status", Set.of("heartbeat-interval", "liveness-file"));

    private static final Set<String> PLATFORM_KEYS =
            Set.of("enabled", "token", "id", "secret", "proxy");

    /** Allowed proxy schemes in {@code platforms.*.proxy}. */
    private static final Set<String> PROXY_PREFIXES = Set.of("http://", "https://", "socks5://", "socks://");

    public ConfigLoader() {
        this(name -> {
            String value = System.getenv(name);
            return value != null ? value : DotEnv.get(name);
        });
    }

    public AppConfig load(@Nullable Path externalFile) {
        Map<String, Object> root = readYaml(externalFile);
        return parse(root);
    }

    private Map<String, Object> readYaml(@Nullable Path externalFile) {

        if (externalFile != null && Files.isReadable(externalFile)) {
            try (InputStream in = Files.newInputStream(externalFile)) {
                return new Yaml().load(in);
            } catch (IOException e) {
                throw new ConfigException("Cannot read file " + externalFile, e);
            }
        }

        return loadDefaultResource();

    }

    private Map<String, Object> loadDefaultResource() {
        try (InputStream in = ConfigLoader.class.getClassLoader().getResourceAsStream("application.yml")) {
            if (in == null) {
                throw new ConfigException("application.yml", "neither external file nor resource found");
            }

            return new Yaml().load(in);
        } catch (IOException e) {
            throw new ConfigException("Cannot read application.yml", e);
        }
    }

    @SuppressWarnings("unchecked")
    private AppConfig parse(Map<String, Object> root) {

        if (root == null) {
            throw new ConfigException("<root>", "empty YAML");
        }

        validateKeys(root);
        Map<String, Object> storage = section(root, "storage");
        Map<String, Object> community = section(root, "community");
        Map<String, Object> scheduler = section(root, "scheduler");
        Map<String, Object> pipeline = section(root, "pipeline");
        Map<String, Object> webApp = section(root, "webapp");
        Map<String, Object> locale = section(root, "locale");
        Map<String, Object> platforms = section(root, "platforms");
        Map<String, Object> status = section(root, "status");

        AppConfig.Storage storageConfig = parseStorage(storage);
        AppConfig.Community communityConfig = new AppConfig.Community(
                str(community, "github-url", null));
        AppConfig.Scheduler schedulerConfig = parseScheduler(scheduler);
        AppConfig.Pipeline pipelineConfig = parsePipeline(pipeline);
        AppConfig.WebApp webAppConfig = parseWebApp(webApp);
        AppConfig.Locale localeConfig = parseLocale(locale);
        Map<Platform, AppConfig.PlatformSection> platformSections = parsePlatforms(platforms);
        // Owners may live in the environment (OWNERS=telegram:1,vk:2) as a comma string:
        // a deployment secret-keeper hands them over without touching the YAML list.
        Object ownersNode = get(root, "owners");
        if (ownersNode instanceof String scalar) {
            ownersNode = substitute(scalar, "owners");
        }
        List<String> owners = stringList(ownersNode, List.of());

        return new AppConfig(storageConfig, communityConfig, schedulerConfig, pipelineConfig,
                webAppConfig, localeConfig, platformSections, owners, parseStatus(status));

    }

    /**
     * Heartbeat and liveness file: observability that needs no open port, which matters
     * for deployments where the bot is a child process of something else (a game server)
     * and nobody can reach its HTTP endpoints.
     */
    private AppConfig.Status parseStatus(Map<String, Object> node) {
        return new AppConfig.Status(
                duration(node, "heartbeat-interval", AppConfig.Status.DEFAULT_HEARTBEAT_INTERVAL),
                str(node, "liveness-file", AppConfig.Status.DEFAULT_LIVENESS_FILE));
    }

    private AppConfig.Storage parseStorage(Map<String, Object> node) {

        String type = str(node, "type", "sqlite");
        AppConfig.Storage.Type storageType;

        try {
            storageType = AppConfig.Storage.Type.valueOf(type.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException("storage.type", "unknown type '" + type + "' (sqlite | postgres)");
        }

        return new AppConfig.Storage(
                storageType,
                str(node, "sqlite-path", "data/birthday.db"),
                str(node, "jdbc-url", null),
                str(node, "username", null),
                str(node, "password", null),
                positiveInt(node, "pool-size", 8));

    }

    private AppConfig.Scheduler parseScheduler(Map<String, Object> node) {

        String zoneRaw = str(node, "default-zone", "UTC");
        ZoneId zone = DateParsers.parseZone(zoneRaw)
                .orElseThrow(() -> new ConfigException("scheduler.default-zone", "invalid zone " + zoneRaw));
        LocalTime defaultTime = DateParsers.parseTime(str(node, "default-notify-time", "12:00"))
                .orElseThrow(() -> new ConfigException("scheduler.default-notify-time", "invalid time"));

        return new AppConfig.Scheduler(
                duration(node, "tick-interval", Duration.ofSeconds(30)),
                positiveInt(node, "batch-size", 500),
                positiveInt(node, "max-attempts", 5),
                duration(node, "retry-backoff", Duration.ofSeconds(20)),
                enumOf(node, "catch-up-policy", AppConfig.Scheduler.CatchUpPolicy.class,
                        AppConfig.Scheduler.CatchUpPolicy.SEND_ONCE),
                nonNegativeInt(node, "countdown-start-days-before", 0),
                defaultTime,
                zone,
                parseLeapPolicy(str(node, "leap-day-policy", "last-of-february")),
                duration(node, "delivery-log-retention",
                        AppConfig.Scheduler.DEFAULT_DELIVERY_LOG_RETENTION));

    }

    private AppConfig.Pipeline parsePipeline(Map<String, Object> node) {
        return new AppConfig.Pipeline(
                positiveInt(node, "inbound-queue-capacity", 10_000),
                positiveInt(node, "worker-threads", 16),
                positiveInt(node, "inbound-per-user-per-window", 8),
                positiveInt(node, "outbound-workers-per-platform", 4),
                positiveDouble(node, "outbound-global-per-second", 24.0),
                positiveInt(node, "outbound-per-chat-per-minute", 18),
                duration(node, "send-timeout", Duration.ofSeconds(15)));
    }

    private AppConfig.WebApp parseWebApp(Map<String, Object> node) {
        return new AppConfig.WebApp(
                bool(node, "enabled", false),
                str(node, "bind-host", "127.0.0.1"),
                positiveInt(node, "port", 8080),
                str(node, "public-url", null),
                duration(node, "init-data-max-age", Duration.ofHours(24)),
                str(node, "metrics-token", null));
    }

    private AppConfig.Locale parseLocale(Map<String, Object> node) {

        Set<String> supported = new LinkedHashSet<>(stringList(get(node, "supported"), List.of("ru", "en")));
        String def = str(node, "default", "ru");

        if (!supported.contains(def)) {
            supported.add(def);
        }

        return new AppConfig.Locale(def, supported, parseDisplayNames(node, supported));

    }

    /**
     * {@code locale.display-names}: language code -> button caption. A code that is
     * not listed in {@code locale.supported} is a typo and fails the startup;
     * a supported language without a caption falls back to the code itself.
     */
    private Map<String, String> parseDisplayNames(Map<String, Object> node, Set<String> supported) {

        Object raw = get(node, "display-names");

        if (raw == null) {
            return Map.of();
        }

        if (!(raw instanceof Map<?, ?> map)) {
            throw new ConfigException("locale.display-names", "expected a map of language code -> name");
        }

        Map<String, String> names = new LinkedHashMap<>();

        for (Map.Entry<?, ?> entry : map.entrySet()) {

            String code = String.valueOf(entry.getKey());

            if (!supported.contains(code)) {
                throw new ConfigException("locale.display-names." + code,
                        "language is not listed in locale.supported");
            }

            if (!(entry.getValue() instanceof String name) || name.isBlank()) {
                throw new ConfigException("locale.display-names." + code, "expected a non-empty string");
            }

            names.put(code, name);

        }

        return names;

    }

    @SuppressWarnings("unchecked")
    private Map<Platform, AppConfig.PlatformSection> parsePlatforms(Map<String, Object> node) {

        Map<Platform, AppConfig.PlatformSection> result = new EnumMap<>(Platform.class);

        for (Platform platform : Platform.values()) {
            Object raw = node.get(platform.id());
            Map<String, Object> section = raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
            String proxy = str(section, "proxy", null);
            validateProxy("platforms." + platform.id() + ".proxy", proxy);
            result.put(platform, new AppConfig.PlatformSection(
                    bool(section, "enabled", false),
                    str(section, "token", null),
                    str(section, "id", null),
                    str(section, "secret", null),
                    proxy));
        }

        return result;

    }

    /**
     * Proxy format: {@code [http://|socks5://]host:port}, optionally with a login -
     * {@code user:pass@host:port} or the colon form proxy sellers print in their lists,
     * {@code host:port:user:pass} (a SOCKS5 login, RFC 1929). An error in the address is
     * visible at start, not at the moment of the first network request.
     *
     * <p>The accepted grammar mirrors {@code TelegramAdapter.parseProxy}, the parser that
     * turns this string into a live connection. The two must stay in step: a valid proxy
     * rejected here never reaches the adapter, and the bot dies on start with a misleading
     * "invalid port" instead of connecting.
     */
    private static void validateProxy(String key, @Nullable String proxy) {

        if (proxy == null || proxy.isBlank()) {
            return;
        }

        String value = proxy.trim();
        String lower = value.toLowerCase(java.util.Locale.ROOT);

        for (String prefix : PROXY_PREFIXES) {
            if (lower.startsWith(prefix)) {
                value = value.substring(prefix.length());
                break;
            }
        }

        int at = value.lastIndexOf('@');

        if (at >= 0) {
            value = value.substring(at + 1);
        }

        List<String> parts = splitOutsideBrackets(value);

        if (parts.size() != 2 && parts.size() != 4) {
            throw new ConfigException(key, "expected [http://|socks5://]host:port[:user:pass], got '"
                    + proxy + "'");
        }

        if (parts.get(0).isEmpty()) {
            throw new ConfigException(key, "no host before the port in '" + proxy + "'");
        }

        try {
            int port = Integer.parseInt(parts.get(1));
            if (port < 1 || port > 65_535) {
                throw new NumberFormatException("port out of range");
            }
        } catch (NumberFormatException e) {
            throw new ConfigException(key, "invalid port in '" + proxy + "'");
        }

    }

    /**
     * Splits on ':' outside a bracketed IPv6 literal, so {@code [::1]:1080} stays a host
     * and a port. Mirrors the split in {@code TelegramAdapter.parseProxy}.
     */
    private static List<String> splitOutsideBrackets(String value) {

        List<String> parts = new java.util.ArrayList<>();

        int depth = 0;
        int start = 0;

        for (int i = 0; i < value.length(); i++) {

            char c = value.charAt(i);

            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == ':' && depth == 0) {
                parts.add(value.substring(start, i).trim());
                start = i + 1;
            }

        }

        parts.add(value.substring(start).trim());
        return parts;

    }

    private static LeapDayPolicy parseLeapPolicy(String value) {
        try {
            return LeapDayPolicy.parse(value);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("scheduler.leap-day-policy", e.getMessage());
        }
    }

    /**
     * Strict schema: a typo in a config key is visible immediately at start,
     * instead of turning into a silently ignored parameter.
     */
    @SuppressWarnings("unchecked")
    private void validateKeys(Map<String, Object> root) {

        for (String key : root.keySet()) {
            if (!ROOT_KEYS.contains(key)) {
                throw new ConfigException(key, "unknown top-level key; allowed: "
                        + new java.util.TreeSet<>(ROOT_KEYS));
            }
        }

        SECTION_KEYS.forEach((section, allowed) -> {

            Object node = root.get(section);

            if (node instanceof Map<?, ?> map) {
                for (Object key : map.keySet()) {
                    if (!allowed.contains(String.valueOf(key))) {
                        throw new ConfigException(section + "." + key,
                                "unknown key; allowed: " + new java.util.TreeSet<>(allowed));
                    }
                }
            }

        });

        Object platforms = root.get("platforms");

        if (platforms instanceof Map<?, ?> platformMap) {

            for (Map.Entry<?, ?> entry : platformMap.entrySet()) {

                String platform = String.valueOf(entry.getKey());

                try {
                    eu.neydev.birthday.core.api.Platform.fromId(platform);
                } catch (IllegalArgumentException e) {
                    throw new ConfigException("platforms." + platform, "unknown platform");
                }

                if (entry.getValue() instanceof Map<?, ?> section) {
                    for (Object key : section.keySet()) {
                        if (!PLATFORM_KEYS.contains(String.valueOf(key))) {
                            throw new ConfigException("platforms." + platform + "." + key,
                                    "unknown key; allowed: " + new java.util.TreeSet<>(PLATFORM_KEYS));
                        }
                    }
                }
            }
        }

    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String key) {
        Object value = root.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Object get(Map<String, Object> node, String key) {
        return node.get(key);
    }

    private String str(Map<String, Object> node, String key, String def) {

        Object value = node.get(key);

        if (value == null) {
            return def;
        }

        return substitute(String.valueOf(value), key);

    }

    private String substitute(String value, String key) {

        Matcher matcher = ENV.matcher(value);
        StringBuilder out = new StringBuilder();

        while (matcher.find()) {

            String envValue = environment.apply(matcher.group(1));

            if (envValue == null && matcher.group(2) == null) {
                throw new ConfigException(key,
                        "environment variable '%s' is not set (secrets are not stored in YAML)"
                                .formatted(matcher.group(1)));
            }

            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    envValue != null ? envValue : matcher.group(2)));

        }

        matcher.appendTail(out);
        return out.toString();

    }

    private boolean bool(Map<String, Object> node, String key, boolean def) {

        Object value = node.get(key);

        if (value == null) {
            return def;
        }

        if (value instanceof Boolean b) {
            return b;
        }

        return Boolean.parseBoolean(String.valueOf(value));

    }

    private int positiveInt(Map<String, Object> node, String key, int def) {

        int value = intOf(node, key, def);

        if (value <= 0) {
            throw new ConfigException(key, "expected a positive number, got " + value);
        }

        return value;

    }

    private int nonNegativeInt(Map<String, Object> node, String key, int def) {

        int value = intOf(node, key, def);

        if (value < 0) {
            throw new ConfigException(key, "expected a non-negative number, got " + value);
        }

        return value;

    }

    private int intOf(Map<String, Object> node, String key, int def) {

        Object value = node.get(key);

        if (value == null) {
            return def;
        }

        if (value instanceof Number n) {
            return n.intValue();
        }

        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(key, "not a number: " + value);
        }

    }

    private double positiveDouble(Map<String, Object> node, String key, double def) {

        Object value = node.get(key);
        double parsed = value == null ? def : value instanceof Number n ? n.doubleValue()
                : Double.parseDouble(String.valueOf(value).trim());

        if (parsed <= 0) {
            throw new ConfigException(key, "expected a positive number, got " + parsed);
        }

        return parsed;

    }

    private Duration duration(Map<String, Object> node, String key, Duration def) {

        Object value = node.get(key);

        if (value == null) {
            return def;
        }

        String raw = String.valueOf(value).trim();

        try {

            if (raw.matches("\\d+")) {
                return Duration.ofSeconds(Long.parseLong(raw));
            }

            // Days belong to the date part of an ISO-8601 duration ("P400D"), so "400d"
            // cannot be reached by prefixing "PT" - it is converted explicitly.
            if (raw.matches("(?i)\\d+d")) {
                return Duration.ofDays(Long.parseLong(raw.substring(0, raw.length() - 1)));
            }

            return Duration.parse(raw.startsWith("PT") || raw.startsWith("P") ? raw : "PT" + raw);

        } catch (Exception e) {
            throw new ConfigException(key, "invalid duration: " + raw
                    + " (examples: 30, 30s, 5m, 24h, 400d, PT1M)");
        }

    }

    private <E extends Enum<E>> E enumOf(Map<String, Object> node, String key, Class<E> type, E def) {

        Object value = node.get(key);

        if (value == null) {
            return def;
        }

        try {
            return Enum.valueOf(type, String.valueOf(value).toUpperCase(java.util.Locale.ROOT)
                    .replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(key, "unknown value '" + value + "' for " + type.getSimpleName());
        }

    }

    private static List<String> stringList(@Nullable Object value, List<String> def) {

        if (value == null) {
            return def;
        }

        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim).toList();
        }

        return java.util.Arrays.stream(String.valueOf(value).split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();

    }

}
