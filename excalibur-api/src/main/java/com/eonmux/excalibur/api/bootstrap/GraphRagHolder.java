package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.graph.browse.GraphCatalog;
import com.eonmux.excalibur.retrieval.pipeline.GraphRagPipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The graph-RAG stack currently being served, which a build or a compaction can replace.
 *
 * <h2>Why a holder rather than a field</h2>
 * <p>The stack is opened from a directory that may not exist yet: a node that has ingested nothing,
 * or has ingested but never built, has no graph. Holding the stack behind one reference makes that
 * ordinary: the route is always registered, a query against an absent graph is told plainly that
 * there is none, and a build swaps the whole stack over.</p>
 *
 * <h2>Readers finish against the stack they started on</h2>
 * <p>A query {@link #acquire() acquires} the stack and releases it when it is done. A replaced
 * stack is closed when its last reader releases it, not when it is replaced, so a build or a
 * compaction never closes an index out from under a query that is reading it. The stack is opened
 * read-only, so the replacement can be opened at the same path while the previous one drains.</p>
 *
 * <h2>One replacement at a time</h2>
 * <p>A build publishes a new directory and a compaction rewrites the served one, and either
 * interleaved with the other loses work: a build publishing mid-compaction serves the edges the
 * compaction was removing, and a compaction that ran against a directory a build then replaced
 * has discharged an obligation against a graph nobody serves. Both take
 * {@link #replacementLock()}, which belongs here because this is what owns the directory.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use.</p>
 *
 * @author ExcaliburDB
 */
public final class GraphRagHolder implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphRagHolder.class);

    /**
     * How long {@link #withdraw()} waits for queries to finish with the stack it withdraws.
     *
     * <p>A graph query is a traversal and a rank over mapped files, and is over in milliseconds;
     * this is a bound on a query stalled in an outbound embedding call, not an estimate of an
     * ordinary one. A reader still holding the stack after it is closed anyway gets a failed
     * query rather than holding up an erasure indefinitely.</p>
     */
    static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(30);

    private final Path directory;
    private final AtomicReference<Served> current = new AtomicReference<>();
    private final ReentrantLock replacementLock = new ReentrantLock();

    /**
     * Orders {@link #install} against {@link #close()}. Without it an install that found the
     * holder open could swap its stack in after the close had emptied the holder, and the stack
     * would be served by a closed holder that nothing would ever close.
     */
    private final Object lifecycle = new Object();
    private boolean closed;

    /**
     * Creates a holder for the graph served from {@code directory}.
     *
     * @param directory Where the graph is read from and built into
     * @param initial   The stack opened at startup, or null when there is no graph yet
     */
    public GraphRagHolder(Path directory, GraphRagFactory.GraphRag initial) {
        this.directory = Objects.requireNonNull(directory, "directory must not be null");
        this.current.set(initial == null ? null : new Served(initial));
    }

    /**
     * Takes the served stack for the length of one query.
     *
     * <p>The stack stays open until the returned reading is closed, however many times it is
     * replaced in the meantime.</p>
     *
     * @return A reading of the stack, or empty when no graph is being served
     */
    public Optional<Reading> acquire() {
        while (true) {
            Served served = current.get();
            if (served == null) {
                return Optional.empty();
            }
            if (served.retain()) {
                return Optional.of(new Reading(served));
            }
            // Retired between the read and the retain. Whatever replaced it is in current now.
        }
    }

    /**
     * What the served stack costs the process, as {@code [mappings, descriptors]}.
     *
     * <p>Only the stack currently served is counted. A replaced stack still draining its last
     * readers is not, because it is on its way out and no longer answers new queries.</p>
     *
     * @return The mappings and descriptors held, or two zeroes when no graph is being served
     */
    public long[] cost() {
        Optional<Reading> reading = acquire();
        if (reading.isEmpty()) {
            return new long[] { 0L, 0L };
        }
        try (Reading held = reading.get()) {
            return held.served.stack.cost();
        }
    }

    /**
     * Whether a graph is currently being served.
     *
     * @return true when a query would be answered rather than refused
     */
    public boolean isPresent() {
        return current.get() != null;
    }

    /**
     * Where the graph is read from and built into.
     *
     * @return The directory
     */
    public Path directory() {
        return directory;
    }

    /**
     * The lock every change to the served directory takes.
     *
     * @return The lock
     */
    public ReentrantLock replacementLock() {
        return replacementLock;
    }

    /**
     * Serves {@code replacement} from now on.
     *
     * <p>The stack it replaces closes once the queries reading it have finished. New queries are
     * answered by the replacement from the moment this returns.</p>
     *
     * @param replacement The newly opened stack, or null to serve nothing
     * @throws IllegalStateException if the holder is closed; the replacement is closed too
     */
    public void install(GraphRagFactory.GraphRag replacement) {
        Served previous;
        synchronized (lifecycle) {
            if (closed) {
                closeQuietly(replacement);
                throw new IllegalStateException("graph holder is closed: " + directory);
            }
            previous = current.getAndSet(replacement == null ? null : new Served(replacement));
        }
        if (previous != null) {
            previous.release();
        }
        LOGGER.info("graph-RAG stack {} for {}",
            replacement == null ? "cleared" : "installed", directory);
    }

    /**
     * Stops serving the stack and waits until nothing is reading it.
     *
     * <p>For a caller about to rewrite the files the stack maps. New queries are told there is no
     * graph from the moment this is called; the ones already running finish first, and only then
     * does this return. A reader still holding the stack after {@link #DRAIN_TIMEOUT} does not
     * hold up the caller: the stack is closed regardless, and that query fails.</p>
     *
     * @return Whether a stack was being served
     */
    public boolean withdraw() {
        Served previous = current.getAndSet(null);
        if (previous == null) {
            return false;
        }
        previous.release();
        if (!previous.awaitClosed(DRAIN_TIMEOUT)) {
            LOGGER.warn("graph queries on {} were still running {} after the stack was "
                + "withdrawn; closing it under them", directory, DRAIN_TIMEOUT);
            previous.closeNow();
        }
        return true;
    }

    @Override
    public void close() {
        Served previous;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            previous = current.getAndSet(null);
        }
        if (previous != null) {
            previous.release();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            LOGGER.warn("failed to close a replaced graph-RAG stack: {}", e.toString());
        }
    }

    @Override
    public String toString() {
        return "GraphRagHolder[" + directory + ", " + (isPresent() ? "serving" : "empty") + "]";
    }

    /**
     * One query's hold on a stack.
     *
     * <p>Close it when the query is done; closing it twice releases it once.</p>
     */
    public static final class Reading implements AutoCloseable {

        private final Served served;
        private final AtomicBoolean released = new AtomicBoolean();

        private Reading(Served served) {
            this.served = served;
        }

        /**
         * The pipeline to answer with.
         *
         * @return The pipeline of the stack this reading holds
         */
        public GraphRagPipeline pipeline() {
            return served.stack.pipeline();
        }

        /**
         * The entity-by-entity view of the same stack.
         *
         * @return The catalog over the graph this reading holds
         */
        public GraphCatalog catalog() {
            return served.stack.catalog();
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                served.release();
            }
        }
    }

    /**
     * A stack and the count of what holds it: the holder while it is served, and each reading.
     */
    private static final class Served {

        private final GraphRagFactory.GraphRag stack;
        private final AtomicInteger references = new AtomicInteger(1);
        private final AtomicBoolean closedOnce = new AtomicBoolean();
        private final CountDownLatch closedLatch = new CountDownLatch(1);

        Served(GraphRagFactory.GraphRag stack) {
            this.stack = stack;
        }

        /** Adds a reader, unless the count has already reached zero and the stack is closing. */
        boolean retain() {
            while (true) {
                int held = references.get();
                if (held == 0) {
                    return false;
                }
                if (references.compareAndSet(held, held + 1)) {
                    return true;
                }
            }
        }

        void release() {
            if (references.decrementAndGet() == 0) {
                closeNow();
            }
        }

        void closeNow() {
            if (closedOnce.compareAndSet(false, true)) {
                closeQuietly(stack);
                closedLatch.countDown();
            }
        }

        boolean awaitClosed(Duration timeout) {
            try {
                return closedLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
