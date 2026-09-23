package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;
import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.store.hash.XxHash64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A reindex, wired as the node wires it, over a real storage engine whose segments' levels in
 * the membership filter are trusted.
 *
 * <p>Stage 1 of the query pipeline rules a segment out when its level is trusted and names none
 * of the query's terms. The reindexer used to file every chunk's terms under segment "0",
 * whichever segment the engine routed the chunk to, so a query for a term a reindex brought in
 * skipped the segment that held it until that segment was next rebuilt.</p>
 */
@DisplayName("Reindex term filing, end to end")
class ReindexTermFilingIntegrationTest {

    private static final String SOURCE = "docs";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("a term a reindex brings in is not pruned from the trusted segment holding it")
    void aReindexedTermIsNotPruned() throws Exception {
        Path dataDir = Files.createDirectories(tempDir.resolve("data"));
        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();
            int width = engine.getSegmentCount();

            // A document whose one chunk routes somewhere other than segment 0, and an entry
            // already in that segment, so the segment exists and its level can be vouched for.
            String documentId = documentRoutedAwayFromZero(width);
            byte[] chunkKey = key(ChunkIdentifiers.chunkId(SOURCE, documentId, 0));
            long slot = slotOf(chunkKey, width);
            engine.put(key(neighbourIn(slot, width)), ChunkPayloadCodec.encodeTextOnly("harbour"));

            HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(100_000);
            trust(filter, slot, "harbour");
            assertThat(pruned(filter, "zeppelin", slot))
                .as("the segment is trusted, and does not hold the term yet").isTrue();

            IndexOrchestrator orchestrator = mock(IndexOrchestrator.class);
            when(orchestrator.getVisibilityTracker())
                .thenReturn(new VisibilityTracker(engine.getCatalog()));
            DocumentReindexer reindexer = ReindexerFactory.create(new Config(new HashMap<>()),
                engine, null, orchestrator, filter, new DocumentRegistry(), null);

            DocumentReindexer.DocumentResult result = reindexer.reindexDocument(SOURCE,
                documentId, new FetchedDocument(documentId,
                    "zeppelin cormorant".getBytes(StandardCharsets.UTF_8), "text/plain",
                    System.currentTimeMillis()));

            assertThat(result.getFailedChunks()).isZero();
            assertThat(result.getSuccessfulChunks()).isEqualTo(1);
            assertThat(engine.getSegment(slot).get(chunkKey))
                .as("the chunk is in the segment its key routes to").isNotNull();
            assertThat(pruned(filter, "zeppelin", slot))
                .as("a query for the reindexed term reads the segment that holds it").isFalse();
            assertThat(filter.coversSegment(String.valueOf(slot)))
                .as("and the segment stays trusted, so pruning still works for it").isTrue();
            for (long other : engine.getSegments().keySet()) {
                if (other != slot && filter.getSegmentFilter(String.valueOf(other)) != null) {
                    assertThat(filter.mightContainInSegment(key("zeppelin"),
                            String.valueOf(other)))
                        .as("nothing was filed under segment %s, which holds no such chunk", other)
                        .isFalse();
                }
            }
        }
    }

    /**
     * Vouches for a segment's level as a completed rebuild over its one entry would. A level
     * only covers its segment once it holds something, so the entry's term is filed.
     */
    private static void trust(HierarchicalCuckooFilter filter, long segmentId, String heldTerm) {
        String key = String.valueOf(segmentId);
        filter.registerSegment(key);
        long generation = filter.beginSegmentRebuild(key);
        filter.insert(heldTerm.getBytes(StandardCharsets.UTF_8), key);
        assertThat(filter.completeSegmentRebuild(key, generation)).isTrue();
    }

    /** Whether stage 1 would rule the segment out for the term. */
    private static boolean pruned(HierarchicalCuckooFilter filter, String term, long segmentId) {
        String key = String.valueOf(segmentId);
        return filter.coversSegment(key)
            && !filter.mightContainInSegment(term.getBytes(StandardCharsets.UTF_8), key);
    }

    private static String documentRoutedAwayFromZero(int width) {
        for (int i = 0; i < 10_000; i++) {
            String candidate = "doc-" + i;
            if (slotOf(key(ChunkIdentifiers.chunkId(SOURCE, candidate, 0)), width) != 0) {
                return candidate;
            }
        }
        throw new AssertionError("no document id routes away from segment 0");
    }

    private static String neighbourIn(long slot, int width) {
        for (int i = 0; i < 100_000; i++) {
            String candidate = "neighbour-" + i;
            if (slotOf(key(candidate), width) == slot) {
                return candidate;
            }
        }
        throw new AssertionError("no key routes to segment " + slot);
    }

    /** The routing slot a key takes before any split, as the engine computes it. */
    private static long slotOf(byte[] key, int width) {
        return XxHash64.hash(key) & (width - 1);
    }

    private static byte[] key(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
