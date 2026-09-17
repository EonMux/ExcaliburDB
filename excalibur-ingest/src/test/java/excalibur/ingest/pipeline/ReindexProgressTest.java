package excalibur.ingest.pipeline;

import excalibur.filter.CuckooFilter;
import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.registry.DocumentRegistry;
import excalibur.ingest.registry.UpsertHandler;
import excalibur.ingest.source.FetchedDocument;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a reindex reports about its own progress while it runs.
 *
 * <p>The counters a caller polls were constants: a run reported 0 of 0 documents for its whole
 * length and then a total taken from a chunk count. Nothing derived from the work was ever
 * published, so a long reindex was indistinguishable from a stuck one.</p>
 */
@DisplayName("Reindex progress")
class ReindexProgressTest {

    private static final String SOURCE = "progress-source";

    private DocumentReindexer reindexer;
    private DocumentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DocumentRegistry();
        RecordingWriter writer = new RecordingWriter();
        reindexer = new DocumentReindexer(new TextChunker(512, 64), null,
            new UpsertHandler(registry, CuckooFilter.create(10_000), writer), registry, writer);
    }

    /** Every (processed, total) pair a run published, in order. */
    private static final class Reported implements DocumentReindexer.ReindexProgress {

        private final List<long[]> pairs = new ArrayList<>();

        @Override
        public void documentsDone(long documentsProcessed, long documentsTotal) {
            pairs.add(new long[] {documentsProcessed, documentsTotal});
        }

        List<String> asText() {
            List<String> text = new ArrayList<>(pairs.size());
            for (long[] pair : pairs) {
                text.add(pair[0] + "/" + pair[1]);
            }
            return text;
        }
    }

    @Test
    @DisplayName("every document moves the counter, and the total is in documents")
    void countsEachDocument() throws SourceException {
        StubSource source = new StubSource();
        source.add("doc-a", "The first document, long enough to chunk twice. ".repeat(60));
        source.add("doc-b", "The second document, also long enough to chunk. ".repeat(60));
        source.add("doc-c", "The third document. ".repeat(60));
        Reported reported = new Reported();

        DocumentReindexer.ReindexResult result = reindexer.reindexAll(source, reported);

        assertThat(reported.asText())
            .as("a caller polling the status has to see the run advance, not a constant")
            .containsExactly("0/3", "1/3", "2/3", "3/3");
        assertThat(result.getChunksCreated())
            .as("the documents chunk more than once, so a total taken from chunks would "
                + "not be 3 - which is what made documents_total wrong")
            .isGreaterThan(3);
    }

    @Test
    @DisplayName("a document that could not be fetched still advances the counter")
    void countsAFailedDocument() throws SourceException {
        StubSource source = new StubSource();
        source.add("doc-a", "A readable document. ".repeat(60));
        source.add("doc-b", "An unreadable document. ".repeat(60));
        source.failOn("doc-b");
        Reported reported = new Reported();

        reindexer.reindexAll(source, reported);

        assertThat(reported.asText())
            .as("a run that stops advancing because one document failed reads as hung")
            .containsExactly("0/2", "1/2", "2/2");
    }

    @Test
    @DisplayName("a source with nothing to do reports that at once")
    void reportsAnEmptyRun() throws SourceException {
        Reported reported = new Reported();

        reindexer.reindexAll(new StubSource(), reported);

        assertThat(reported.asText()).containsExactly("0/0");
    }

    @Test
    @DisplayName("a run with no listener behaves exactly as before")
    void theListenerIsOptional() throws SourceException {
        StubSource source = new StubSource();
        source.add("doc-a", "Still indexed. ".repeat(60));

        assertThat(reindexer.reindexAll(source).getDocumentsIndexed()).isEqualTo(1);
    }

    /** A storage writer that accepts everything, vector or not. */
    private static final class RecordingWriter
            implements DocumentReindexer.StorageWriter, UpsertHandler.StorageAdapter {

        private final Map<String, String> text = new ConcurrentHashMap<>();

        @Override
        public boolean writeChunk(String chunkId, String content, float[] embedding) {
            text.put(chunkId, content);
            return true;
        }

        @Override
        public boolean delete(byte[] key) {
            return text.remove(new String(key, StandardCharsets.UTF_8)) != null;
        }
    }

    /** A source whose documents, and whose failures, the test decides. */
    private static final class StubSource implements Source {

        private final Map<String, String> documents = new LinkedHashMap<>();
        private final Set<String> unreadable = ConcurrentHashMap.newKeySet();

        void add(String documentId, String content) {
            documents.put(documentId, content);
        }

        void failOn(String documentId) {
            unreadable.add(documentId);
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
            return documents.keySet().stream();
        }

        @Override
        public boolean hasChanged(Instant since) {
            return true;
        }

        @Override
        public FetchedDocument fetch(String documentId) throws SourceException {
            if (unreadable.contains(documentId)) {
                throw new SourceException("Cannot read " + documentId);
            }
            String content = documents.get(documentId);
            if (content == null) {
                throw new SourceException("Document not found: " + documentId);
            }
            return new FetchedDocument(documentId, content.getBytes(StandardCharsets.UTF_8),
                "text/plain", System.currentTimeMillis());
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
