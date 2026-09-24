package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.DocumentRefusedException;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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
            new UpsertHandler(registry, writer), registry, writer);
    }

    /** Everything a run published, in order. */
    private static final class Reported implements DocumentReindexer.ReindexProgress {

        private final List<long[]> pairs = new ArrayList<>();

        /** Each (failed, not text) pair, as {@code failed/notText}. */
        private final List<String> counted = new ArrayList<>();

        /** Each report of unchecked documents, as {@code count:sample}. */
        private final List<String> unchecked = new ArrayList<>();

        @Override
        public void documentsDone(long documentsProcessed, long documentsTotal) {
            pairs.add(new long[] {documentsProcessed, documentsTotal});
        }

        @Override
        public void documentsCounted(long documentsFailed, long documentsNotText) {
            counted.add(documentsFailed + "/" + documentsNotText);
        }

        @Override
        public void documentsUnchecked(long documentsUnchecked, List<String> sample) {
            unchecked.add(documentsUnchecked + ":" + sample);
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
    @DisplayName("what the run fails on is counted as it goes, not only at the end")
    void failuresAreCountedAsTheRunGoes() throws SourceException {
        StubSource source = new StubSource();
        source.add("doc-a", "A readable document. ".repeat(60));
        source.add("doc-b", "An unreadable document. ".repeat(60));
        source.failOn("doc-b");
        Reported reported = new Reported();

        DocumentReindexer.ReindexResult result = reindexer.reindexAll(source, reported);

        assertThat(reported.counted)
            .as("once before the first document, then once after each")
            .containsExactly("0/0", "0/0", "1/0");
        assertThat(result.getDocumentsFailed()).isEqualTo(1);
        assertThat(result.getDocumentsUnchecked()).isZero();
        assertThat(reported.unchecked).containsExactly("0:[]");
    }

    @Test
    @DisplayName("a document its source could not be asked about is a failure, and stays indexed")
    void anUncheckedDocumentIsAFailure() throws SourceException {
        StubSource source = new StubSource();
        source.add("doc-a", "A document that stays the same. ".repeat(60));
        source.add("doc-b", "A document whose host goes down. ".repeat(60));
        reindexer.reindexAll(source);
        source.failOn("doc-b");
        Reported reported = new Reported();

        DocumentReindexer.ReindexResult result = reindexer.reindexAll(source, reported);

        assertThat(result.getDocumentsUnchecked()).isEqualTo(1);
        assertThat(result.getUncheckedSample()).containsExactly("doc-b");
        assertThat(result.getDocumentsFailed())
            .as("a check that could not be made is a fetch that failed")
            .isEqualTo(1);
        assertThat(result.getDocumentsUnchanged())
            .as("doc-a was checked and found current; doc-b was not checked at all")
            .isEqualTo(1);
        assertThat(result.getErrors())
            .extracting(DocumentReindexer.ReindexResult.OperationError::getComponent)
            .containsExactly("check");
        assertThat(reported.unchecked).containsExactly("1:[doc-b]");
        assertThat(reported.counted).containsExactly("1/0");
        assertThat(registry.get(SOURCE, "doc-b"))
            .as("not knowing whether it changed is no reason to drop what is indexed")
            .isNotNull();
    }

    @Test
    @DisplayName("a document its source refuses is a failure with its reason, not unchecked")
    void aRefusedDocumentIsAFailureNotUnchecked() throws SourceException {
        StubSource source = new StubSource();
        source.add("doc-a", "A document that stays the same. ".repeat(60));
        source.add("doc-b", "A document that grows past the limit. ".repeat(60));
        reindexer.reindexAll(source);
        source.refuse("doc-b");
        Reported reported = new Reported();

        DocumentReindexer.ReindexResult result = reindexer.reindexAll(source, reported);

        assertThat(result.getDocumentsFailed()).isEqualTo(1);
        assertThat(result.getDocumentsUnchecked())
            .as("the source answered for it; it was not left unasked")
            .isZero();
        assertThat(result.getDocumentsUnchanged()).isEqualTo(1);
        assertThat(result.getErrors())
            .extracting(DocumentReindexer.ReindexResult.OperationError::getComponent)
            .containsExactly("fetch");
        assertThat(reported.unchecked).containsExactly("0:[]");
        assertThat(registry.get(SOURCE, "doc-b"))
            .as("the version indexed before stays, as for any document a run fails on")
            .isNotNull();
    }

    @Test
    @DisplayName("the documents named as unchecked are a bounded sample of them")
    void theUncheckedSampleIsBounded() {
        DocumentReindexer.ReindexResult.Builder builder =
            DocumentReindexer.ReindexResult.builder(SOURCE);
        int count = DocumentReindexer.ReindexResult.UNCHECKED_SAMPLE_SIZE + 5;
        for (int i = 0; i < count; i++) {
            builder.addUnchecked("doc-" + i);
        }

        DocumentReindexer.ReindexResult result = builder.build();

        assertThat(result.getDocumentsUnchecked()).isEqualTo(count);
        assertThat(result.getDocumentsFailed()).isEqualTo(count);
        assertThat(result.getErrorCount()).isEqualTo(count);
        assertThat(result.getUncheckedSample())
            .hasSize(DocumentReindexer.ReindexResult.UNCHECKED_SAMPLE_SIZE)
            .startsWith("doc-0", "doc-1");
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
        private final Set<String> refused = ConcurrentHashMap.newKeySet();

        void add(String documentId, String content) {
            documents.put(documentId, content);
        }

        void failOn(String documentId) {
            unreadable.add(documentId);
        }

        void refuse(String documentId) {
            refused.add(documentId);
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
            if (unreadable.contains(documentId)) {
                throw new SourceException("Cannot read " + documentId);
            }
            if (refused.contains(documentId)) {
                throw new DocumentRefusedException("File too large: " + documentId, SOURCE);
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
