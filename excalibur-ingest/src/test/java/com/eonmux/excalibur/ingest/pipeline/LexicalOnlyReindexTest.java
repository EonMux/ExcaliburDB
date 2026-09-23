package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.FetchDisallowedException;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Re-indexing on a node that never embedded its corpus.
 *
 * <p>A BM25-only deployment indexes at registration and then has no documented way to pick up a
 * later edit: the whole reindex was refused because half of it - the embedding half - could not
 * run. The half that can run is the half that produced the index in the first place, so it must
 * run on its own.</p>
 */
@DisplayName("Lexical-only reindex")
class LexicalOnlyReindexTest {

    private static final String SOURCE = "lexical-source";

    private TextChunker chunker;
    private DocumentRegistry registry;
    private RecordingWriter writer;
    private UpsertHandler upsertHandler;

    @BeforeEach
    void setUp() {
        chunker = new TextChunker(512, 64);
        registry = new DocumentRegistry();
        writer = new RecordingWriter();
        upsertHandler = new UpsertHandler(registry, CuckooFilter.create(10_000), writer);
    }

    private DocumentReindexer lexicalOnly() {
        return new DocumentReindexer(chunker, null, upsertHandler, registry, writer);
    }

    private static FetchedDocument document(String id, String text) {
        return new FetchedDocument(id, text.getBytes(StandardCharsets.UTF_8), "text/plain",
            System.currentTimeMillis());
    }

    @Test
    @DisplayName("a reindexer without an embedding client says so")
    void reportsThatItDoesNotEmbed() {
        assertThat(lexicalOnly().embedsChunks())
            .as("the caller decides what to report as the running phase from this")
            .isFalse();
    }

    @Test
    @DisplayName("one document is re-chunked and re-indexed with no embedding call")
    void indexesOneDocumentWithoutEmbedding() {
        DocumentReindexer.DocumentResult result = lexicalOnly()
            .reindexDocument(SOURCE, "doc-1", document("doc-1", "Chunk me. ".repeat(200)));

        assertThat(result.getFailedChunks()).isZero();
        assertThat(result.getSuccessfulChunks()).isPositive();
        assertThat(registry.contains(SOURCE, "doc-1"))
            .as("the run has to be recorded, or the next change detection re-does it forever")
            .isTrue();
        assertThat(writer.embeddingOf(SOURCE + ":doc-1:chunk:0"))
            .as("a chunk indexed lexically carries no vector; writing a fabricated one would "
                + "put a meaningless point in the vector index")
            .isNull();
    }

    @Test
    @DisplayName("a whole source is re-crawled and re-indexed with no embedding client")
    void reindexesAWholeSourceWithoutEmbedding() throws SourceException {
        StubSource source = new StubSource();
        source.add("doc-a", "The first document. ".repeat(50));
        source.add("doc-b", "The second document. ".repeat(50));

        DocumentReindexer.ReindexResult result = lexicalOnly().reindexAll(source);

        assertThat(result.getDocumentsIndexed()).isEqualTo(2);
        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    @DisplayName("a later edit is detected and re-indexed, which is the whole point")
    void picksUpALaterEdit() throws SourceException {
        DocumentReindexer reindexer = lexicalOnly();
        StubSource source = new StubSource();
        source.add("doc-a", "Original text. ".repeat(50));
        reindexer.reindexAll(source);

        source.add("doc-a", "Replacement text entirely. ".repeat(50));
        DocumentReindexer.ReindexResult second = reindexer.reindexAll(source);

        assertThat(second.getDocumentsIndexed()).isEqualTo(1);
        assertThat(writer.textOf(SOURCE + ":doc-a:chunk:0")).contains("Replacement text");
    }

    @Test
    @DisplayName("a listed document its publisher forbids fetching is left out, not counted as an error")
    void disallowedDocumentIsNotAnError() throws SourceException {
        StubSource source = new StubSource();
        source.add("doc-a", "The first document. ".repeat(50));
        source.add("doc-private", DISALLOWED);

        DocumentReindexer.ReindexResult result = lexicalOnly().reindexAll(source);

        assertThat(result.getDocumentsIndexed()).isEqualTo(1);
        assertThat(result.hasErrors())
            .as("a site saying no is not a reindex that failed")
            .isFalse();
        assertThat(registry.contains(SOURCE, "doc-private")).isFalse();
    }

    /** Content that makes {@link StubSource} refuse the fetch as its publisher would. */
    private static final String DISALLOWED = "!disallowed";

    /** A storage writer that keeps what it was handed, null embeddings included. */
    private static final class RecordingWriter
            implements DocumentReindexer.StorageWriter, UpsertHandler.StorageAdapter {

        private final Map<String, String> text = new ConcurrentHashMap<>();
        private final Map<String, float[]> embeddings = new ConcurrentHashMap<>();

        String textOf(String chunkId) {
            return text.get(chunkId);
        }

        float[] embeddingOf(String chunkId) {
            return embeddings.get(chunkId);
        }

        @Override
        public boolean writeChunk(String chunkId, String content, float[] embedding) {
            text.put(chunkId, content);
            if (embedding != null) {
                embeddings.put(chunkId, embedding.clone());
            }
            return true;
        }

        @Override
        public boolean delete(byte[] key) {
            return text.remove(new String(key, StandardCharsets.UTF_8)) != null;
        }
    }

    /** A source whose documents are whatever the test last put in it. */
    private static final class StubSource implements Source {

        private final Map<String, String> documents = new LinkedHashMap<>();

        void add(String documentId, String content) {
            documents.put(documentId, content);
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
        public FetchedDocument fetch(String documentId) throws SourceException {
            String content = documents.get(documentId);
            if (content == null) {
                throw new SourceException("Document not found: " + documentId);
            }
            if (DISALLOWED.equals(content)) {
                throw new FetchDisallowedException(documentId, SOURCE, "robots.txt disallows it");
            }
            return document(documentId, content);
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
