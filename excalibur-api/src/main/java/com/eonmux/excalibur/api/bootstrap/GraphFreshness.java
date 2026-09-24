package com.eonmux.excalibur.api.bootstrap;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether a namespace's served graph is behind what it has staged.
 *
 * <p>Ingestion stages every change - a new document's facts, a replaced or removed one's
 * withdrawal - and the graph a query reads is what the last build published. This is the
 * difference between the two, for the automatic build deciding what to rebuild and for the
 * routes that tell an operator whether {@code /v1/graph/query} reflects the latest ingest.</p>
 *
 * @param behind        Whether staging holds something the served graph does not: a change
 *                      since the last build, or staged facts no build has published
 * @param pendingSince  When the oldest such change was made, if known
 * @param lastChangedAt When the newest was made, if known
 * @param withheld      Whether the graph is withheld until an interrupted erasure is applied,
 *                      which a build must not get ahead of
 */
public record GraphFreshness(boolean behind, Optional<Instant> pendingSince,
                             Optional<Instant> lastChangedAt, boolean withheld) {

    /** A served graph that has everything staging holds, or a namespace with nothing staged. */
    public static final GraphFreshness CURRENT =
        new GraphFreshness(false, Optional.empty(), Optional.empty(), false);

    /**
     * Validates the record.
     *
     * @throws NullPointerException if an optional is null
     */
    public GraphFreshness {
        Objects.requireNonNull(pendingSince, "pendingSince must not be null");
        Objects.requireNonNull(lastChangedAt, "lastChangedAt must not be null");
    }

    /**
     * A graph behind its staging.
     *
     * @param pendingSince  When the oldest unpublished change was made, if known
     * @param lastChangedAt When the newest was made, if known
     * @return The freshness
     */
    public static GraphFreshness behind(Optional<Instant> pendingSince,
                                        Optional<Instant> lastChangedAt) {
        return new GraphFreshness(true, pendingSince, lastChangedAt, false);
    }

    /**
     * The same freshness, withheld or not.
     *
     * @param isWithheld Whether the graph is withheld for an interrupted erasure
     * @return The freshness
     */
    public GraphFreshness withheld(boolean isWithheld) {
        return new GraphFreshness(behind, pendingSince, lastChangedAt, isWithheld);
    }
}
