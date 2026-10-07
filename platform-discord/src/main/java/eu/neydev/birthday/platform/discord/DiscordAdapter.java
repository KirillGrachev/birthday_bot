package eu.neydev.birthday.platform.discord;

import eu.neydev.birthday.core.api.IncomingUpdate;
import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.api.OutboundMessage;
import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.api.PlatformAdapter;
import eu.neydev.birthday.core.api.PlatformException;
import eu.neydev.birthday.core.api.PlatformUser;
import eu.neydev.birthday.core.api.PlatformContext;
import eu.neydev.birthday.core.api.UpdateSink;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discord adapter (JDA 6). Privacy by default: the whole birthday dialog
 * goes into the user's DM (chatId = discord:&lt;userId&gt;), slash commands and buttons
 * in guilds get only an ephemeral ack reply.
 *
 * <p>Interactions require a reply within 3 seconds, so the ack (defer/reply)
 * performed IMMEDIATELY in the mapping, and the business reply comes as a separate DM
 * via the outbound dispatcher. Follow-up hooks (toasts) live in memory for up to 14 minutes.
 */
public final class DiscordAdapter implements PlatformAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiscordAdapter.class);

    private final String token;
    private final eu.neydev.birthday.core.i18n.MessageBundleHolder bundleHolder;
    private JDA jda;
    private final Map<String, HookEntry> hooks = new ConcurrentHashMap<>();

    private record HookEntry(InteractionHook hook, Instant createdAt) {
    }

    public DiscordAdapter(String token, eu.neydev.birthday.core.i18n.MessageBundleHolder bundleHolder) {
        this.token = token;
        this.bundleHolder = bundleHolder;
    }

    @Override
    public Platform platform() {
        return Platform.DISCORD;
    }

    @Override
    public boolean isEnabled() {
        return token != null && !token.isBlank();
    }

    @Override
    public void start(PlatformContext context) {

        try {

            jda = JDABuilder.createLight(token, GatewayIntent.DIRECT_MESSAGES)
                    .addEventListeners(new Listener(context.instrumentedSink(Platform.DISCORD)))
                    .build()
                    .awaitReady();
            registerCommands();
            Thread.ofVirtual().name("discord-hook-purge").start(this::purgeLoop);

            log.info("Discord: gateway connected, commands registered");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("Discord: start interrupted", e);
        } catch (Exception e) {
            throw new PlatformException("Discord: failed to start: " + e.getMessage(), e);
        }

    }

    /**
     * Slash commands are registered from {@link eu.neydev.birthday.core.command.CommandCatalog} -
     * a single source of truth for names/options; descriptions are localized from the i18n bundle.
     */
    private void registerCommands() {

        List<net.dv8tion.jda.api.interactions.commands.build.CommandData> commands = new ArrayList<>();

        for (eu.neydev.birthday.core.command.CommandCatalog.Spec spec
                : eu.neydev.birthday.core.command.CommandCatalog.all()) {

            String description = bundleHolder.renderer()
                    .raw(spec.descriptionKey(), java.util.Locale.ENGLISH, Map.of());
            var command = Commands.slash(spec.name(), truncate(description, 100));

            for (String option : spec.options()) {
                command.addOption(net.dv8tion.jda.api.interactions.commands.OptionType.STRING,
                        option, option, false);
            }

            Map<net.dv8tion.jda.api.interactions.DiscordLocale, String> localizations = new java.util.HashMap<>();

            for (String language : bundleHolder.bundle().languages()) {
                localizations.put(net.dv8tion.jda.api.interactions.DiscordLocale.from(language),
                        truncate(bundleHolder.renderer().raw(spec.descriptionKey(),
                                java.util.Locale.forLanguageTag(language), Map.of()), 100));
            }

            command.setDescriptionLocalizations(localizations);
            commands.add(command);

        }

        jda.updateCommands().addCommands(commands).queue();

    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    @Override
    public void stop() {
        if (jda != null) {
            jda.shutdown();
        }
    }

    @Override
    public void execute(OutboundMessage message) {

        try {

            switch (message) {

                case OutboundMessage.Send send -> {
                    PrivateChannel channel = openDm(send.chatId());
                    channel.sendMessage(DiscordMarkdownRenderer.render(send.text()))
                            .setComponents(components(send.keyboard()))
                            .complete();
                }

                case OutboundMessage.Edit edit -> {
                    PrivateChannel channel = openDm(edit.chatId());
                    channel.editMessageById(edit.messageId(),
                                    DiscordMarkdownRenderer.render(edit.text()))
                            .setComponents(components(edit.keyboard()))
                            .complete();
                }

                case OutboundMessage.Delete delete -> openDm(delete.chatId())
                        .deleteMessageById(delete.messageId()).complete();
                case OutboundMessage.AnswerCallback answer -> {
                    HookEntry entry = hooks.get(answer.interactionId());
                    if (entry != null && !answer.text().isEmpty()) {
                        entry.hook().sendMessage(answer.text()).setEphemeral(true).queue();
                    }
                }

            }

        } catch (ErrorResponseException e) {
            throw classify(e);
        }

    }

    /**
     * JDA checks components before a request leaves the process, so a keyboard it cannot
     * build surfaces as an argument failure instead of an error response. Retrying it
     * cannot help: the payload is ours, the chat is healthy.
     */
    private static List<ActionRow> components(InlineKeyboard keyboard) {

        try {
            return DiscordKeyboardMapper.components(keyboard);
        } catch (IllegalArgumentException e) {
            throw new PlatformException.InvalidMessageException("Discord: " + e.getMessage());
        }

    }

    private PrivateChannel openDm(String chatId) {
        return jda.openPrivateChannelById(chatId).complete();
    }

    private RuntimeException classify(ErrorResponseException e) {

        int code = e.getErrorResponse().getCode();

        if (code == 50035) {
            // "Invalid form body": the payload, not the chat. No retry can fix it.
            return new PlatformException.InvalidMessageException("Discord 50035: " + e.getMessage());
        }

        if (code == 50007 || code == 40003 || code == 10001) {
            return new PlatformException.PermanentDeliveryException("Discord: " + code, e);
        }

        if (code == 429) {
            return new PlatformException.RateLimitedException("Discord 429", 1_000);
        }

        return new PlatformException("Discord: " + e.getMessage(), e);

    }

    private void purgeLoop() {

        while (jda != null && jda.getStatus() == JDA.Status.CONNECTED) {

            hooks.entrySet().removeIf(entry ->
                    entry.getValue().createdAt().isBefore(Instant.now().minusSeconds(14 * 60)));

            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

        }

    }

    private final class Listener extends ListenerAdapter {

        private final UpdateSink sink;

        private Listener(UpdateSink sink) {
            this.sink = sink;
        }

        @Override
        public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {

            String name = event.getName();
            Locale locale = event.getUserLocale().toLocale();
            // The pointer is copy like any other: the bundle speaks it in the
            // user's language, falling back to the configured default.
            event.reply(bundleHolder.renderer()
                            .raw("message.pointer.dm", locale, Map.of()))
                    .setEphemeral(true).queue();

            String text = switch (name) {

                case "date" -> optionText(event, "date")
                        .<String>map(value -> {

                            sink.accept(new IncomingUpdate.FormSubmit(user(event.getUser().getId()),
                                    chatId(event.getUser().getId()),
                                    eu.neydev.birthday.core.command.Actions.FORM_SET_DATE,
                                    Map.of(eu.neydev.birthday.core.command.Actions.FIELD_DATE, value),
                                    locale.getLanguage()));

                            return null;

                        })
                        .orElse("/setdate");
                case "time" -> optionText(event, "time").map(value -> "/time " + value).orElse("/time");
                case "zone" -> optionText(event, "zone").map(value -> "/zone " + value).orElse("/zone");
                default -> "/" + name;

            };

            if (text != null) {
                sink.accept(new IncomingUpdate.TextMessage(user(event.getUser().getId()),
                        chatId(event.getUser().getId()), text, locale.getLanguage()));
            }

        }

        @Override
        public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {

            event.deferEdit().queue();
            hooks.put(event.getId(), new HookEntry(event.getHook(), Instant.now()));
            sink.accept(new IncomingUpdate.Callback(
                    user(event.getUser().getId()),
                    chatId(event.getUser().getId()),
                    event.getComponentId(),
                    event.getMessageId(),
                    event.getId(),
                    event.getUserLocale().toLocale().getLanguage()));

        }

        @Override
        public void onGenericMessage(@NotNull net.dv8tion.jda.api.events.message.GenericMessageEvent event) {

            if (!(event instanceof net.dv8tion.jda.api.events.message.MessageReceivedEvent received)) {
                return;
            }

            if (!received.getChannelType().isThread() && received.getMessage().getAuthor().isBot()) {
                return;
            }

            if (received.getChannelType() != net.dv8tion.jda.api.entities.channel.ChannelType.PRIVATE) {
                return;
            }

            String content = received.getMessage().getContentRaw();

            if (content.isBlank() || content.startsWith("/")) {
                return;
            }

            sink.accept(new IncomingUpdate.TextMessage(
                    user(received.getAuthor().getId()),
                    chatId(received.getAuthor().getId()),
                    content,
                    null));

        }

        private java.util.Optional<String> optionText(SlashCommandInteractionEvent event, String name) {
            var option = event.getOption(name);
            return option == null ? java.util.Optional.empty()
                    : java.util.Optional.of(option.getAsString());
        }

        private PlatformUser user(String id) {
            return new PlatformUser(Platform.DISCORD, id);
        }

        private String chatId(String userId) {
            return userId;
        }

    }

    public JDA jda() {
        return jda;
    }

}
