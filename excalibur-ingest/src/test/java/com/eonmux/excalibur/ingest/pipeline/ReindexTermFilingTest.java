package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.core.text.TermExtractor;
import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a reindex files the terms of the chunks it writes.
 *
 * <p>Under the segment each chunk's write landed in, as the writer reports it - the premise
 * stage 1 of the query pipeline orders on. A reindex that filed every chunk's terms under one
 * fixed segment, or none, left a trusted level missing a reindexed-in term, and every query for
 * that term searched the segment holding it last until the segment was next rebuilt.</p>
 */
@DisplayName("Reindex term filing")
class ReindexTermFilingTest {

    private static final String SOURCE = "filing";

    /** Words that chunk into several chunks, every one new to the filter. */
    private static final String TEXT =
        "zeppelin harbour lighthouse estuary cormorant ".repeat(400);

    @TempDir
    Path tempDir;

    private DocumentRegistry registry;
    private HierarchicalCuckooFilter filter;
    private VisibilityTracker visibility;

    @BeforeEach
    void setUp() {
        registry = new DocumentRegistry();
        filter = new HierarchicalCuckooFilter(100_000);
        visibility = new VisibilityTracker(new SegmentCatalog(tempDir.resolve("catalog")));
    }

    private static FetchedDocument document(String id) {
        return new FetchedDocument(id, TEXT.getBytes(StandardCharsets.UTF_8), "text/plain",
            System.currentTimeMillis());
    }

    private DocumentReindexer publishing(OffsetAwareStorageWriter writer,
                                         UpsertHandler.StorageAdapter adapter) {
        DocumentReindexer reindexer = new DocumentReindexer(new TextChunker(64, 8), null,
            new UpsertHandler(registry, filter, adapter), registry, writer,
            new DocumentBatchPublisher(visibility, registry, (CuckooFilter) null, () -> { },
                new ReentrantLock()));
        reindexer.setMembershipFilter(filter);
        return reindexer;
    }

    /**
     * Vouches for a segment's level as a completed rebuild would, over a segment holding one
     * earlier entry: a level covers its segment only once it holds something.
     */
    private void trust(long segmentId) {
        String key = String.valueOf(segmentId);
        filter.registerSegment(key);
        long generation = filter.beginSegmentRebuild(key);
        filter.insert(bytes("gannet"), key);
        assertThat(filter.completeSegmentRebuild(key, generation)).isTrue();
    }

    /** Whether stage 1 would search a segment last for a term. */
    private boolean searchedLast(String term, long segmentId) {
        String key = String.valueOf(segmentId);
        return filter.coversSegment(key)
            && !filter.mightContainInSegment(term.getBytes(StandardCharsets.UTF_8), key);
    }

    @Test
    @DisplayName("each chunk's terms are filed under the segment its own write landed in")
    void termsFollowEachChunksSegment() {
        SpreadingWriter writer = new SpreadingWriter();
        DocumentReindexer.DocumentResult result =
            publishing(writer, writer).reindexDocument(SOURCE, "doc-1", document("doc-1"));

        assertThat(result.getFailedChunks()).isZero();
        assertThat(writer.segments).hasSizeGreaterThan(1);
        assertThat(writer.segments.values()).as("the chunks span more than one segment")
            .contains(11L, 12L);
        writer.segments.forEach((chunkId, segment) -> {
            for (String term : TermExtractor.extractTerms(writer.text.get(chunkId))) {
                assertThat(filter.mightContainInSegment(
                        term.getBytes(StandardCharsets.UTF_8), String.valueOf(segment)))
                    .as("term %s of %s filed under segment %s", term, chunkId, segment)
                    .isTrue();
            }
        });
        assertThat(filter.getSegmentFilter("0"))
            .as("nothing is filed under a segment no chunk landed in").isNull();
    }

    @Test
    @DisplayName("a reindexed-in term does not leave its trusted segment searched last")
    void aTrustedSegmentIsNotSearchedLastForAReindexedTerm() {
        trust(11L);
        trust(12L);
        assertThat(searchedLast("zeppelin", 11L))
            .as("trusted, and without the term before the reindex").isTrue();

        SpreadingWriter writer = new SpreadingWriter();
        publishing(writer, writer).reindexDocument(SOURCE, "doc-1", document("doc-1"));

        for (long segment : writer.segments.values()) {
            assertThat(searchedLast("zeppelin", segment))
                .as("segment %s holds a chunk with the term", segment).isFalse();
        }
    }

