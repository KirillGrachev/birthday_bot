package eu.neydev.birthday.core.metrics;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;

/**
 * A lightweight metrics registry (counters + gauges) exported in the Prometheus format
 * without external dependencies. Enough for operational visibility:
 * queues, send outcomes, scheduler ticks, errors.
 */
public final class MetricsRegistry {

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, DoubleSupplier> gauges = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.AtomicLongArray> histograms =
            new ConcurrentHashMap<>();

    public void increment(String name, String... labelPairs) {
        counter(name, labelPairs).increment();
    }

    public void add(String name, long delta, String... labelPairs) {
        counter(name, labelPairs).add(delta);
    }

    public long count(String name, String... labelPairs) {
        return counter(name, labelPairs).sum();
    }

    public void gauge(String name, DoubleSupplier supplier, String... labelPairs) {
        gauges.put(seriesKey(name, labelPairs), supplier);
    }

    /**
     * The current value of a registered gauge, or {@code 0} when no such series exists.
     * Reading a gauge is how the status heartbeat reports queue sizes without holding
     * references to the queues themselves.
     */
    public double gaugeValue(String name, String... labelPairs) {
        DoubleSupplier supplier = gauges.get(seriesKey(name, labelPairs));
        return supplier == null ? 0 : supplier.getAsDouble();
    }

    /** A lightweight histogram analog: count+sum (Prometheus summary style). */
    public void observe(String name, double seconds, String... labelPairs) {

        java.util.concurrent.atomic.AtomicLongArray series = histograms.computeIfAbsent(
                seriesKey(name, labelPairs), k -> new java.util.concurrent.atomic.AtomicLongArray(2));

        series.getAndAdd(0, 1);
        series.getAndAdd(1, (long) (seconds * 1_000_000));

    }

    private LongAdder counter(String name, String... labelPairs) {
        return counters.computeIfAbsent(seriesKey(name, labelPairs), k -> new LongAdder());
    }

    private static String seriesKey(String name, String... labelPairs) {

        if (labelPairs.length == 0) {
            return sanitize(name) + "{}";
        }

        if (labelPairs.length % 2 != 0) {
            throw new IllegalArgumentException("Labels come in pairs: " + String.join(",", labelPairs));
        }

        Map<String, String> labels = new TreeMap<>();

        for (int i = 0; i < labelPairs.length; i += 2) {
            labels.put(sanitize(labelPairs[i]), sanitize(labelPairs[i + 1]));
        }

        StringBuilder sb = new StringBuilder(sanitize(name)).append('{');
        labels.forEach((k, v) -> sb.append(k).append("=\"").append(v).append("\","));
        sb.setLength(sb.length() - 1);

        return sb.append('}').toString();

    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9_:]", "_").toLowerCase(Locale.ROOT);
    }

    /** Prometheus text format for the /metrics endpoint. */
    public String prometheusText() {

        StringBuilder sb = new StringBuilder();

        new TreeMap<>(counters).forEach((series, adder) ->
                sb.append(series.replace('=', '~').replace('~', '=')).append(' ')
                        .append(adder.sum()).append('\n'));
        new TreeMap<>(gauges).forEach((series, supplier) ->
                sb.append(series).append(' ').append(supplier.getAsDouble()).append('\n'));

        return sb.toString();

    }

}
