package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.DocumentLimits;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.store.StorageEngine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One document's chunk count is bounded the same way on every path into the index.
 *
 * <p>Each chunk is an embedding request and a storage write, all of which have to succeed before
 * the document is published, so {@link DocumentLimits#MAX_CHUNKS_PER_DOCUMENT} bounds the work one
 * document can schedule. Bounded only on the first crawl, a document refused there came back as
 * "new" on the next re-crawl, which indexed it in full - the index then held a document its own
 * first pass said it could not take.</p>
 */
@DisplayName("The per-document chunk cap")
class ChunkCapTest {

    /** A one-token target, so a chunk is a few characters and the cap is cheap to reach. */
    private static final TextChunker TINY_CHUNKS = new TextChunker(1, 0);

    private static String overTheCap() {
        return "ab ".repeat(DocumentLimits.MAX_CHUNKS_PER_DOCUMENT * 2);
    }

    @Test
    @DisplayName("a first crawl counts a document over the cap as failed and writes none of it")
    void firstCrawlRefusesIt(@TempDir Path dir) throws Exception {
        assertThat(TINY_CHUNKS.chunk("k", overTheCap()))
            .as("precondition: the text chunks past the cap")
            .hasSizeGreaterThan(DocumentLimits.MAX_CHUNKS_PER_DOCUMENT);
        StorageEngine storage = new StorageEngine(Files.createDirectories(dir.resolve("data")));
        storage.start();
        try {
            IndexOrchestrator index = mock(IndexOrchestrator.class);
            when(index.awaitBackpressureRelease(anyInt())).thenReturn(true);
            IngestionOrchestrator orchestrator = new IngestionOrchestrator(TINY_CHUNKS, null,
                storage, index, sourceId -> new NoStatus());

            IngestionOrchestrator.IngestionOutcome outcome =
                orchestrator.ingestNow(new OneDocument("big.txt", overTheCap()));

            assertThat(outcome.documentsFailed()).isOne();
            assertThat(outcome.chunksIngested()).isZero();
            assertThat(storage.get("docs:big.txt:chunk:0".getBytes(StandardCharsets.UTF_8)))
                .isNull();
        } finally {
            storage.close();
        }
    }

    @Test
    @DisplayName("a re-crawl refuses the same document, leaving its entry as it was")
    void reindexRefusesItToo() {
        DocumentRegistry registry = new DocumentRegistry();
        UpsertHandler upsert = new UpsertHandler(registry, CuckooFilter.create(1_000), key -> true);
        DocumentReindexer reindexer = new DocumentReindexer(TINY_CHUNKS, null, upsert, registry,
            (chunkId, content, embedding) -> true);
        FetchedDocument document = new FetchedDocument("big.txt",
            overTheCap().getBytes(StandardCharsets.UTF_8), "text/plain", 0L);

        assertThatThrownBy(() -> reindexer.reindexDocument("docs", "big.txt", document))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(Integer.toString(DocumentLimits.MAX_CHUNKS_PER_DOCUMENT));
        assertThat(registry.contains("docs", "big.txt")).isFalse();
    }

    /** A source holding exactly one document. */
    private record OneDocument(String documentId, String text) implements Source {

        @Override
        public String getSourceId() {
            return "docs";
        }

        @Override
        public String getSourceType() {
            return "single";
        }

        @Override
        public Stream<String> crawl() {
            return Stream.of(documentId);
        }

        @Override
        public FetchedDocument fetch(String id) {
            return new FetchedDocument(id, text.getBytes(StandardCharsets.UTF_8), "text/plain");
        }
    }

    private static final class NoStatus implements IngestionOrchestrator.IngestionStatus {

        @Override
        public void update(String state, double progress, long ingested, long pending) {
        }

        @Override
        public void setSkipped(long skipped) {
        }

        @Override
        public void setDisallowed(long disallowed) {
        }

        @Override
        public void setError(String error) {
        }
    }
}
