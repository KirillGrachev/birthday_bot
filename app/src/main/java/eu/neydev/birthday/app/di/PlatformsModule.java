package eu.neydev.birthday.app.di;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.api.PlatformAdapter;
import eu.neydev.birthday.core.config.AppConfig;
import eu.neydev.birthday.platform.discord.DiscordAdapter;
import eu.neydev.birthday.platform.slack.SlackAdapter;
import eu.neydev.birthday.platform.telegram.TelegramAdapter;
import eu.neydev.birthday.platform.viber.ViberAdapter;
import eu.neydev.birthday.platform.vk.VkAdapter;
import eu.neydev.birthday.platform.whatsapp.WhatsAppAdapter;

/**
 * Platform DI module: each active platform provides one {@link PlatformAdapter}
 * into the Multibinder. Inactive ones are simply not installed - the graph stays valid,
 * the bot starts degraded. A new platform = its own module + config section.
 */
public final class PlatformsModule extends AbstractModule {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PlatformsModule.class);

    private final AppConfig config;
    private final eu.neydev.birthday.core.i18n.MessageBundleHolder bundleHolder;

    public PlatformsModule(AppConfig config,
                           eu.neydev.birthday.core.i18n.MessageBundleHolder bundleHolder) {
        this.config = config;
        this.bundleHolder = bundleHolder;
    }

    @Override
    protected void configure() {

        Multibinder<PlatformAdapter> binder = Multibinder.newSetBinder(binder(), PlatformAdapter.class);
        String publicUrl = config.webApp().enabled() ? config.webApp().publicUrl() : null;

        section(Platform.TELEGRAM).ifPresent(section ->
                binder.addBinding().toInstance(new TelegramAdapter(
                        section.token(), publicUrl, "Birthday", null, section.proxy())));
        section(Platform.VK).ifPresent(section ->
                binder.addBinding().toInstance(new VkAdapter(section.token(), section.extraId())));
        section(Platform.DISCORD).ifPresent(section ->
                binder.addBinding().toInstance(new DiscordAdapter(section.token(), bundleHolder)));
        section(Platform.SLACK).ifPresent(section ->
                binder.addBinding().toInstance(new SlackAdapter(section.token(), section.extraId())));
        section(Platform.WHATSAPP).ifPresent(section ->
                binder.addBinding().toInstance(new WhatsAppAdapter(
                        section.token(), section.extraId(), section.secret())));
        section(Platform.VIBER).ifPresent(section ->
                binder.addBinding().toInstance(new ViberAdapter(
                        section.token(), publicUrl, section.secret())));

    }

    /**
     * A platform is active when enabled, has a token and meets the platform
     * requirements (id, webhook infrastructure, secrets). Otherwise - degradation with
     * a warning, not a bot crash.
     */
    private java.util.Optional<AppConfig.PlatformSection> section(Platform platform) {

        AppConfig.PlatformSection section = config.platform(platform);

        if (!section.isActive()) {
            return java.util.Optional.empty();
        }

        boolean webhookReady = config.webApp().enabled() && config.webApp().publicUrl() != null;
        boolean extraOk = switch (platform) {

            case VK, SLACK -> section.extraId() != null && !section.extraId().isBlank();
            case WHATSAPP -> webhookReady && section.extraId() != null
                    && !section.extraId().isBlank()
                    && (hasSecret(section) || section.extraId().split(":").length > 2);
            case VIBER -> webhookReady && hasSecret(section);
            default -> true;

        };

        if (!extraOk) {
            log.warn("Platform {} is enabled but requirements are not met (id/webhook/secret) - "
                    + "skipped", platform.id());
        }

        return extraOk ? java.util.Optional.of(section) : java.util.Optional.empty();

    }

    private static boolean hasSecret(AppConfig.PlatformSection section) {
        return section.secret() != null && !section.secret().isBlank();
    }

}
