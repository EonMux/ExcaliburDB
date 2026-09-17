package io.excalibur.metrics;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * How many responses this node has written, by status class.
 *
 * <p>The node had no failure signal of any kind. A rate-limit rejection, an authentication
 * failure and an unhandled handler exception each wrote a log line and were then gone, so the
 * three questions a monitoring system asks first - what is the request rate, what is the error
 * rate, what is being refused - could not be answered from anything the process published. A
 * histogram of how long the successful queries took says nothing about the ones that never ran.</p>
 *
 * <h2>Why classes rather than codes</h2>
 * <p>A counter per status code is a label whose cardinality is decided by whoever writes the
 * next handler. Five classes is the granularity an alert is actually written against - "5xx
 * over 1% of responses" - and the two refusals that matter operationally are broken out
 * alongside, because a node being throttled and a node rejecting credentials are different
 * incidents and both disappear into 4xx.</p>
 *
 * <p>{@link Rejection#THROTTLED} counts every 429, which on this server is the per-client rate
 * limiter and the registration and upload capacity bounds. They are one signal - the node is
 * refusing work it would otherwise do - and separating them would require the choke point to
 * know which handler it is writing for, which is the coupling it exists to avoid.</p>
 *
 * <h2>Process-wide, like the rest of this module</h2>
 * <p>Static state, matching {@link Metrics}: one JVM serves one node, and the HTTP layer that
 * records here has no registry to be handed. {@link #reset()} exists for tests, which would
 * otherwise see each other's counts.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. {@link LongAdder} is used rather than {@code AtomicLong} because every
 * response on every request thread writes here and nothing reads except a scrape.</p>
 *
 * @author ExcaliburDB
 */
public final class ResponseCounters {

    /**
     * The status classes of RFC 9110, as a monitoring label spells them.
     */
    public enum StatusClass {

        /** 1xx: the request is still being processed. */
        INFORMATIONAL("1xx"),

        /** 2xx: the node did what was asked. */
        SUCCESS("2xx"),

        /** 3xx: the caller must go elsewhere. */
        REDIRECTION("3xx"),

        /** 4xx: the request was refused, and the caller can act on the reason. */
        CLIENT_ERROR("4xx"),

        /** 5xx: the node failed, and the caller cannot. */
        SERVER_ERROR("5xx");

        private final String label;

        StatusClass(String label) {
            this.label = label;
        }

        /**
         * The value this class carries as a metric label.
         *
         * @return A stable label, safe to write an alert against
         */
        public String label() {
            return label;
        }

        /**
         * The class a status code belongs to.
         *
         * @param statusCode An HTTP status code in [100, 599]
         * @return The class holding it
         * @throws IllegalArgumentException if the code is not an HTTP status code
         */
        static StatusClass of(int statusCode) {
            return switch (statusCode / 100) {
                case 1 -> INFORMATIONAL;
                case 2 -> SUCCESS;
                case 3 -> REDIRECTION;
                case 4 -> CLIENT_ERROR;
                case 5 -> SERVER_ERROR;
                default -> throw new IllegalArgumentException(
                    "Not an HTTP status code: " + statusCode);
            };
        }
    }

    /**
     * The refusals broken out of {@link StatusClass#CLIENT_ERROR}.
     *
     * <p>A second view of responses already counted as 4xx, not a separate bucket, so a sum
     * over the classes is still the total number of responses.</p>
     */
    public enum Rejection {

        /** 429: the rate limiter, or a capacity bound, refused work the node would have done. */
        THROTTLED("throttled"),

        /** 401: the bearer token was missing or wrong. */
        UNAUTHENTICATED("unauthenticated");

        private final String label;

        Rejection(String label) {
            this.label = label;
        }

        /**
         * The value this refusal carries as a metric label.
         *
         * @return A stable label, safe to write an alert against
         */
        public String label() {
            return label;
        }
    }

    private static final Map<StatusClass, LongAdder> BY_CLASS = newCounters(StatusClass.class);
    private static final Map<Rejection, LongAdder> BY_REJECTION = newCounters(Rejection.class);

    private ResponseCounters() {
        // Utility class
    }

    private static <E extends Enum<E>> Map<E, LongAdder> newCounters(Class<E> type) {
        Map<E, LongAdder> counters = new EnumMap<>(type);
        for (E constant : type.getEnumConstants()) {
            counters.put(constant, new LongAdder());
        }
        return counters;
    }

    /**
     * Counts one response.
     *
     * <p>Called from the single place every response body is written, so a route added later
     * is instrumented by existing rather than by remembering to.</p>
     *
     * @param statusCode The status the response carried
     * @throws IllegalArgumentException if the code is not an HTTP status code, which would
     *                                  otherwise be counted under a class it does not belong to
     */
    public static void record(int statusCode) {
        BY_CLASS.get(StatusClass.of(statusCode)).increment();
        if (statusCode == 429) {
            BY_REJECTION.get(Rejection.THROTTLED).increment();
        } else if (statusCode == 401) {
            BY_REJECTION.get(Rejection.UNAUTHENTICATED).increment();
        }
    }

    /**
     * How many responses fell in a status class.
     *
     * @param statusClass The class to read
     * @return A non-negative count since the process started, or since the last reset
     */
    public static long count(StatusClass statusClass) {
        return BY_CLASS.get(statusClass).sum();
    }

    /**
     * How many responses were one of the broken-out refusals.
     *
     * @param rejection The refusal to read
     * @return A non-negative count since the process started, or since the last reset
     */
    public static long rejections(Rejection rejection) {
        return BY_REJECTION.get(rejection).sum();
    }

    /**
     * How many responses this node has written in total.
     *
     * <p>The denominator an error rate is computed against, and the request rate itself once
     * a scraper differences it over time.</p>
     *
     * @return A non-negative count since the process started, or since the last reset
     */
    public static long total() {
        long total = 0;
        for (LongAdder counter : BY_CLASS.values()) {
            total += counter.sum();
        }
        return total;
    }

    /**
     * Forgets every count.
     *
     * <p>For tests. The counters are process-wide, so without this one test's responses are
     * another's starting point.</p>
     */
    public static void reset() {
        BY_CLASS.values().forEach(LongAdder::reset);
        BY_REJECTION.values().forEach(LongAdder::reset);
    }
}
