package eu.neydev.birthday.core.metrics;

import eu.neydev.birthday.core.FakeAdapter;
import eu.neydev.birthday.core.api.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The heartbeat is the contract with whoever supervises the process (a game server
 * plugin, a container HEALTHCHECK), so both halves of it are pinned here: the log line
 * content and the liveness file.
 */
class StatusHeartbeatTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    @TempDir
    Path tempDir;

    private StatusHeartbeat heartbeat(MetricsRegistry metrics, Path file, long profiles) {
        return new StatusHeartbeat(Duration.ofMinutes(5), file, metrics, new PlatformHealth(),
                List.of(new FakeAdapter(Platform.TELEGRAM)), () -> profiles,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void beatWritesTheLivenessFileWithTheCounters() throws Exception {

        MetricsRegistry metrics = new MetricsRegistry();
        metrics.increment("inbound_processed_total", "platform", "telegram");
        metrics.increment("inbound_processed_total", "platform", "telegram");
        metrics.increment("outbound_rejected_total", "platform", "telegram");
        Path file = tempDir.resolve("bot.liveness");

        heartbeat(metrics, file, 7).beat();

        String content = Files.readString(file);

        assertThat(content)
                .contains("uptime=0s")
                .contains("platforms=telegram:up")
                .contains("profiles=7")
                .contains("inbound=2")
                .contains("outbound_rejected=1");

    }

    @Test
    void summaryNeverCarriesBracketsOrNewlines() {

        String summary = heartbeat(new MetricsRegistry(), null, 0).summary();

        assertThat(summary)
                .doesNotContain("\n")
                .startsWith("uptime=");
        assertThat(StatusHeartbeat.MARKER + " " + summary).startsWith("[status] uptime=");

    }

    @Test
    void aMissingLivenessDirectoryIsCreated() throws Exception {

        Path nested = tempDir.resolve("data").resolve("bot.liveness");

        heartbeat(new MetricsRegistry(), nested, 0).beat();
        assertThat(nested).exists();

    }

    @Test
    void anUnwritableLivenessFileDoesNotThrow() throws Exception {

        Path readOnly = tempDir.resolve("ro.liveness");
        Files.writeString(readOnly, "x");
        readOnly.toFile().setWritable(false);

        // Must not throw: a deployment fact is reported once, not every interval.
        heartbeat(new MetricsRegistry(), readOnly, 0).beat();
        readOnly.toFile().setWritable(true);

    }

    @Test
    void compactRendersReadableDurations() {

        assertThat(StatusHeartbeat.compact(Duration.ofSeconds(45))).isEqualTo("45s");
        assertThat(StatusHeartbeat.compact(Duration.ofSeconds(192))).isEqualTo("3m12s");
        assertThat(StatusHeartbeat.compact(Duration.ofHours(1).plusMinutes(5))).isEqualTo("1h05m");

    }

}
