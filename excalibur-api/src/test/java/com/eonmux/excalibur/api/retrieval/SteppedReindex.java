package com.eonmux.excalibur.api.retrieval;

import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;
import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex;
import com.eonmux.excalibur.ingest.pipeline.DocumentBatchBuffer;
import com.eonmux.excalibur.ingest.pipeline.DocumentBatchPublisher;
import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer;
import com.eonmux.excalibur.ingest.pipeline.OffsetAwareStorageWriter;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityChecker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A node's document state - storage, registry, visibility tracker and lexical index - with a
 * reindexer that stops at every point a query could land on.
 *
 * <p>The reindex runs on a thread of its own and stops after every chunk write, after every
 * tombstone and on either side of each publication. At each stop the lexical index is refreshed,
 * as the node's refresher may do at any moment, and the caller's query runs against what the
 * node then holds. Two rendezvous per stop and no sleeps: the reindex cannot move on until the
 * query has looked, and the query cannot look until the reindex has stopped.</p>
 *
 * <p>Every word of a document's text names its version, and every chunk also carries
 * {@link #COMMON}, so one query matches every chunk of every version and a result can be told
 * apart by its text alone.</p>
 */
public final class SteppedReindex implements AutoCloseable {

    /** The source every document here belongs to. */
    public static final String SOURCE = "docs";

    /** A word in every chunk of every version. */
    public static final String COMMON = "shared";

    /** Small enough that a few dozen words span several chunks. */
    private static final int CHUNK_TOKENS = 8;

    private static final String DONE = "done";

    /** What storage holds, which is what a chunk's text is read back from. */
    private final Map<String, String> storage = new ConcurrentHashMap<>();

    private final Stepper stepper = new Stepper();
    private final ExecutorService reindexThread = Executors.newSingleThreadExecutor();
    private final StubSource source = new StubSource();

    private final DocumentVisibilityTracker tracker;
    private final DocumentVisibilityChecker checker;
    private final ChunkLexicalIndex index;
    private final DocumentReindexer reindexer;

    /**
     * @param dataDir Where the registry, its in-flight journal and the lexical index live
     * @throws IOException if the lexical index cannot be opened
     */
    public SteppedReindex(Path dataDir) throws IOException {
        SegmentCatalog catalog = new SegmentCatalog(dataDir.resolve("catalog"));
        DocumentRegistry registry = new DocumentRegistry(dataDir.resolve("document-registry"));
        VisibilityTracker clock = new VisibilityTracker(catalog);
        tracker = new DocumentVisibilityTracker(registry, clock);
        checker = new DocumentVisibilityTrackerAdapter(tracker);
        index = ChunkLexicalIndex.open(dataDir.resolve("lexical"), 8.0);
        SteppingWriter writer = new SteppingWriter();
        reindexer = new DocumentReindexer(new TextChunker(CHUNK_TOKENS, 0), null,
            new UpsertHandler(registry, writer), registry, writer,
            new SteppingPublisher(tracker), catalog, clock);
        reindexer.setLexicalIndex(index, storage::get);
    }

    /** The tracker the reindexer publishes through. */
    public DocumentVisibilityTracker tracker() {
        return tracker;
    }

    /** The checker a node's query paths consult, over {@link #tracker()}. */
    public DocumentVisibilityChecker checker() {
        return checker;
    }

    /** The lexical index the reindexer writes. */
    public ChunkLexicalIndex index() {
        return index;
    }

    /** Reads a chunk's text straight out of storage, as the node's stored-text reader does. */
    public Function<String, String> storedText() {
        return storage::get;
    }

    /**
     * Storage as a storage engine returns it, for a mocked engine to answer reads with.
     *
     * @param key The chunk id's bytes
     * @return The stored payload, or null when storage holds nothing under the key
     */
    public MemorySegment stored(byte[] key) {
        String text = storage.get(new String(key, StandardCharsets.UTF_8));
        return text == null ? null : MemorySegment.ofArray(ChunkPayloadCodec.encodeTextOnly(text));
    }

    /**
     * Sets a document's content at the source, to be picked up by the next reindex.
     *
     * @param documentId The document
     * @param prefix     What every word of this version starts with
     * @param chunks     Roughly how many chunks the version spans
     */
    public void put(String documentId, String prefix, int chunks) {
        source.put(documentId, version(prefix, chunks));
    }

    /**
     * Reindexes the source to completion without stopping, and publishes the index.
     *
     * @throws Exception if the reindex fails
     */
    public void reindex() throws Exception {
        reindexer.reindexAll(source);
        index.refresh();
    }

    /**
     * Reindexes the source on its own thread, running the query at every stop.
     *
     * @param query What a caller would see at that moment
     * @return Every observation, one per stop and one once the reindex has finished
     * @throws Exception if the reindex failed or never stopped
     */
    public List<Observation> observeReindex(Query query) throws Exception {
        stepper.armed = true;
        Future<?> run = reindexThread.submit(() -> {
            try {
                return reindexer.reindexAll(source);
            } finally {
                stepper.finish();
            }
        });
        List<Observation> observations = new ArrayList<>();
        try {
            while (true) {
                String point = stepper.awaitStop();
                index.refresh();
                observations.add(new Observation(point, query.run()));
                if (DONE.equals(point)) {
                    break;
                }
                stepper.resume();
            }
        } finally {
            stepper.armed = false;
        }
        try {
            run.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("the reindex failed", e.getCause());
        }
        return observations;
    }

    /**
     * Every chunk storage holds, by chunk id.
     *
     * @return A copy of storage
     */
    public Map<String, String> storedChunks() {
        return new TreeMap<>(storage);
    }

    /**
     * The chunks storage holds for a document, by chunk id.
     *
     * @param documentId The document
     * @return Its chunks and their text
     */
    public Map<String, String> storedVersionOf(String documentId) {
        String documentKey = ChunkIdentifiers.documentKey(SOURCE, documentId);
        Map<String, String> version = new TreeMap<>();
        storage.forEach((chunkId, text) -> {
            if (ChunkIdentifiers.documentKeyOfChunk(chunkId).equals(documentKey)) {
                version.put(chunkId, text);
            }
        });
        return version;
    }

    /**
     * The chunks of one document among what a query returned.
     *
     * @param documentId The document
     * @param returned   Chunk ids and the text returned with them
     * @return Those of the document
     */
    public static Map<String, String> ofDocument(String documentId, Map<String, String> returned) {
        String documentKey = ChunkIdentifiers.documentKey(SOURCE, documentId);
        Map<String, String> seen = new TreeMap<>();
        returned.forEach((chunkId, text) -> {
            if (ChunkIdentifiers.documentKeyOfChunk(chunkId).equals(documentKey)) {
                seen.put(chunkId, text);
            }
        });
        return seen;
    }

    /**
     * Whether what a query returned of a document is part of one version, text included.
     *
     * <p>A chunk returned without text is one whose text was withheld because its document was
     * being written when it was read; it shows nothing of either version, so it only has to be
     * one of this version's chunks.</p>
     *
     * @param seen    The document's chunks the query returned, with their text
     * @param version One version of it, as storage held it
     * @return true when every returned chunk is one of the version's, with its text or none
     */
    public static boolean partOf(Map<String, String> seen, Map<String, String> version) {
        for (Map.Entry<String, String> chunk : seen.entrySet()) {
            if (!version.containsKey(chunk.getKey())) {
                return false;
            }
            if (chunk.getValue() != null && !chunk.getValue().equals(version.get(chunk.getKey()))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        reindexThread.shutdownNow();
        index.close();
    }

    private static String version(String prefix, int chunks) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < chunks * CHUNK_TOKENS; i++) {
            text.append(prefix).append((char) ('a' + i / 26)).append((char) ('a' + i % 26))
                .append(' ').append(COMMON).append(' ');
        }
        return text.toString().trim();
    }

    /** What a caller sees at one moment: chunk ids and the text returned with them. */
    @FunctionalInterface
    public interface Query {
        /**
         * @return The chunks returned, in rank order, with their text or null for none
         * @throws Exception if the query fails
         */
        Map<String, String> run() throws Exception;
    }

    /**
     * A query and where the reindex stood when it ran.
     *
     * @param point    Where the reindex was stopped
     * @param returned What the query returned
     */
    public record Observation(String point, Map<String, String> returned) {
    }

    /** Two rendezvous per stop, so neither side can run ahead of the other. */
    private static final class Stepper {
        private final SynchronousQueue<String> stops = new SynchronousQueue<>();
        private final SynchronousQueue<Boolean> resumes = new SynchronousQueue<>();
        volatile boolean armed;

        void stop(String point) {
            if (!armed) {
                return;
            }
            try {
                if (!stops.offer(point, 30, TimeUnit.SECONDS)
                        || resumes.poll(30, TimeUnit.SECONDS) == null) {
                    throw new IllegalStateException("no query came to look at " + point);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        void finish() {
            if (armed) {
                try {
                    stops.offer(DONE, 30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        String awaitStop() throws InterruptedException {
            String point = stops.poll(30, TimeUnit.SECONDS);
            if (point == null) {
                throw new IllegalStateException("the reindex neither stopped nor finished");
            }
            return point;
        }

        void resume() throws InterruptedException {
            if (!resumes.offer(Boolean.TRUE, 30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the reindex did not wait to be resumed");
            }
        }
    }

    /** Writes to {@link #storage}, stopping after every write and every tombstone. */
    private final class SteppingWriter
            implements OffsetAwareStorageWriter, UpsertHandler.StorageAdapter {
        private long nextOffset;

        @Override
        public long writeChunkWithOffset(String chunkId, String content, float[] embedding) {
            storage.put(chunkId, content);
            stepper.stop("after writing " + chunkId);
            return nextOffset++;
        }

        @Override
        public boolean canWriteTo(long segmentId) {
            return false;
        }

        @Override
        public boolean delete(byte[] key) {
            String chunkId = new String(key, StandardCharsets.UTF_8);
            boolean removed = storage.remove(chunkId) != null;
            stepper.stop("after tombstoning " + chunkId);
            return removed;
        }
    }

    /** Publishes through the tracker, stopping on either side of the publication. */
    private final class SteppingPublisher extends DocumentBatchPublisher {

        SteppingPublisher(DocumentVisibilityTracker documents) {
            super(documents);
        }

        @Override
        public PublicationResult publish(DocumentBatchBuffer buffer, DocumentMetadata metadata) {
            stepper.stop("before publishing " + buffer.getDocumentId());
            PublicationResult published = super.publish(buffer, metadata);
            stepper.stop("after publishing " + buffer.getDocumentId());
            return published;
        }
    }

    /** A source whose documents the test decides. */
    private static final class StubSource implements Source {

        private final Map<String, String> documents = new LinkedHashMap<>();

        /** When each document last changed, so an unchanged one is not reindexed. */
        private final Map<String, Long> modified = new HashMap<>();

        private long clock;

        void put(String documentId, String content) {
            documents.put(documentId, content);
            modified.put(documentId, ++clock);
        }

        @Override
        public String getSourceId() {
            return SOURCE;
        }

        @Override
        public String getSourceType() {
            return "stub";
        }

        @Override
        public Stream<String> crawl() {
            return List.copyOf(documents.keySet()).stream();
        }

        @Override
        public FetchedDocument fetch(String documentId) throws SourceException {
            String content = documents.get(documentId);
            if (content == null) {
                throw new SourceException("Document not found: " + documentId);
            }
            return new FetchedDocument(documentId, content.getBytes(StandardCharsets.UTF_8),
                "text/plain", modified.get(documentId));
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
