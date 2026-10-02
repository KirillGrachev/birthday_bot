package eu.neydev.birthday.core.command;

import eu.neydev.birthday.core.api.IncomingUpdate;
import eu.neydev.birthday.core.api.OutboundMessage;
import eu.neydev.birthday.core.command.handlers.AboutHandler;
import eu.neydev.birthday.core.command.handlers.AdminHandlers;
import eu.neydev.birthday.core.command.handlers.MenuHandlers;
import eu.neydev.birthday.core.command.handlers.ProfileHandlers;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.core.domain.Profile;
import eu.neydev.birthday.core.metrics.MetricsRegistry;
import eu.neydev.birthday.core.pipeline.OutboundDispatcher;
import eu.neydev.birthday.core.pipeline.UserThrottle;
import eu.neydev.birthday.core.service.ProfileService;
import eu.neydev.birthday.core.util.TraceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A thin router: tracing, anti-flood, handler lookup in the tables
 * of commands/actions (names - from {@link CommandCatalog}) and handing replies to the outbound
 * the dispatcher. Business logic lives in the handler classes and {@link ConversationFlow}.
 */
public final class CommandRouter implements Consumer<IncomingUpdate> {

    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);
    private static final long THROTTLE_WINDOW_MILLIS = 10_000;

    private final ProfileService profileService;
    private final ConversationFlow flow;
    private final ReplyBuilder replies;
    private final ProfileHandlers profileHandlers;
    private final OutboundDispatcher dispatcher;
    private final MetricsRegistry metrics;
    private final UserThrottle throttle;

    private final Map<String, CommandHandler> commands = new HashMap<>();
    private final Map<String, CommandHandler> actions = new HashMap<>();

    public CommandRouter(ProfileService profileService,
                         ConversationFlow flow,
                         ReplyBuilder replies,
                         MenuHandlers menuHandlers,
                         ProfileHandlers profileHandlers,
                         AboutHandler aboutHandler,
                         AdminHandlers adminHandlers,
                         OutboundDispatcher dispatcher,
                         MetricsRegistry metrics,
                         AppConfig config) {

        this.profileService = profileService;
        this.flow = flow;
        this.replies = replies;
        this.profileHandlers = profileHandlers;
        this.dispatcher = dispatcher;
        this.metrics = metrics;
        this.throttle = new UserThrottle(
                config.pipeline().inboundPerUserPerWindow(), THROTTLE_WINDOW_MILLIS);

        wireCommands(menuHandlers, profileHandlers, aboutHandler, adminHandlers);
        wireActions(menuHandlers, profileHandlers, aboutHandler, adminHandlers);

    }

    /**
     * The command table is built from the catalog: names and aliases in one place,
     * here it is only the binding to handlers.
     */
    private void wireCommands(MenuHandlers menu, ProfileHandlers profile,
                              AboutHandler about, AdminHandlers admin) {
        bind(CommandCatalog.START, menu.start());
        bind(CommandCatalog.HELP, menu.help());
        bind(CommandCatalog.DATE, profile.beginDate());
        bind(CommandCatalog.NOTIFY, profile.toggleNotify());
        bind(CommandCatalog.DAYS_TO, profile.daysTo());
        bind(CommandCatalog.DAYS_SINCE, profile.daysSince());
        bind(CommandCatalog.SETTINGS, profile.settings());
        bind(CommandCatalog.LANG, profile.langMenu());
        bind(CommandCatalog.TIME, profile.beginTime());
        bind(CommandCatalog.ZONE, profile.beginZone());
        bind(CommandCatalog.ABOUT, about);
        bind(CommandCatalog.DELETE, profile.deleteConfirm());
        bind(CommandCatalog.CANCEL, profile.cancel());
        bind(CommandCatalog.RELOAD, admin.reload());
    }

    private void wireActions(MenuHandlers menu, ProfileHandlers profile,
                             AboutHandler about, AdminHandlers admin) {
        actions.put(Actions.SET_DATE, profile.beginDate());
        actions.put(Actions.TOGGLE_NOTIFY, profile.toggleNotify());
        actions.put(Actions.DAYS_TO, profile.daysTo());
        actions.put(Actions.DAYS_SINCE, profile.daysSince());
        actions.put(Actions.DAYS_SINCE_LAST, profile.daysSince());
        actions.put(Actions.SETTINGS, profile.settings());
        actions.put(Actions.BACK, menu.back());
        actions.put(Actions.MENU_OPEN, menu.openMenu());
        actions.put(Actions.SET_TIME, profile.beginTime());
        actions.put(Actions.SET_ZONE, profile.zoneMenu());
        actions.put(Actions.ZONE_MANUAL, profile.beginZone());
        actions.put(Actions.LANG_MENU, profile.langMenu());
        actions.put(Actions.CANCEL, profile.cancel());
        actions.put(Actions.ABOUT, about);
        actions.put(Actions.DELETE, profile.deleteConfirm());
        actions.put(Actions.DELETE_YES, profile.deleteYes());
        actions.put(Actions.DELETE_NO, profile.deleteNo());
        actions.put(Actions.RELOAD, admin.reload());
        actions.put(Actions.NOOP, interaction -> List.of());
    }

    private void bind(CommandCatalog.Spec spec, CommandHandler handler) {
        commands.put("/" + spec.name(), handler);
        spec.aliases().forEach(alias -> commands.put("/" + alias, handler));
    }

    @Override
    public void accept(IncomingUpdate update) {

        TraceId.put(update.traceId());

        try {

            Profile profile = profileService.getOrCreate(
                    update.user(), update.chatId(), localeHint(update));

            if (throttled(update, profile)) {
                return;
            }

            for (OutboundMessage reply : route(update, profile)) {
                dispatcher.submit(reply);
            }

        } finally {
            TraceId.clear();
        }

    }

    /**
     * Anti-flood: protects the core from command spam. Silent throttling does not reply
     * at all, so anti-flood itself does not become a flood.
     */
    private boolean throttled(IncomingUpdate update, Profile profile) {

        UserThrottle.Verdict verdict = throttle.tryAcquire(update.user());

        if (verdict == UserThrottle.Verdict.PASS) {
            return false;
        }

        metrics.increment("inbound_throttled_total", "platform", update.user().platform().id());

        if (verdict == UserThrottle.Verdict.THROTTLED_REPLY) {
            dispatcher.submit(replies.send(profile, "message.flood", Map.of(), replies.none()));
        }

        return true;

    }

    private List<OutboundMessage> route(IncomingUpdate update, Profile profile) {

        Interaction interaction = new Interaction(update, profile);

        return switch (update) {
            case IncomingUpdate.TextMessage message -> routeText(interaction, message);
            case IncomingUpdate.Callback callback -> routeCallback(interaction, callback);
            case IncomingUpdate.FormSubmit form -> routeForm(interaction, form);
        };

    }

    private List<OutboundMessage> routeText(Interaction interaction, IncomingUpdate.TextMessage message) {

        metrics.increment("inbound_text_total", "platform", interaction.profile().user().platform().id());
        String text = message.text().trim();

        if (text.startsWith("/")) {
            return commandHandler(text).handle(interaction);
        }

        return flow.current(interaction.profile().user())
                .map(state -> flow.handleText(interaction.profile(), state, text))
                .orElseGet(() -> commands.get("/help").handle(interaction));

    }

    private CommandHandler commandHandler(String text) {

        String raw = normalizeCommand(text);
        metrics.increment("commands_total", "command", raw);

        return CommandCatalog.resolve(raw.substring(1))
                .map(spec -> commands.get("/" + spec.name()))
                .orElse(commands.get("/help"));

    }

    private List<OutboundMessage> routeCallback(Interaction interaction, IncomingUpdate.Callback callback) {

        metrics.increment("callbacks_total", "action", callback.actionId(),
                "platform", interaction.profile().user().platform().id());

        List<OutboundMessage> out = new ArrayList<>(2);
        out.add(new OutboundMessage.AnswerCallback(interaction.profile().user().platform(),
                callback.chatId(), callback.interactionId(), "", false));

        if (Actions.isLang(callback.actionId())) {
            out.addAll(profileHandlers.langApply(Actions.langOf(callback.actionId())).handle(interaction));
            return out;
        }

        if (Actions.isZonePick(callback.actionId())) {
            out.addAll(profileHandlers.zoneApply(Actions.zoneOf(callback.actionId())).handle(interaction));
            return out;
        }

        if (Actions.isZonePage(callback.actionId())) {
            out.addAll(profileHandlers.zonePage(Actions.zonePageOf(callback.actionId()))
                    .handle(interaction));
            return out;
        }

        if (Actions.isLangPage(callback.actionId())) {
            out.addAll(profileHandlers.langPage(Actions.langPageOf(callback.actionId()))
                    .handle(interaction));
            return out;
        }

        CommandHandler handler = actions.get(callback.actionId());

        if (handler != null) {
            out.addAll(handler.handle(interaction));
        } else {
            log.debug("Unknown action '{}' from {}", callback.actionId(),
                    interaction.profile().user().key());
        }

        return out;

    }

    private List<OutboundMessage> routeForm(Interaction interaction, IncomingUpdate.FormSubmit form) {

        if (Actions.FORM_SET_DATE.equals(form.actionId())) {
            return flow.applyDate(interaction.profile(),
                    form.fields().getOrDefault(Actions.FIELD_DATE, ""));
        }

        return List.of();

    }

    private String normalizeCommand(String text) {

        String command = text.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int at = command.indexOf('@');

        return at > 0 ? command.substring(0, at) : command;

    }

    private static String localeHint(IncomingUpdate update) {
        return switch (update) {
            case IncomingUpdate.TextMessage message -> message.localeHint();
            case IncomingUpdate.Callback callback -> callback.localeHint();
            case IncomingUpdate.FormSubmit form -> form.localeHint();
        };
    }

}
