package excalibur.integration;

import excalibur.ingest.pipeline.DocumentBatchBuffer;
import excalibur.ingest.processor.TextChunk;
import io.excalibur.core.codec.ChunkPayloadCodec;
import io.excalibur.store.WriteLog;
import io.excalibur.store.format.LogEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the write-log driver the integration tests ingest through.
 *
 * <p>The batches this writer returns are what the other integration tests assert document
 * atomicity over, so the two claims they rest on are pinned here: an offset recorded on a batch
 * really locates that chunk in the log, and one document's chunks occupy a run of the log that
 * no other document's writes fall inside - including when several threads ingest at once.</p>
 */
@DisplayName("AtomicDocumentWriter")
class AtomicDocumentWriterTest {

    @TempDir
    Path tempDir;

    private WriteLog writeLog;
    private AtomicDocumentWriter writer;

    @BeforeEach
    void setUp() throws IOException {
        writeLog = new WriteLog(tempDir.resolve("writer-test.log"));
        writer = new AtomicDocumentWriter(writeLog);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (writeLog != null) {
            writeLog.close();
        }
    }

    @Nested
    @DisplayName("A written document")
    class AWrittenDocument {

        @Test
        @DisplayName("records, for every chunk, the offset the chunk was actually written at")
        void offsetsLocateTheChunks() throws IOException {
            List<TextChunk> chunks = chunks("doc-located", 4);

            DocumentBatchBuffer batch = writer.write("doc-located", chunks);

            assertThat(batch.getChunkCount()).isEqualTo(4);
            for (DocumentBatchBuffer.ChunkEntry entry : batch.getEntries()) {
                LogEntry logged = writeLog.readEntryAt(entry.writeLogOffset());

                assertThat(logged)
                    .as("Offset %d must hold a log entry", entry.writeLogOffset())
                    .isNotNull();
                assertThat(new String(logged.key(), StandardCharsets.UTF_8))
                    .isEqualTo(entry.chunkId());
                assertThat(ChunkPayloadCodec.textOf(logged.value()))
                    .isEqualTo(entry.textChunk().getText());
            }
        }

        @Test
        @DisplayName("names its chunks the way the rest of the pipeline names them")
        void chunkIdsFollowTheConvention() throws IOException {
            DocumentBatchBuffer batch = writer.write("doc-named", chunks("doc-named", 3));

            assertThat(batch.getChunkIds())
                .containsExactly("doc-named:chunk:0", "doc-named:chunk:1", "doc-named:chunk:2");
        }

        @Test
        @DisplayName("occupies a strictly increasing run of the log, and is sealed for publication")
        void chunksAreWrittenInOrder() throws IOException {
            DocumentBatchBuffer batch = writer.write("doc-ordered", chunks("doc-ordered", 6));

            List<Long> offsets = batch.getEntries().stream()
                .map(DocumentBatchBuffer.ChunkEntry::writeLogOffset)
                .toList();

            assertThat(offsets).isSorted();
            assertThat(offsets).doesNotHaveDuplicates();
            assertThat(batch.getStartOffset()).isEqualTo(offsets.get(0));
            assertThat(batch.getEndOffset()).isEqualTo(offsets.get(offsets.size() - 1));
            assertThat(batch.isSealed())
                .as("A written document is ready to publish, not still open")
                .isTrue();
        }

        @Test
        @DisplayName("stores the embedding of any chunk that was given one")
        void embeddingsAreStored() throws IOException {
            List<TextChunk> chunks = chunks("doc-embedded", 2);
            Map<Integer, float[]> embeddings = new HashMap<>();
            embeddings.put(0, new float[] {0.25f, -0.5f, 1.0f});

            DocumentBatchBuffer batch = writer.write("doc-embedded", chunks, embeddings);

            LogEntry embedded = writeLog.readEntryAt(batch.getEntries().get(0).writeLogOffset());
            assertThat(ChunkPayloadCodec.embeddingOf(embedded.value(), 3))
                .containsExactly(0.25f, -0.5f, 1.0f);

            // A chunk with no embedding is a normal value, not a damaged one: its text is still
            // readable, and the reader is told there is no vector rather than handed a wrong one.
            LogEntry plain = writeLog.readEntryAt(batch.getEntries().get(1).writeLogOffset());
            assertThat(ChunkPayloadCodec.embeddingOf(plain.value())).isNull();
            assertThat(ChunkPayloadCodec.textOf(plain.value())).isEqualTo(chunks.get(1).getText());
        }

        @Test
        @DisplayName("with no chunks is refused rather than written as an empty run")
        void emptyDocumentIsRefused() {
            assertThatThrownBy(() -> writer.write("doc-empty", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("doc-empty");
        }
    }

    @Nested
    @DisplayName("Concurrent ingestion")
    class ConcurrentIngestion {

        @Test
        @DisplayName("never interleaves one document's chunks with another's")
        void documentRunsDoNotOverlap() throws Exception {
            int threads = 8;
            int documentsPerThread = 12;
            int chunksPerDocument = 5;

            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            ConcurrentLinkedQueue<DocumentBatchBuffer> written = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        start.await();
                        for (int d = 0; d < documentsPerThread; d++) {
                            String documentId = "doc-" + threadId + "-" + d;
                            written.add(writer.write(documentId, chunks(documentId, chunksPerDocument)));
                        }
                    } catch (Throwable e) {
                        failures.add(e);
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
            executor.shutdownNow();

            assertThat(failures).isEmpty();
            assertThat(written).hasSize(threads * documentsPerThread);

            List<DocumentBatchBuffer> byPosition = new ArrayList<>(written);
            byPosition.sort(Comparator.comparingLong(DocumentBatchBuffer::getStartOffset));

            for (DocumentBatchBuffer batch : byPosition) {
                assertThat(batch.getChunkCount()).isEqualTo(chunksPerDocument);
                for (DocumentBatchBuffer.ChunkEntry entry : batch.getEntries()) {
                    assertThat(entry.writeLogOffset())
                        .as("Chunk %s must lie inside its own document's run", entry.chunkId())
                        .isBetween(batch.getStartOffset(), batch.getEndOffset());
                }
            }

            for (int i = 1; i < byPosition.size(); i++) {
                DocumentBatchBuffer previous = byPosition.get(i - 1);
                DocumentBatchBuffer next = byPosition.get(i);
                assertThat(previous.getEndOffset())
                    .as("%s must finish before %s begins",
                        previous.getDocumentId(), next.getDocumentId())
                    .isLessThan(next.getStartOffset());
            }
        }
    }

    private static List<TextChunk> chunks(String documentId, int count) {
        List<TextChunk> chunks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String text = "chunk " + i + " of " + documentId;
            chunks.add(new TextChunk(documentId, text, text.length() / 4, i * 100, (i + 1) * 100, i));
        }
        return chunks;
    }
}
