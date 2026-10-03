package eu.neydev.birthday.plugin.bukkit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A retry storm must cost the host log one record and one summary, not one
 * record per attempt: the Telegram poller prints an ERROR line plus a raw
 * stack for every transient 502, and the backoff loop makes that a flood.
 */
class RepeatCoalescerTest {

    private final List<String> emitted = new ArrayList<>();
    private final BotProcess.RepeatCoalescer coalescer =
            new BotProcess.RepeatCoalescer((level, line) -> emitted.add(level + "|" + line));

    private static List<String> retryRecord(String millis) {
        return List.of(
                "04:12:24.608 ERROR BotSession - Error received from Telegram GetUpdates Request, retrying in "
                        + millis + " millis...",
                "-1: org.telegram.telegrambots.longpolling.exceptions.TelegramApiErrorResponseException",
                "\tat org.telegram.telegrambots.longpolling.BotSession.getUpdatesFromTelegram(BotSession.java:161)");
    }

    @Test
    void identicalRecordsCollapseIntoOnePlusASummary() {

        coalescer.accept(1, retryRecord("529"));
        coalescer.accept(1, retryRecord("972"));
        coalescer.accept(1, retryRecord("1681"));
        coalescer.drain();

        assertThat(emitted).hasSize(4);
        assertThat(emitted.get(0)).contains("retrying in 529");
        assertThat(emitted.get(3)).contains("repeated 2 more times");

    }

    @Test
    void aDifferentRecordBreaksTheStreakAndFlushesTheSummary() {

        coalescer.accept(1, retryRecord("529"));
        coalescer.accept(1, retryRecord("972"));
        coalescer.accept(1, List.of("04:12:30.000 INFO Bootstrap - Birthday Bot started"));
        coalescer.drain();

        // three lines of the first record, the summary, then the different record
        assertThat(emitted).hasSize(5);
        assertThat(emitted.get(3)).contains("repeated 1 more times");
        assertThat(emitted.get(4)).contains("Birthday Bot started");

    }

    @Test
    void debugRecordsNeverCoalesce() {

        coalescer.accept(-1, List.of("debug one"));
        coalescer.accept(-1, List.of("debug one"));
        coalescer.drain();

        assertThat(emitted).hasSize(2);

    }

    @Test
    void aLongStormStillSpeaksEveryTwentyFifthRepeat() {

        coalescer.accept(1, retryRecord("1"));
        for (int i = 0; i < 25; i++) {
            coalescer.accept(1, retryRecord(String.valueOf(i + 2)));
        }

        coalescer.drain();
        assertThat(emitted).anyMatch(line -> line.contains("identical record number 25"));

    }
}
