package com.eonmux.excalibur.graph.temporal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Valid-time selector: which facts held in the world at a given instant.
 *
 * <p>This is the <em>valid time</em> axis and nothing else. The engine's other temporal axis is the
 * epoch carried by {@code excalibur-store}, which records when the index learned a fact
 * (transaction time). The two are never compared with each other and never share a column, a type
 * or a filter. A query for "what was true in 2019" and a query for "what the index knew as of
 * epoch 4200" are different questions, and answering one with the other is silent corruption
 * rather than an error.
 *
 * <p>Intervals are half-open: an edge is admitted when
 * {@code validFrom <= asOfDays && asOfDays < validTo}. An edge whose {@code validTo} is
 * {@link #OPEN} has no end and is admitted by every {@code asOf} at or after its start.
 *
 * <p>Instances are immutable and cheap enough to pass through a traversal hot loop; {@link #admits}
 * allocates nothing.
 */
public record TemporalFilter(int asOfDays) {

    /**
     * Sentinel {@code validTo} for an interval with no end. Chosen as {@link Integer#MAX_VALUE} so
     * that the ordinary half-open comparison admits it without a special case: no real
     * {@code asOfDays} ever reaches it.
     */
    public static final int OPEN = Integer.MAX_VALUE;

    /**
     * Admits every edge regardless of its interval, for callers that genuinely want the whole
     * history rather than a snapshot.
     *
     * <p>Represented as {@code asOfDays == OPEN}, which {@link #admits} special-cases. It is not
     * "as of the far future": an edge closed in 2020 is excluded by a far-future snapshot but
     * included here.
     */
    public static final TemporalFilter ALL = new TemporalFilter(OPEN);

    /** Days between the epoch and {@code date}. */
    public static TemporalFilter asOf(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date must not be null");
        }
        return new TemporalFilter(Math.toIntExact(date.toEpochDay()));
    }

    /** Days between the epoch and {@code instant}, truncated towards the epoch. */
    public static TemporalFilter asOf(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException("instant must not be null");
        }
        return asOf(LocalDate.ofInstant(instant, ZoneOffset.UTC));
    }

    /**
     * The default for every traversal that does not name a time: what holds now.
     *
     * <p>Reads the clock on each call, so a long-running traversal that wants one consistent
     * snapshot must capture this once and thread the same instance through.
     */
    public static TemporalFilter now() {
        return asOf(LocalDate.now(ZoneOffset.UTC));
    }

    /** Whether this filter admits every interval rather than selecting a snapshot. */
    public boolean isUnbounded() {
        return asOfDays == OPEN;
    }

    /**
     * Whether an edge valid over {@code [validFrom, validTo)} is visible at this filter's instant.
     *
     * <p>Called once per candidate edge inside expansion, so it stays branch-light and
     * allocation-free.
     *
     * @param validFrom first day the fact held, days since epoch
     * @param validTo   first day it no longer held, or {@link #OPEN} if it still does
     */
    public boolean admits(int validFrom, int validTo) {
        if (asOfDays == OPEN) {
            return true;
        }
        return validFrom <= asOfDays && asOfDays < validTo;
    }

    /**
     * Validates an interval as a pair. Rejects a backwards or empty interval, which otherwise
     * becomes an edge that no query can ever return and no test would notice.
     */
    public static void checkInterval(int validFrom, int validTo) {
        if (validFrom > validTo) {
            throw new IllegalArgumentException(
                "validFrom (" + validFrom + ") must not be after validTo (" + validTo + ")");
        }
        if (validFrom == validTo) {
            throw new IllegalArgumentException(
                "empty valid interval at " + validFrom
                    + "; a half-open interval of zero length is never visible to any query");
        }
    }
}
