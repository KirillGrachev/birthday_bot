package eu.neydev.birthday.core.webapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.api.PlatformUser;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.http.WebhookHandler;
import eu.neydev.birthday.core.domain.BirthdayMath;
import eu.neydev.birthday.core.domain.DateParsers;
import eu.neydev.birthday.core.domain.Profile;
import eu.neydev.birthday.core.i18n.MessageBundleHolder;
import eu.neydev.birthday.core.metrics.MetricsRegistry;
import eu.neydev.birthday.core.service.ProfileService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * The built-in mini app HTTP server: serves the SPA (one file from resources),
 * profile-API and /metrics. The bot ITSELF hosts the web app - a separate
 * a site is not needed; from the outside only TLS termination is required (a reverse proxy or
 * a tunnel, see README "Mini App without a site").
 *
 * <p>Authorization: the {@code X-Telegram-Init-Data} header is verified
 * {@link TelegramInitDataValidator} - no own session/token database.
 */
public final class WebAppServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WebAppServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_PROFILE_BODY_BYTES = 64 * 1024;

    private static final String[] WEBAPP_I18N_KEYS = {
            "webapp.title", "webapp.date", "webapp.time", "webapp.zone",
            "webapp.lang", "webapp.notify", "webapp.save", "webapp.saved",
            "webapp.days_left", "webapp.opensource", "webapp.star", "webapp.star_note",
            "webapp.delete", "webapp.delete_confirm", "webapp.preview",
            "webapp.api_error", "webapp.auth_error"};

    private final AppConfig.WebApp config;
    private final ProfileService profileService;
    private final MessageBundleHolder bundleHolder;
    private final MetricsRegistry metrics;
    private final AppConfig appConfig;
    private final Clock clock;
    private final @Nullable TelegramInitDataValidator validator;
    private final List<WebhookHandler> webhookHandlers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private HttpServer server;

    public WebAppServer(AppConfig appConfig,
                        ProfileService profileService,
                        MessageBundleHolder bundleHolder,
                        MetricsRegistry metrics,
                        Clock clock) {
        this.appConfig = appConfig;
        this.config = appConfig.webApp();
        this.profileService = profileService;
        this.bundleHolder = bundleHolder;
        this.metrics = metrics;
        this.clock = clock;
        String telegramToken = appConfig.platform(Platform.TELEGRAM).token();
        this.validator = telegramToken != null
                ? new TelegramInitDataValidator(telegramToken, config.initDataMaxAge())
                : null;
    }

    /** Platforms with an inbound webhook register a handler before the server starts. */
    public void registerWebhook(WebhookHandler handler) {
        webhookHandlers.add(handler);
    }

    public boolean hasWebhooks() {
        return !webhookHandlers.isEmpty();
    }

    public void start() throws IOException {

        if (config.bindsPublicly() && config.metricsTokenMissing()) {
            log.warn("Binding {} (not loopback) with an empty webapp.metrics-token: /metrics is"
                    + " readable by anyone who can reach port {}. Set METRICS_TOKEN, or keep the"
                    + " bind address local and expose it through a reverse proxy.",
                    config.bindHost(), config.port());
        }

        server = HttpServer.create(new InetSocketAddress(config.bindHost(), config.port()), 64);
        server.createContext("/", this::handleStatic);
        server.createContext("/healthz", this::handleHealth);
        server.createContext("/metrics", this::handleMetrics);
        server.createContext("/api/profile", this::handleProfile);
        server.createContext("/api/i18n", this::handleI18n);

        for (WebhookHandler handler : webhookHandlers) {
            server.createContext(handler.path(), exchange -> dispatchWebhook(handler, exchange));
        }

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        log.info("Mini App server listening on {}:{} (public-url={})",
                config.bindHost(), config.port(), config.publicUrl());

    }

    private void dispatchWebhook(WebhookHandler handler, HttpExchange exchange) throws IOException {

        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0));
            }
        });

        WebhookHandler.WebhookRequest request = new WebhookHandler.WebhookRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery(),
                headers, body);
        WebhookHandler.WebhookResponse response;

        try {
            response = handler.handle(request);
        } catch (RuntimeException e) {
            log.warn("Webhook {} processed with error: {}", handler.path(), e.getMessage());
            response = new WebhookHandler.WebhookResponse(500, "application/json", "{\"error\":\"internal\"}");
        }

        respond(exchange, response.status(), response.contentType(), response.body());

    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "text/plain", "ok");
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {

        if (!metricsAuthorized(exchange)) {
            respond(exchange, 401, "text/plain", "unauthorized\n");
            return;
        }

        respond(exchange, 200, "text/plain; version=0.0.4", metrics.prometheusText());

    }

    /**
     * An optional token for {@code /metrics}: the {@code Authorization: Bearer} header or
     * the {@code ?token=} query parameter, compared in constant time. Without a configured
     * token the endpoint stays open - the loopback bind is the default protection and
     * {@link #start()} warns loudly when it is not loopback. {@code /healthz} is never
     * protected: a container HEALTHCHECK has no secrets to present.
     */
    private boolean metricsAuthorized(HttpExchange exchange) {

        if (config.metricsTokenMissing()) {
            return true;
        }

        byte[] expected = config.metricsToken().getBytes(StandardCharsets.UTF_8);
        String header = exchange.getRequestHeaders().getFirst("Authorization");

        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return MessageDigest.isEqual(expected,
                    header.substring(7).trim().getBytes(StandardCharsets.UTF_8));
        }

        String fromQuery = queryParam(exchange.getRequestURI().getQuery(), "token");
        return fromQuery != null
                && MessageDigest.isEqual(expected, fromQuery.getBytes(StandardCharsets.UTF_8));

    }

    private static @Nullable String queryParam(@Nullable String query, String name) {

        if (query == null) {
            return null;
        }

        for (String pair : query.split("&")) {

            int eq = pair.indexOf('=');

            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }

        }

        return null;

    }

    private void handleStatic(HttpExchange exchange) throws IOException {

        String path = exchange.getRequestURI().getPath();

        if (!path.equals("/") && !path.equals("/index.html")) {
            respond(exchange, 404, "text/plain", "not found");
            return;
        }

        try (InputStream in = WebAppServer.class.getClassLoader().getResourceAsStream("webapp/index.html")) {

            if (in == null) {
                respond(exchange, 500, "text/plain", "webapp/index.html not found in resources");
                return;
            }

            byte[] body = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, body.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }

        }

    }

    /**
     * Public Mini App UI strings for the chosen language: the page is localized
     * instantly on language switch, before the profile is saved.
     */
    private void handleI18n(HttpExchange exchange) throws IOException {

        Map<String, String> query = new HashMap<>();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    query.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }

        String language = query.getOrDefault("lang", appConfig.locale().defaultLanguage());
        if (!appConfig.locale().supported().contains(language)) {
            language = appConfig.locale().defaultLanguage();
        }

        Map<String, String> i18n = new HashMap<>();
        for (String key : WEBAPP_I18N_KEYS) {
            i18n.put(key, bundleHolder.renderer().raw(key, Locale.forLanguageTag(language), Map.of()));
        }

        respond(exchange, 200, "application/json", MAPPER.writeValueAsString(i18n));

    }

    private void handleProfile(HttpExchange exchange) throws IOException {

        if (validator == null) {
            respond(exchange, 503, "application/json", "{\"error\":\"telegram platform disabled\"}");
            return;
        }

        String initData = exchange.getRequestHeaders().getFirst("X-Telegram-Init-Data");

        if (initData == null || initData.isBlank()) {
            respond(exchange, 401, "application/json", "{\"error\":\"missing X-Telegram-Init-Data\"}");
            return;
        }

        Optional<TelegramInitDataValidator.WebAppUser> user =
                validator.validate(initData, clock.instant());

        if (user.isEmpty()) {

            metrics.increment("webapp_auth_fail_total");
            respond(exchange, 401, "application/json", "{\"error\":\"invalid init data\"}");

            return;

        }

        PlatformUser platformUser = new PlatformUser(Platform.TELEGRAM,
                Long.toString(user.get().id()));
        String chatId = Long.toString(user.get().id());

        try {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                applyChanges(platformUser, chatId, exchange, user.get());
            } else {
                Profile profile = profileService.getOrCreate(platformUser, chatId,
                        user.get().languageCode());
                respond(exchange, 200, "application/json", profileJson(profile));
            }
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, "application/json",
                    "{\"error\":\"" + e.getMessage().replace('"', '\'') + "\"}");
        }

    }

    private void applyChanges(PlatformUser platformUser, String chatId,
                              HttpExchange exchange,
                              TelegramInitDataValidator.WebAppUser webUser) throws IOException {

        byte[] rawBody = exchange.getRequestBody().readNBytes(MAX_PROFILE_BODY_BYTES + 1);

        if (rawBody.length > MAX_PROFILE_BODY_BYTES) {
            respond(exchange, 413, "application/json", "{\"error\":\"too large\"}");
            return;
        }

        JsonNode body = MAPPER.readTree(rawBody);
        Profile profile = profileService.getOrCreate(platformUser, chatId, webUser.languageCode());

        if (body.path("delete").asBoolean(false)) {

            profileService.deleteData(platformUser);
            respond(exchange, 200, "application/json", "{\"deleted\":true}");

            return;

        }

        if (body.hasNonNull("date")) {

            String raw = body.get("date").asText();

            if (raw.isBlank()) {
                profile = profileService.setBirthDate(profile, null);
            } else {

                DateParsers.BirthDateParse parsed = DateParsers.parseBirthDateInAnyLanguage(
                        raw, profile.locale(), appConfig.locale().supported(),
                        profile.todayAt(clock.instant()));

                if (!parsed.parsed()) {

                    // The Mini App owes the same honesty as the chat: a readable date
                    // with an impossible year is not "invalid", it is out of range.
                    if (parsed.yearOutOfRange() != null) {
                        respond(exchange, 400, "application/json", "{\"error\":\"year out of range\"}");
                        return;
                    }
                    respond(exchange, 400, "application/json", "{\"error\":\"invalid date\"}");
                    return;
                }

                profile = profileService.setBirthDate(profile, parsed.date());

            }

        }

        if (body.hasNonNull("time")) {

            Optional<LocalTime> time = DateParsers.parseTime(body.get("time").asText());

            if (time.isEmpty()) {
                respond(exchange, 400, "application/json", "{\"error\":\"invalid time\"}");
                return;
            }

            profile = profileService.setNotifyTime(profile, time.get());

        }

        if (body.hasNonNull("zone")) {

            Optional<ZoneId> zone = DateParsers.parseZone(body.get("zone").asText());

            if (zone.isEmpty()) {
                respond(exchange, 400, "application/json", "{\"error\":\"invalid zone\"}");
                return;
            }

            profile = profileService.setZone(profile, zone.get());

        }

        if (body.hasNonNull("lang")) {
            String language = body.get("lang").asText();
            if (profileService.localeResolver().isSupported(language)) {
                profile = profileService.setLocale(profile, Locale.forLanguageTag(language));
            }
        }

        if (body.hasNonNull("notify")) {
            profile = profileService.setNotifyEnabled(profile, body.get("notify").asBoolean());
        }

        respond(exchange, 200, "application/json", profileJson(profile));

    }

    private String profileJson(Profile profile) throws IOException {

        ObjectNode node = MAPPER.createObjectNode();
        node.put("date", profile.birthDate() == null
                ? null
                : (profile.birthDate().hasYear()
                ? profile.birthDate().requireFullDate().toString()
                : profile.birthDate().toMonthDayString()));
        node.put("time", profile.notifyTime().toString());
        node.put("zone", profile.zone().getId());
        node.put("lang", profile.locale().getLanguage());
        node.put("notify", profile.notifyEnabled());

        if (profile.birthDate() != null) {
            node.put("daysUntil", BirthdayMath.daysUntil(profile.birthDate(),
                    profile.todayAt(clock.instant()),
                    appConfig.scheduler().leapDayPolicy()));
        }

        node.putArray("supported").addAll(appConfig.locale().supported().stream()
                .map(node::textNode).toList());
        Map<String, String> i18n = new HashMap<>();

        if (appConfig.community().hasGithub()) {
            node.put("github", appConfig.community().githubUrl());
        }

        for (String key : new String[]{"webapp.title", "webapp.date", "webapp.time", "webapp.zone",
                "webapp.lang", "webapp.notify", "webapp.save", "webapp.saved", "webapp.days_left",
                "webapp.opensource", "webapp.star", "webapp.star_note"}) {
            i18n.put(key, bundleHolder.renderer().raw(key, profile.locale(), Map.of()));
        }

        node.set("i18n", MAPPER.valueToTree(i18n));
        return MAPPER.writeValueAsString(node);

    }

    private void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "frame-ancestors https://web.telegram.org https://*.telegram.org");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(2);
        }
    }

    public @NotNull String listenAddress() {
        return config.bindHost() + ":" + config.port();
    }

}
