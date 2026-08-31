package excalibur.api.bootstrap;

import io.excalibur.retrieval.pipeline.GraphRagPipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The graph-RAG stack currently being served, which a build can replace.
 *
 * <h2>Why a holder rather than a field</h2>
 * <p>The stack is opened from a directory that may not exist yet: a node that has ingested nothing,
 * or has ingested but never built, has no graph. Before this, the route was registered only when a
 * graph happened to be present at startup, so a node that built one later kept answering as though
 * it had none until someone restarted it - and a node that rebuilt one went on serving the previous
 * store from mappings the rebuild had already replaced on disk.</p>
 *
 * <p>Holding the stack behind one reference makes both cases ordinary: the route is always
 * registered, a query against an absent graph is told plainly that there is none, and a build swaps
 * the whole stack over and closes what it replaced.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use. Readers take the current stack in one volatile read; a swap publishes
 * the new one before closing the old, so a query that started against the previous stack finishes
 * against it.</p>
 *
 * @author ExcaliburDB
 */
public final class GraphRagHolder implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphRagHolder.class);

    private final Path directory;
    private final AtomicReference<GraphRagFactory.GraphRag> current = new AtomicReference<>();
    private volatile boolean closed;

    /**
     * Creates a holder for the graph served from {@code directory}.
     *
     * @param directory Where the graph is read from and built into
     * @param initial   The stack opened at startup, or null when there is no graph yet
     */
    public GraphRagHolder(Path directory, GraphRagFactory.GraphRag initial) {
        this.directory = Objects.requireNonNull(directory, "directory must not be null");
        this.current.set(initial);
    }

    /**
     * The pipeline to answer with, if there is a graph.
     *
     * @return The pipeline, or empty when no graph has been built
     */
    public Optional<GraphRagPipeline> pipeline() {
        GraphRagFactory.GraphRag stack = current.get();
        return stack == null ? Optional.empty() : Optional.of(stack.pipeline());
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
     * Replaces the served stack, closing whatever it replaces.
     *
     * <p>The new stack is published before the old one is closed, so a query that has already
     * taken a reference finishes against the stack it took rather than against a half-swapped one.
     * That is not a guarantee it finishes successfully: the memory-mapped columns survive a close
     * of the object that opened them, but Lucene's reader does not, and a query still reading one
     * gets an {@code AlreadyClosedException}. Callers that must not do that to a reader hold a
     * lease and let eviction defer - see {@code GraphTenants}. What the query path does guarantee
     * is that such a failure degrades a result rather than failing a request.
     *
     * @param replacement The newly opened stack, or null to serve nothing
     */
    public void install(GraphRagFactory.GraphRag replacement) {
        if (closed) {
            closeQuietly(replacement);
            throw new IllegalStateException("graph holder is closed: " + directory);
        }
        GraphRagFactory.GraphRag previous = current.getAndSet(replacement);
        if (previous != null && previous != replacement) {
            closeQuietly(previous);
        }
        LOGGER.info("graph-RAG stack {} for {}",
            replacement == null ? "cleared" : "installed", directory);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeQuietly(current.getAndSet(null));
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
}
