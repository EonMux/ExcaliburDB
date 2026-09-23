package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;
import com.eonmux.excalibur.query.QueryRequest;
import com.eonmux.excalibur.query.executor.CancellationToken;
import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.Segment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 0b: ordering candidate segments by the terms their own writes were counted from.
 */
@DisplayName("Segment prioritisation by term selectivity")
class SegmentPrioritizerTest {

    @TempDir
    Path dir;

    private Arena arena;
    private final List<Segment> opened = new ArrayList<>();

    @BeforeEach
    void setUp() {
        arena = Arena.ofShared();
    }

    @AfterEach
    void tearDown() {
        opened.forEach(Segment::close);
        arena.close();
    }

    private Segment segmentHolding(String name, String... chunks) throws IOException {
        Segment segment = new Segment(dir.resolve(name + ".dat"), 1 << 20, arena);
        opened.add(segment);
        for (int i = 0; i < chunks.length; i++) {
            segment.put((name + "-" + i).getBytes(StandardCharsets.UTF_8),
                ChunkPayloadCodec.encode(chunks[i], new float[] {0.5f, 0.5f}));
        }
        return segment;
    }

    private static SegmentCandidate candidate(long id, Segment segment) {
        return new SegmentCandidate(id, segment, null, LifecycleState.ACTIVE);
    }

    private static List<Long> order(String queryText, SegmentCandidate... candidates) {
        QueryRequest request = QueryRequest.builder()
            .queryVector(new float[] {0.5f, 0.5f})
            .queryText(queryText)
            .build();
        PipelineContext context = new PipelineContext(request, new CancellationToken(), 0L,
            List.of(candidates), 0);
        return SegmentPrioritizer.prioritize(context, new CancellationToken()).candidates.stream()
            .map(SegmentCandidate::segmentId)
            .toList();
    }

    @Test
    @DisplayName("the segment where the query's terms are densest is searched first, and none is dropped")
    void ordersByTheSegmentsOwnCounts() throws IOException {
        Segment sparse = segmentHolding("sparse",
            "gardening tips for spring", "vector search latency", "compost and soil");
        Segment dense = segmentHolding("dense",
            "vector search recall", "vector quantisation", "search latency budgets");

        assertThat(order("vector search", candidate(1, sparse), candidate(2, dense)))
            .containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("the query is cut the way the chunks were, punctuation and case included")
    void queryTermsAreTokenisedLikeTheChunks() throws IOException {
        Segment unrelated = segmentHolding("unrelated", "gardening tips for spring");
        Segment matching = segmentHolding("matching", "Retrieval pipelines, explained.");

        // Split on whitespace alone, "Retrieval," and "pipelines?" are terms no chunk was
        // counted under: every segment estimates zero and nothing moves.
        assertThat(order("Retrieval, pipelines?", candidate(1, unrelated), candidate(2, matching)))
            .containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("a segment with no sketch is assumed relevant and goes first")
    void segmentsWithoutASketchGoFirst() throws IOException {
        Segment counted = segmentHolding("counted", "vector search");
        Segment uncounted = segmentHolding("uncounted", "unrelated text");
        uncounted.detachTermFrequencySketch();

        assertThat(order("vector search", candidate(1, counted), candidate(2, uncounted)))
            .containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("a query of stop words alone reorders nothing")
    void stopWordsAloneLeaveTheOrder() throws IOException {
        Segment first = segmentHolding("first", "gardening tips");
        Segment second = segmentHolding("second", "the and of");

        assertThat(order("the and of", candidate(1, first), candidate(2, second)))
            .containsExactly(1L, 2L);
    }
}
