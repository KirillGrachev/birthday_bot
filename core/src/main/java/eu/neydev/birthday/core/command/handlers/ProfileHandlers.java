package eu.neydev.birthday.core.command.handlers;

import eu.neydev.birthday.core.api.IncomingUpdate;
import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.api.OutboundMessage;
import eu.neydev.birthday.core.command.Actions;
import eu.neydev.birthday.core.command.CommandHandler;
import eu.neydev.birthday.core.command.ConversationFlow;
import eu.neydev.birthday.core.command.Interaction;
import eu.neydev.birthday.core.command.ReplyBuilder;
import eu.neydev.birthday.core.domain.DateParsers;
import eu.neydev.birthday.core.domain.Profile;
import eu.neydev.birthday.core.service.ProfileService;

import java.time.ZoneId;
import java.util.Optional;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Profile mutations and info: date, subscription, day counters, settings, language, cancel. */
public record ProfileHandlers(ProfileService profileService, ConversationFlow flow,
                              ReplyBuilder replies) {

    public CommandHandler beginDate() {
        return interaction -> flow.beginDate(interaction.profile(), interaction.editMessageId());
    }

    public CommandHandler toggleNotify() {
        return interaction -> {

            Profile profile = interaction.profile();
            boolean nowEnabled = !profile.notifyEnabled();

            Profile updated = profileService.setNotifyEnabled(profile, nowEnabled);
            String key = nowEnabled ? "message.notify.on" : "message.notify.off";

            if (interaction.isCallback()) {
                return List.of(replies.edit((IncomingUpdate.Callback) interaction.update(),
                        updated, key, Map.of(), replies.mainMenu(updated)));
            }

            return List.of(replies.send(updated, key, Map.of(), replies.mainMenu(updated)));

        };
    }

    public CommandHandler daysTo() {
        return interaction -> answer(interaction, replies.daysToKey(interaction.profile()),
                replies.daysToParams(interaction.profile()));
    }

    public CommandHandler daysSince() {
        return interaction -> {

            // Two counting modes, switched by the toggle on the answer itself:
            // since the birth date (full age in days) and since the last anniversary.
            boolean sinceLast = Actions.DAYS_SINCE_LAST.equals(interaction.actionId());
            Profile profile = interaction.profile();

            return answer(interaction, replies.daysSinceKey(profile, sinceLast),
                    replies.daysSinceParams(profile, sinceLast),
                    replies.daysSinceToggle(profile.locale(), sinceLast));

        };
    }

    public CommandHandler settings() {
        return interaction -> {

            Profile profile = interaction.profile();

            if (interaction.isCallback()) {
                return List.of(replies.edit((IncomingUpdate.Callback) interaction.update(), profile,
                        "message.settings.view", replies.settingsParams(profile),
                        replies.settings(profile.locale())));
            }

            return List.of(replies.send(profile, "message.settings.view",
                    replies.settingsParams(profile), replies.settings(profile.locale())));

        };
    }

    public CommandHandler beginTime() {
        return interaction -> flow.beginTime(interaction.profile(), interaction.editMessageId());
    }

    /** The free-text zone prompt: the way out of the picker for zones outside the list. */
    public CommandHandler beginZone() {
        return interaction -> flow.beginZone(interaction.profile(), interaction.editMessageId());
    }

    public CommandHandler zoneMenu() {
        return interaction -> zonePicker(interaction, replies.zonePage(interaction.profile()));
    }

    /** A page button of the zone picker: same screen, another page of zones. */
    public CommandHandler zonePage(int page) {
        return interaction -> zonePicker(interaction, page);
    }

    private List<OutboundMessage> zonePicker(Interaction interaction, int page) {

        Profile profile = interaction.profile();

        if (interaction.isCallback()) {
            return List.of(replies.edit((IncomingUpdate.Callback) interaction.update(), profile,
                    "message.zone.choose", Map.of(),
                    replies.zones(profile, page)));
        }

        return List.of(replies.send(profile, "message.zone.choose", Map.of(),
                replies.zones(profile, page)));

    }

    /** A zone button: apply it the way the typed prompt would. */
    public CommandHandler zoneApply(String zoneRaw) {

        return interaction -> {

            Profile profile = interaction.profile();
            Optional<ZoneId> zone = DateParsers.parseZone(zoneRaw);

            if (zone.isEmpty()) {

                int page = replies.zonePage(profile, zoneRaw);

                if (interaction.isCallback()) {
                    return List.of(replies.edit((IncomingUpdate.Callback) interaction.update(), profile,
                            "message.zone.invalid", Map.of(),
                            replies.zones(profile, page)));
                }

                return List.of(replies.send(profile, "message.zone.invalid", Map.of(),
                        replies.zones(profile, page)));

            }

            Profile updated = profileService.setZone(profile, zone.get());

            if (interaction.isCallback()) {
                return List.of(replies.edit((IncomingUpdate.Callback) interaction.update(), updated,
                        "message.zone.set", replies.zoneSetParams(updated), replies.mainMenu(updated)));
            }

            return List.of(replies.send(updated, "message.zone.set", replies.zoneSetParams(updated),
                    replies.mainMenu(updated)));

        };

    }

    public CommandHandler langMenu() {
        return interaction -> langPicker(interaction, replies.languagePage(interaction.profile()));
    }

    /** A picker page button: same screen, another page of languages. */
    public CommandHandler langPage(int page) {
        return interaction -> langPicker(interaction, page);
    }

    private List<OutboundMessage> langPicker(Interaction interaction, int page) {

        Profile profile = interaction.profile();

        // From the settings menu this is a callback: turn the settings screen into the
        // picker instead of stacking one more message in the chat.
        if (interaction.isCallback()) {
            return List.of(replies.edit((IncomingUpdate.Callback) interaction.update(), profile,
                    "message.lang.choose", Map.of(), replies.languages(profile, page)));
        }

        return List.of(replies.send(profile, "message.lang.choose", Map.of(),
                replies.languages(profile, page)));

    }

    public CommandHandler langApply(String language) {

        return interaction -> {

            Profile profile = interaction.profile();

            if (!profileService.localeResolver().isSupported(language)) {
                return List.of();
            }

            Profile updated = profileService.setLocale(profile, Locale.forLanguageTag(language));
            List<OutboundMessage> repliesOut = new java.util.ArrayList<>(2);

            if (interaction.isCallback()) {
                IncomingUpdate.Callback callback = (IncomingUpdate.Callback) interaction.update();
                repliesOut.add(replies.toast(callback, updated, "message.lang.set",
                        Map.of("lang", replies.languageName(Locale.forLanguageTag(language)))));
                repliesOut.add(replies.edit(callback, updated, "message.settings.view",
                        replies.settingsParams(updated), replies.settings(updated.locale())));
            } else {
                repliesOut.add(replies.send(updated, "message.lang.set",
                        Map.of("lang", replies.languageName(Locale.forLanguageTag(language))),
                        replies.mainMenu(updated)));
            }

            return repliesOut;

        };
    }

    public CommandHandler deleteConfirm() {
        return interaction -> {

            Profile profile = interaction.profile();

            if (interaction.isCallback()) {
                return List.of(replies.edit((IncomingUpdate.Callback) interaction.update(), profile,
                        "message.delete.confirm", Map.of(),
                        confirmKeyboard(profile)));
            }

            return List.of(replies.send(profile, "message.delete.confirm", Map.of(),
                    confirmKeyboard(profile)));

        };
    }

    public CommandHandler deleteYes() {
        return interaction -> {

            Profile profile = interaction.profile();
            flow.cancel(profile);
            profileService.deleteData(profile.user());

            return List.of(replies.send(profile, "message.delete.done", Map.of(), replies.none()));

        };
    }

    public CommandHandler deleteNo() {
        return interaction -> {

            Profile profile = interaction.profile();

            if (interaction.isCallback()) {
                return List.of(replies.edit((IncomingUpdate.Callback) interaction.update(), profile,
                        "message.settings.view", replies.settingsParams(profile),
                        replies.settings(profile.locale())));
            }

            return List.of(replies.send(profile, "message.settings.view",
                    replies.settingsParams(profile), replies.settings(profile.locale())));

        };
    }

    public CommandHandler cancel() {
        return interaction -> {

            flow.cancel(interaction.profile());
            Profile profile = interaction.profile();

            if (interaction.isCallback()) {
                return List.of(replies.edit((IncomingUpdate.Callback) interaction.update(), profile,
                        "message.cancelled", Map.of(), replies.mainMenu(profile)));
            }

            return List.of(replies.send(profile, "message.cancelled", Map.of(), replies.mainMenu(profile)));

        };
    }

    private eu.neydev.birthday.core.api.InlineKeyboard confirmKeyboard(Profile profile) {
        return replies.confirmDelete(profile.locale());
    }

    private List<OutboundMessage> answer(Interaction interaction, String key,
                                         Map<String, Object> params) {
        return answer(interaction, key, params, replies.back(interaction.profile().locale()));
    }

    private List<OutboundMessage> answer(Interaction interaction, String key,
                                         Map<String, Object> params, InlineKeyboard keyboard) {

        Profile profile = interaction.profile();

        if (interaction.isCallback()) {
            return List.of(replies.edit((IncomingUpdate.Callback) interaction.update(),
                    profile, key, params, keyboard));
        }

        return List.of(replies.send(profile, key, params, keyboard));

    }

}
