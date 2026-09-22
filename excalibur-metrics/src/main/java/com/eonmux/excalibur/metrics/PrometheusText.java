package com.eonmux.excalibur.metrics;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Builds a body in the Prometheus text exposition format, version 0.0.4.
 *
 * <h2>Why this format</h2>
 * <p>It is the one a containerised database is scraped in. It needs no client library, no
 * registry and no push target: the node answers a GET with text, and every scraper in the
 * ecosystem - Prometheus itself, the OpenTelemetry collector, Grafana Agent, Datadog and
 * CloudWatch through their Prometheus receivers - reads it. The alternative already present
 * here, JMX, requires a port opened with {@code -Dcom.sun.management.jmxremote}, an RMI
 * callback address that does not survive a container's port mapping, and a client process per
 * node; none of that appears in this repository's build files, scripts or documentation, which
 * is why the seven registered MBeans were unreachable in a real deployment.</p>
 *
 * <h2>Why a builder rather than string concatenation</h2>
 * <p>The format fails whole-scrape rather than per-line. A locale that writes {@code 0,5} for a
 * half, a help line carrying a raw newline, a label value carrying an unescaped quote, or a
 * second {@code # TYPE} for a family already declared, each make a scraper reject everything
 * the node published - so the node would appear to be exporting while exporting nothing. Each
 * of those is a rule this class holds rather than a convention each call site must remember.</p>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>Not thread-safe.</strong> One instance renders one response, on the thread
 * serving it.</p>
 *
 * @author ExcaliburDB
 */
public final class PrometheusText {

    /** The largest magnitude written in full rather than in scientific notation. */
    private static final BigDecimal PLAIN_LIMIT = BigDecimal.valueOf(1e15);

    /**
     * Every family declared so far, in the order it was first declared, each holding its
     * type and the samples written under it.
     *
     * <p>Samples are grouped by family rather than appended as they arrive, because the format
     * allows one {@code # HELP} and one {@code # TYPE} per family: two calls writing different
     * labels of the same metric must produce one header and two samples.</p>
     */
    private final Map<String, Family> families = new LinkedHashMap<>();

    /**
     * One metric family: its type, its help text and its samples.
     *
     * @param type    {@code gauge} or {@code counter}
     * @param help    The one-line description a scraper shows beside the metric
     * @param samples The rendered sample lines, in the order they were added
     */
    private record Family(String type, String help, StringBuilder samples) {
    }

    /**
     * Adds an unlabelled gauge - a value that can go up and down.
     *
     * @param name  The metric name
     * @param help  What it measures, in one line
     * @param value The current value
     * @return This builder
     * @throws IllegalArgumentException if the name is not a valid metric name
     * @throws IllegalStateException    if the family was already declared as another type
     */
    public PrometheusText gauge(String name, String help, double value) {
        return sample("gauge", name, help, Map.of(), value);
    }

    /**
     * Adds a labelled gauge.
     *
     * @param name   The metric name
     * @param help   What it measures, in one line
     * @param labels The label set distinguishing this sample from its siblings
     * @param value  The current value
     * @return This builder
     * @throws IllegalArgumentException if a name is not valid
     * @throws IllegalStateException    if the family was already declared as another type
     */
    public PrometheusText gauge(String name, String help, Map<String, String> labels,
                                double value) {
        return sample("gauge", name, help, labels, value);
    }

    /**
     * Adds an unlabelled counter - a value that only ever increases.
     *
     * @param name  The metric name, which by convention ends in {@code _total}
     * @param help  What it counts, in one line
     * @param value The current total
     * @return This builder
     * @throws IllegalArgumentException if the name is not a valid metric name
     * @throws IllegalStateException    if the family was already declared as another type
     */
    public PrometheusText counter(String name, String help, double value) {
        return sample("counter", name, help, Map.of(), value);
    }

    /**
     * Adds a labelled counter.
     *
     * @param name   The metric name, which by convention ends in {@code _total}
     * @param help   What it counts, in one line
     * @param labels The label set distinguishing this sample from its siblings
     * @param value  The current total
     * @return This builder
     * @throws IllegalArgumentException if a name is not valid
     * @throws IllegalStateException    if the family was already declared as another type
     */
    public PrometheusText counter(String name, String help, Map<String, String> labels,
                                  double value) {
        return sample("counter", name, help, labels, value);
    }

    /**
     * Adds a summary - a set of quantiles over one measurement, and how many were taken.
     *
     * <p>A summary rather than a histogram because the underlying histogram is ten thousand
     * one-millisecond buckets: exported as {@code _bucket} series that is ten thousand lines
     * per scrape, for a resolution nobody alerts at. The quantiles are the five the JMX bean
     * already published, and the count beside them is what makes a rate out of them.</p>
     *
     * <p>No {@code _sum} is written. The histogram's mean is reconstructed from bucket
     * positions rather than kept as a running total, so a sum derived from it would be an
     * estimate presented as an exact figure, and an estimate is what {@code _sum} is used to
     * compute an average <em>from</em>. Both {@code _sum} and {@code _count} are optional in
     * this format; a wrong one is not.</p>
     *
     * @param name      The metric name
     * @param help      What it measures, in one line
     * @param quantiles Value by quantile, the quantile written as its label
     * @param count     How many measurements the quantiles were taken over
     * @return This builder
     * @throws IllegalArgumentException if the name is not valid, or a quantile is not a number
     * @throws IllegalStateException    if the family was already declared as another type
     */
    public PrometheusText summary(String name, String help, Map<String, Double> quantiles,
                                  long count) {
        requireValidName(name, "metric name");
        Objects.requireNonNull(help, "help cannot be null");
        Objects.requireNonNull(quantiles, "quantiles cannot be null");
        // Sorted numerically, not by the text of the label: "0.999" precedes "0.99" as a
        // string, which would put the tail quantile in the middle of the list.
        Map<String, Double> ordered = new TreeMap<>(
            Comparator.comparingDouble(PrometheusText::quantileValue));
        ordered.putAll(quantiles);

        for (Map.Entry<String, Double> quantile : ordered.entrySet()) {
            sample("summary", name, help, Map.of("quantile", quantile.getKey()),
                quantile.getValue());
        }
        Family family = families.computeIfAbsent(name,
            key -> new Family("summary", help, new StringBuilder()));
        family.samples().append(name).append("_count ").append(number(count)).append('\n');
        return this;
    }

    /**
     * A quantile label as the number it names.
     *
     * @param label The label, such as {@code 0.99}
     * @return Its numeric value, for ordering
     */
    private static double quantileValue(String label) {
        try {
            return Double.parseDouble(label);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Quantile label is not a number: " + label, e);
        }
    }

    /**
     * Adds one sample to a family, declaring the family if this is its first.
     *
     * @param type   {@code gauge} or {@code counter}
     * @param name   The metric name
     * @param help   What it measures
     * @param labels The label set, possibly empty
     * @param value  The value
     * @return This builder
     */
    private PrometheusText sample(String type, String name, String help,
                                  Map<String, String> labels, double value) {
        requireValidName(name, "metric name");
        Objects.requireNonNull(help, "help cannot be null");
        Family family = families.computeIfAbsent(name,
            key -> new Family(type, help, new StringBuilder()));
        if (!family.type().equals(type)) {
            throw new IllegalStateException("Metric " + name + " was already declared as a "
                + family.type() + " and cannot also be a " + type);
        }
        family.samples().append(name);
        appendLabels(family.samples(), labels);
        family.samples().append(' ').append(number(value)).append('\n');
        return this;
    }

    /**
     * Appends a label set, sorted by name.
     *
     * <p>Sorted so that two scrapes of the same state render identically: a diff between them
     * should show what changed in the node, not what order a hash map happened to iterate in.</p>
     *
     * @param out    The line being built
     * @param labels The label set, possibly empty
     */
    private static void appendLabels(StringBuilder out, Map<String, String> labels) {
        if (labels.isEmpty()) {
            return;
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, String> label : new TreeMap<>(labels).entrySet()) {
            requireValidName(label.getKey(), "label name");
            if (!first) {
                out.append(',');
            }
            out.append(label.getKey()).append("=\"")
                .append(escape(label.getValue())).append('"');
            first = false;
        }
        out.append('}');
    }

    /**
     * The exposition body.
     *
     * @return The complete text, ending in a newline, or empty when nothing was added
     */
    public String render() {
        StringBuilder out = new StringBuilder(2048);
        for (Map.Entry<String, Family> entry : families.entrySet()) {
            Family family = entry.getValue();
            out.append("# HELP ").append(entry.getKey()).append(' ')
                .append(escape(family.help())).append('\n');
            out.append("# TYPE ").append(entry.getKey()).append(' ')
                .append(family.type()).append('\n');
            out.append(family.samples());
        }
        return out.toString();
    }

    /**
     * Formats a value the way the exposition format reads numbers.
     *
     * <p>{@code Double.toString} is wrong here in three ways: it writes {@code 5.36870912E9}
     * for a byte count, {@code 12.0} for a count of twelve, and {@code Infinity} where the
     * format spells {@code +Inf}. {@link BigDecimal} is used for the plain range because
     * {@code %f} would pad a whole number with fractional zeros and a byte count with none.</p>
     *
     * @param value The value
     * @return The value as a scraper reads it
     */
    private static String number(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "+Inf" : "-Inf";
        }
        BigDecimal exact = BigDecimal.valueOf(value);
        if (exact.abs().compareTo(PLAIN_LIMIT) >= 0) {
            // Past this magnitude a plain rendering is a wall of digits no reader parses, and
            // the format accepts Go's scientific notation.
            return Double.toString(value);
        }
        return exact.stripTrailingZeros().toPlainString();
    }

    /**
     * Escapes the three characters that would otherwise change a line's structure.
     *
     * <p>A raw newline in a help line or a label value ends the line early, and everything
     * after it is read as a fresh metric; a raw quote in a label value closes the value. Both
     * are reachable from data - a version string, a volume path, a breaker name - so neither
     * can be assumed away.</p>
     *
     * @param value The raw text
     * @return The text with backslash, newline and quote escaped
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '"' -> out.append("\\\"");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Refuses a name a scraper would not parse.
     *
     * <p>Metric and label names are {@code [a-zA-Z_][a-zA-Z0-9_]*}. A hyphen is the mistake
     * that actually happens, because every other identifier in this project's configuration
     * uses one, and it produces a body that parses as far as the bad line and no further.</p>
     *
     * @param name What to check
     * @param what Which kind of name it is, for the message
     */
    private static void requireValidName(String name, String what) {
        Objects.requireNonNull(name, what + " cannot be null");
        if (name.isEmpty() || !isNameStart(name.charAt(0))) {
            throw new IllegalArgumentException("Invalid " + what + ": '" + name + "'");
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!isNameStart(c) && (c < '0' || c > '9')) {
                throw new IllegalArgumentException("Invalid " + what + ": '" + name + "'");
            }
        }
    }

    private static boolean isNameStart(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == ':';
    }
}
