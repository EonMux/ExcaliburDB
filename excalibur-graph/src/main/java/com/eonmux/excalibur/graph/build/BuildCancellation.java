package com.eonmux.excalibur.graph.build;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * How a graph build in progress is told to stop, and where it is allowed to.
 *
 * <h2>Why cooperative rather than an interrupt</h2>
 * <p>A build is not uniformly interruptible. Everything up to the publish is assembled in a
 * scratch directory beside the served graph, so abandoning it costs work and nothing else - but
 * the publish itself closes the served stack, renames two directories and reopens what it
 * renamed. An interrupt landing in that window leaves a namespace serving nothing while its
 * graph sits intact on disk, which is a worse state than the one the stop was meant to avoid.
 * So a build is never interrupted: it is asked, and it stops at a point of its own choosing.</p>
 *
 * <h2>Where those points are</h2>
 * <p>Every expensive phase of a build streams the staged data - the fact replay that fills the
 * edge store, the snippet replay the two Lucene indexes and the entity vectors are built from,
 * and the vector written for each entity a batch came back embedded. Each of those checks here,
 * so the wait between asking and stopping is one staged record, one embedding batch or one sort.
 * After the last of them the build publishes, and from that point it finishes: a rename is not
 * worth stopping in the middle of.</p>
 *
 * <h2>Why the signal is per-thread</h2>
 * <p>A build runs on one thread and the staging store it reads is shared - ingestion appends to
 * it and an erasure rebuild replays it, both from threads of their own. A flag on the store
 * would stop them too, and work nobody asked to stop would end with a graph missing the facts
 * that were in flight. The signal therefore belongs to the thread doing the build, which is what
 * a scope establishes.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Each thread sees only the signal it set; the supplier itself is read from the
 * build thread and written by whoever asked for the stop, so an implementation of it has to be
 * safe for that - an {@code AtomicBoolean::get} is the intended shape.</p>
 *
 * @see BuildCancelledException
 * @author ExcaliburDB
 */
public final class BuildCancellation {

    /**
     * How many items pass between checks in a loop hot enough to count them.
     *
     * <p>A power of two, so the test is a mask. The loops that use it run once per staged
     * record, where a thread-local read and a call through a supplier would be a measurable
     * fraction of the work done per item; the ones that do not are already doing a file write
     * or an embedding call per item, where it is nothing.</p>
     */
    public static final int CHECK_INTERVAL = 1024;

    private static final ThreadLocal<BooleanSupplier> CURRENT = new ThreadLocal<>();

    private BuildCancellation() {
        throw new AssertionError("Utility class");
    }

    /**
     * Makes a stop signal current for this thread, until the scope is closed.
     *
     * <p>Close it - a try-with-resources - around the whole build. A scope left open would make
     * the next build on the same pool thread answer to a signal nobody set for it, which is the
     * shape of bug that cancels a run whose own request never asked for anything.</p>
     *
     * @param cancelRequested Read at each checkpoint; true asks the build to stop
     * @return The scope, which restores whatever was current when it is closed
     * @throws NullPointerException if cancelRequested is null
     */
    public static Scope scope(BooleanSupplier cancelRequested) {
        Objects.requireNonNull(cancelRequested, "cancelRequested must not be null");
        Scope scope = new Scope(CURRENT.get());
        CURRENT.set(cancelRequested);
        return scope;
    }

    /**
     * Ends the build here if it has been asked to stop.
     *
     * <p>A safe point, by construction: every caller is either between two staged records or
     * between two phases, with nothing published and nothing half-written that the next build
     * does not overwrite.</p>
     *
     * @throws BuildCancelledException if the build on this thread has been asked to stop
     */
    public static void checkpoint() {
        if (isCancelled()) {
            throw new BuildCancelledException(
                "the graph build was asked to stop and ended before publishing");
        }
    }

    /**
     * Ends the build here if it has been asked to stop, checking one item in
     * {@value #CHECK_INTERVAL}.
     *
     * <p>For the per-record loops. The first item of a loop is always checked, so a build that
     * was asked to stop before it started does no work at all.</p>
     *
     * @param progress How many items the loop has reached; counted from zero
     * @throws BuildCancelledException if the build on this thread has been asked to stop
     */
    public static void checkpoint(long progress) {
        if ((progress & (CHECK_INTERVAL - 1)) == 0) {
            checkpoint();
        }
    }

    /**
     * Whether the build on this thread has been asked to stop.
     *
     * <p>For a caller that has cleanup to decide about rather than an exception to raise.</p>
     *
     * @return true if a scope is current and its signal says stop
     */
    public static boolean isCancelled() {
        BooleanSupplier signal = CURRENT.get();
        return signal != null && signal.getAsBoolean();
    }

    /**
     * The window in which a build answers to a stop signal.
     *
     * <p>Restores the signal that was current when it was opened, so scopes nest without one
     * build's stop outliving it on a thread another build is about to use.</p>
     */
    public static final class Scope implements AutoCloseable {

        private final BooleanSupplier previous;

        private Scope(BooleanSupplier previous) {
            this.previous = previous;
        }

        /** Restores the signal that was current before this scope was opened. */
        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
