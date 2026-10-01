package eu.neydev.birthday.core.command;

import eu.neydev.birthday.core.api.OutboundMessage;
import eu.neydev.birthday.core.conversation.ConversationStore;
import eu.neydev.birthday.core.domain.BirthDate;
import eu.neydev.birthday.core.domain.DateParsers;
import eu.neydev.birthday.core.domain.Profile;
import eu.neydev.birthday.core.service.ProfileService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Input dialogs (date/time/zone): start, applying the answer, cancel.
 * Extracted from the router: state lives in {@link ConversationStore},
 * profile mutations - in {@link ProfileService}, here only the scenario.
 */
public record ConversationFlow(ConversationStore store, ProfileService profileService,
                               ReplyBuilder replies) {

    public List<OutboundMessage> beginDate(Profile profile, @Nullable String editMessageId) {
        return begin(profile, ConversationStore.State.AWAITING_BIRTH_DATE,
                "message.prompt.date", editMessageId);
    }

    public List<OutboundMessage> beginTime(Profile profile, @Nullable String editMessageId) {
        return begin(profile, ConversationStore.State.AWAITING_NOTIFY_TIME,
                "message.prompt.time", editMessageId);
    }

    public List<OutboundMessage> beginZone(Profile profile, @Nullable String editMessageId) {
        return begin(profile, ConversationStore.State.AWAITING_ZONE,
                "message.prompt.zone", editMessageId);
    }

    private List<OutboundMessage> begin(Profile profile, ConversationStore.State state,
                                        String promptKey, @Nullable String editMessageId) {
        store.begin(profile.user(), state);
        var keyboard = replies.back(profile.locale());

        if (editMessageId != null) {
            return List.of(new OutboundMessage.Edit(profile.user().platform(), profile.chatId(),
                    editMessageId, replies.rich(profile, promptKey, Map.of()), keyboard));
        }

        return List.of(replies.send(profile, promptKey, Map.of(), keyboard));

    }

    /** Free text within an active dialog. */
    public List<OutboundMessage> handleText(Profile profile, ConversationStore.State state, String text) {
        return switch (state) {
            case AWAITING_BIRTH_DATE -> applyDate(profile, text);
            case AWAITING_NOTIFY_TIME -> applyTime(profile, text);
            case AWAITING_ZONE -> applyZone(profile, text);
        };
    }

    public List<OutboundMessage> applyDate(Profile profile, String text) {

        DateParsers.BirthDateParse parsed = DateParsers.parseBirthDateInAnyLanguage(
                text, profile.locale(), profileService.localeResolver().supported(),
                profile.todayAt(replies.clock().instant()));

        if (!parsed.parsed()) {

            // "I cannot read this" and "I can read it, but 1889 is not a birth year"
            // are different sentences: the second one names the year and the window.
            if (parsed.yearOutOfRange() != null) {
                return List.of(replies.send(profile, "message.date.year_range",
                        Map.of("year", parsed.yearOutOfRange()), replies.none()));
            }

            return List.of(replies.send(profile, "message.date.invalid", Map.of(), replies.none()));

        }

        BirthDate date = parsed.date();
        store.clear(profile.user());
        Profile updated = profileService.setBirthDate(profile, date);

        return List.of(replies.send(updated, "message.date.set",
                replies.dateSetParams(updated, date), replies.mainMenu(updated)));

    }

    private List<OutboundMessage> applyTime(Profile profile, String text) {

        var time = DateParsers.parseTime(text);

        if (time.isEmpty()) {
            return List.of(replies.send(profile, "message.time.invalid", Map.of(), replies.none()));
        }

        store.clear(profile.user());
        Profile updated = profileService.setNotifyTime(profile, time.get());

        return List.of(replies.send(updated, "message.time.set",
                replies.timeSetParams(updated), replies.mainMenu(updated)));

    }

    private List<OutboundMessage> applyZone(Profile profile, String text) {

        var zone = DateParsers.parseZone(text);

        if (zone.isEmpty()) {
            return List.of(replies.send(profile, "message.zone.invalid", Map.of(), replies.none()));
        }

        store.clear(profile.user());
        Profile updated = profileService.setZone(profile, zone.get());

        return List.of(replies.send(updated, "message.zone.set",
                replies.zoneSetParams(updated), replies.mainMenu(updated)));

    }

    public void cancel(Profile profile) {
        store.clear(profile.user());
    }

    public Optional<ConversationStore.State> current(@NotNull eu.neydev.birthday.core.api.PlatformUser user) {
        return store.current(user);
    }

}