    @Test
    @DisplayName("the direct write path files terms too")
    void theDirectPathFilesTerms() {
        DirectWriter writer = new DirectWriter(9L);
        DocumentReindexer reindexer = new DocumentReindexer(new TextChunker(64, 8), null,
            new UpsertHandler(registry, filter, writer), registry, writer);
        reindexer.setMembershipFilter(filter);
        trust(9L);

        DocumentReindexer.DocumentResult result =
            reindexer.reindexDocument(SOURCE, "doc-1", document("doc-1"));

        assertThat(result.getFailedChunks()).isZero();
        assertThat(searchedLast("zeppelin", 9L)).isFalse();
        assertThat(filter.mightContainInSegment(bytes("cormorant"), "9")).isTrue();
    }

    @Test
    @DisplayName("a writer that reports no segment has nothing filed, and nothing fails")
    void anUnroutedWriterFilesNothing() {
        Map<String, String> stored = new ConcurrentHashMap<>();
        OffsetAwareStorageWriter unrouted = (chunkId, content, embedding) -> {
            stored.put(chunkId, content);
            return stored.size();
        };
        UpsertHandler.StorageAdapter adapter =
            key -> stored.remove(new String(key, StandardCharsets.UTF_8)) != null;

        DocumentReindexer.DocumentResult result =
            publishing(unrouted, adapter).reindexDocument(SOURCE, "doc-1", document("doc-1"));

        assertThat(result.getFailedChunks()).isZero();
        assertThat(result.getSuccessfulChunks()).isPositive();
        assertThat(filter.getSegmentCount()).isZero();
    }

    @Test
    @DisplayName("a failed write files nothing for the chunk it did not write")
    void aFailedWriteFilesNothing() {
        OffsetAwareStorageWriter refusing = new OffsetAwareStorageWriter() {
            @Override
            public long writeChunkWithOffset(String chunkId, String content, float[] embedding) {
                return -1L;
            }

            @Override
            public ChunkPlacement writeChunk(String chunkId, String content, List<String> terms,
                                             float[] embedding) {
                return ChunkPlacement.FAILED;
            }
        };

        DocumentReindexer.DocumentResult result =
            publishing(refusing, key -> true).reindexDocument(SOURCE, "doc-1", document("doc-1"));

        assertThat(result.getFailedChunks()).isPositive();
        assertThat(filter.getSegmentCount()).isZero();
    }

    @Test
    @DisplayName("a basic writer's default placement carries no segment")
    void defaultPlacements() {
        DocumentReindexer.StorageWriter accepting = (chunkId, content, embedding) -> true;
        DocumentReindexer.StorageWriter refusing = (chunkId, content, embedding) -> false;

        assertThat(accepting.writeChunk("c", "text", List.of("text"), null).segmentKnown())
            .isFalse();
        assertThat(accepting.writeChunk("c", "text", List.of("text"), null).written()).isTrue();
        assertThat(refusing.writeChunk("c", "text", List.of("text"), null))
            .isEqualTo(ChunkPlacement.FAILED);
        assertThat(ChunkPlacement.unrouted(-3L)).isEqualTo(ChunkPlacement.FAILED);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Writes alternate chunks to segments 11 and 12, as a hash-routed engine spreads a
     * document's chunks, and reports each one's segment.
     */
    private static final class SpreadingWriter
            implements OffsetAwareStorageWriter, UpsertHandler.StorageAdapter {

        final Map<String, String> text = new ConcurrentHashMap<>();
        final Map<String, Long> segments = new ConcurrentHashMap<>();
        private long nextOffset;

        @Override
        public long writeChunkWithOffset(String chunkId, String content, float[] embedding) {
            throw new AssertionError("a reindex hands the writer its chunk's terms");
        }

        @Override
        public synchronized ChunkPlacement writeChunk(String chunkId, String content,
                                                      List<String> terms, float[] embedding) {
            int index = Integer.parseInt(chunkId.substring(chunkId.lastIndexOf(':') + 1));
            long segment = index % 2 == 0 ? 11L : 12L;
            text.put(chunkId, content);
            segments.put(chunkId, segment);
            return new ChunkPlacement(nextOffset++, segment);
        }

        @Override
        public boolean delete(byte[] key) {
            return text.remove(new String(key, StandardCharsets.UTF_8)) != null;
        }
    }

    /** A direct writer over one segment, reporting it. */
    private static final class DirectWriter
            implements DocumentReindexer.StorageWriter, UpsertHandler.StorageAdapter {

        private final long segment;

        DirectWriter(long segment) {
            this.segment = segment;
        }

        @Override
        public boolean writeChunk(String chunkId, String content, float[] embedding) {
            throw new AssertionError("a reindex hands the writer its chunk's terms");
        }

        @Override
        public ChunkPlacement writeChunk(String chunkId, String content, List<String> terms,
                                         float[] embedding) {
            return new ChunkPlacement(0L, segment);
        }

        @Override
        public boolean delete(byte[] key) {
            return true;
        }
    }
}
