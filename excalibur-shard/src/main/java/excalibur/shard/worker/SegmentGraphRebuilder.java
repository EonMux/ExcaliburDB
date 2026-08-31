package excalibur.shard.worker;

import io.excalibur.core.codec.ChunkPayloadCodec;
import io.excalibur.core.text.ChunkIdentifiers;
import io.excalibur.store.Segment;
import io.excalibur.store.format.SegmentEntry;
import io.excalibur.vector.hnsw.HnswGraph;
import io.excalibur.vector.hnsw.TailOffsetTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Rebuilds a segment's HNSW graph from the entries the segment actually holds.
 *
 * <h2>Why this exists</h2>
 * <p>Vectors reach a graph one way only: the integration worker reads them out of the segment's
 * write log. Nothing reads them out of the segment's data file. That is fine while a segment and
 * its log grow together, and wrong the moment a structural change hands a segment a data file and
 * a log that do not correspond:</p>
 * <ul>
 *   <li><b>Compaction</b> copies the live entries into a new file and discards the log. Anything
 *       the integration worker had not reached yet was copied as data and never indexed - and
 *       under sustained ingest the worker is always behind, so this is the normal case rather
 *       than a rare one.</li>
 *   <li><b>A split</b> distributes the parent's entries into two children, each with a fresh
 *       empty log. Neither child's graph describes what its data file holds.</li>
 * </ul>
 *
 * <p>In both cases the entries stay retrievable by key and disappear from vector search, which
 * is a silent loss of recall: no error, no counter, and no later event that would repair it.</p>
 *
 * <h2>Cost</h2>
 * <p>One HNSW insert per live entry. Both callers run after a structural change that has already
 * copied every one of those entries, so the rebuild is the same order of work as the operation
 * that made it necessary - and it happens off the routing path, after publication.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless and thread-safe, but a rebuild must not run concurrently with the integration
 * worker on the same graph: the caller is responsible for unregistering the segment first.</p>
 *
 * @author ExcaliburDB
 */
public final class SegmentGraphRebuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentGraphRebuilder.class);

    private SegmentGraphRebuilder() {
        throw new AssertionError("Utility class");
    }

    /**
     * Inserts every live entry of a segment into its graph and moves the checkpoint to the
     * segment's current log head.
     *
     * <p>The checkpoint is set last and to the head of the log the segment holds now: after the
     * rebuild the graph describes the data file, and the only entries still to integrate are the
     * ones appended from this point on.</p>
     *
     * @param segment The segment whose entries to index
     * @param graph   The graph to fill; expected to be empty
     * @param tracker The checkpoint to reset, or null when the segment has none
     * @return The number of vectors inserted
     * @throws NullPointerException if segment or graph is null
     */
    public static int rebuild(Segment segment, HnswGraph graph, TailOffsetTracker tracker) {
        Objects.requireNonNull(segment, "segment must not be null");
        Objects.requireNonNull(graph, "graph must not be null");

        int dimension = graph.config().dimension();
        int inserted = 0;
        int skipped = 0;

        try (Segment.EntryIterator entries = segment.liveEntries()) {
            while (entries.hasNext()) {
                SegmentEntry entry = entries.next();
                byte[] value = entry.value();
                float[] vector = value == null ? null : ChunkPayloadCodec.embeddingOf(value);

                // An entry that carries no vector, or one of another dimension, is not a
                // failure: a segment holds whatever was written to it, and one unreadable value
                // must not abort the rebuild of everything around it.
                if (vector == null || vector.length != dimension) {
                    skipped++;
                    continue;
                }

                long nodeId = graph.insert(vector);
                byte[] key = entry.key();
                if (key != null && key.length > 0) {
                    graph.registerDocumentId(nodeId, ChunkIdentifiers.documentKeyOfChunk(
                        new String(key, StandardCharsets.UTF_8)));
                }
                inserted++;
            }
        } catch (RuntimeException e) {
            LOGGER.error("Rebuilding the HNSW graph for {} stopped after {} vectors: {}",
                segment.path(), inserted, e.toString());
        }

        long head = segment.getWriteLogHead();
        graph.setLastIntegratedOffset(head);
        if (tracker != null) {
            tracker.resetTo(head);
        }

        LOGGER.info("Rebuilt the HNSW graph for {}: {} vectors indexed, {} entries skipped, "
            + "checkpoint set to the log head {}", segment.path(), inserted, skipped, head);
        return inserted;
    }
}
