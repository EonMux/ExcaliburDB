package com.eonmux.excalibur.ingest.visibility;

import com.eonmux.excalibur.store.VisibilityTracker;

import java.util.Objects;

/**
 * The counter that decides when a commit becomes visible.
 *
 * <p>Two methods rather than the whole {@link VisibilityTracker}, because publishing a document
 * needs to read and advance the epoch and nothing else; a tracker also registers and tombstones
 * segments, which is not this caller's business.
 *
 * <h2>Why this exists at all</h2>
 * <p>An epoch is only meaningful against the counter it came from. A document committed at epoch
 * 7 on one counter and a query capturing epoch 7 on another are two unrelated numbers that
 * happen to be equal, and comparing them produces an answer that looks right and is arbitrary.
 * Naming the clock makes the sharing explicit at the wiring site, so a component that publishes
 * on one clock cannot be silently read by a query capturing on another.
 *
 * @see DocumentVisibilityChecker#sharesNodeEpochClock()
 */
public interface VisibilityEpochClock {

    /** The current epoch. May advance immediately after this returns. */
    long currentEpoch();

    /**
     * Advances the epoch by one.
     *
     * @return The new epoch
     */
    long advanceEpoch();

    /**
     * The node's own clock: the one queries capture and segment visibility is decided on.
     *
     * @param tracker The node visibility tracker
     * @return A clock backed by it
     * @throws NullPointerException if tracker is null
     */
    static VisibilityEpochClock of(VisibilityTracker tracker) {
        Objects.requireNonNull(tracker, "tracker must not be null");
        return new VisibilityEpochClock() {
            @Override
            public long currentEpoch() {
                return tracker.currentEpoch();
            }

            @Override
            public long advanceEpoch() {
                return tracker.advanceEpoch();
            }

            @Override
            public String toString() {
                return "VisibilityEpochClock[node]";
            }
        };
    }
}
