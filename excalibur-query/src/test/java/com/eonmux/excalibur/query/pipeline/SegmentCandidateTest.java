package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.WriteLog;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SegmentCandidate domain correctness.
 */
@DisplayName("SegmentCandidate")
class SegmentCandidateTest {

    private Segment mockSegment() {
        Segment segment = mock(Segment.class);
        when(segment.writeLog()).thenReturn(mock(WriteLog.class));
        return segment;
    }

    private Segment mockSegmentWithoutWriteLog() {
        Segment segment = mock(Segment.class);
        when(segment.writeLog()).thenReturn(null);
        return segment;
    }

    private HnswGraph mockGraph() {
        return mock(HnswGraph.class);
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("creates candidate with basic fields")
        void createsCandidateWithBasicFields() {
            Segment segment = mockSegment();
            HnswGraph graph = mockGraph();

            SegmentCandidate candidate = new SegmentCandidate(
                1L, segment, graph, LifecycleState.ACTIVE);

            assertThat(candidate.segmentId()).isEqualTo(1L);
            assertThat(candidate.segment()).isEqualTo(segment);
            assertThat(candidate.graph()).isEqualTo(graph);
            assertThat(candidate.state()).isEqualTo(LifecycleState.ACTIVE);
            assertThat(candidate.lshIndex()).isNull();
            assertThat(candidate.lshCandidates()).isEmpty();
        }

        @Test
        @DisplayName("creates candidate with LSH index")
        void createsCandidateWithLshIndex() {
            Segment segment = mockSegment();
            HnswGraph graph = mockGraph();

            SegmentCandidate candidate = new SegmentCandidate(
                1L, segment, graph, LifecycleState.INDEXING, null);

            assertThat(candidate.segmentId()).isEqualTo(1L);
            assertThat(candidate.lshIndex()).isNull();
        }

        @Test
        @DisplayName("handles null segment")
        void handlesNullSegment() {
            HnswGraph graph = mockGraph();

            SegmentCandidate candidate = new SegmentCandidate(
                1L, null, graph, LifecycleState.ACTIVE);

            assertThat(candidate.segment()).isNull();
            assertThat(candidate.isValid()).isFalse();
        }

        @Test
        @DisplayName("handles null graph")
        void handlesNullGraph() {
            Segment segment = mockSegment();

            SegmentCandidate candidate = new SegmentCandidate(
                1L, segment, null, LifecycleState.ACTIVE);

            assertThat(candidate.graph()).isNull();
            assertThat(candidate.isValid()).isFalse();
        }
    }

    @Nested
    @DisplayName("Validation methods")
    class ValidationMethods {

        @Test
        @DisplayName("isValid returns true when segment and graph are non-null")
        void isValidReturnsTrueWhenSegmentAndGraphNonNull() {
            Segment segment = mockSegment();
            HnswGraph graph = mockGraph();

            SegmentCandidate candidate = new SegmentCandidate(
                1L, segment, graph, LifecycleState.ACTIVE);

            assertThat(candidate.isValid()).isTrue();
        }

        @Test
        @DisplayName("isValid returns false when segment is null")
        void isValidReturnsFalseWhenSegmentNull() {
            HnswGraph graph = mockGraph();

            SegmentCandidate candidate = new SegmentCandidate(
                1L, null, graph, LifecycleState.ACTIVE);

            assertThat(candidate.isValid()).isFalse();
        }

        @Test
        @DisplayName("isValid returns false when graph is null")
        void isValidReturnsFalseWhenGraphNull() {
            Segment segment = mockSegment();

            SegmentCandidate candidate = new SegmentCandidate(
                1L, segment, null, LifecycleState.ACTIVE);

            assertThat(candidate.isValid()).isFalse();
        }

        @Test
        @DisplayName("isQueryable returns true for ACTIVE state")
        void isQueryableReturnsTrueForActiveState() {
            SegmentCandidate candidate = new SegmentCandidate(
                1L, mockSegment(), mockGraph(), LifecycleState.ACTIVE);

            assertThat(candidate.isQueryable()).isTrue();
        }

        @Test
        @DisplayName("isQueryable returns true for INDEXING state")
        void isQueryableReturnsTrueForIndexingState() {
            SegmentCandidate candidate = new SegmentCandidate(
                1L, mockSegment(), mockGraph(), LifecycleState.INDEXING);

            assertThat(candidate.isQueryable()).isTrue();
        }

        @Test
        @DisplayName("isQueryable returns false for TOMBSTONED state")
        void isQueryableReturnsFalseForTombstonedState() {
            SegmentCandidate candidate = new SegmentCandidate(
                1L, mockSegment(), mockGraph(), LifecycleState.TOMBSTONED);

            assertThat(candidate.isQueryable()).isFalse();
        }

        @Test
        @DisplayName("hasWriteLog returns true when segment has write log")
        void hasWriteLogReturnsTrueWhenSegmentHasWriteLog() {
            SegmentCandidate candidate = new SegmentCandidate(
                1L, mockSegment(), mockGraph(), LifecycleState.ACTIVE);

            assertThat(candidate.hasWriteLog()).isTrue();
        }

        @Test
        @DisplayName("hasWriteLog returns false when segment has no write log")
        void hasWriteLogReturnsFalseWhenNoWriteLog() {
            SegmentCandidate candidate = new SegmentCandidate(
                1L, mockSegmentWithoutWriteLog(), mockGraph(), LifecycleState.ACTIVE);

            assertThat(candidate.hasWriteLog()).isFalse();
        }

        @Test
        @DisplayName("hasWriteLog returns false when segment is null")
        void hasWriteLogReturnsFalseWhenSegmentNull() {
            SegmentCandidate candidate = new SegmentCandidate(
                1L, null, mockGraph(), LifecycleState.ACTIVE);

            assertThat(candidate.hasWriteLog()).isFalse();
        }
    }

    @Nested
    @DisplayName("LSH methods")
    class LshMethods {

        @Test
        @DisplayName("hasLshCandidates returns false when no candidates")
        void hasLshCandidatesReturnsFalseWhenNoCandidates() {
            SegmentCandidate candidate = new SegmentCandidate(
                1L, mockSegment(), mockGraph(), LifecycleState.ACTIVE);

            assertThat(candidate.hasLshCandidates()).isFalse();
        }

        @Test
        @DisplayName("withLshCandidates creates new instance with candidates")
        void withLshCandidatesCreatesNewInstance() {
            SegmentCandidate original = new SegmentCandidate(
                1L, mockSegment(), mockGraph(), LifecycleState.ACTIVE);

            Set<Long> candidates = Set.of(1L, 2L, 3L);
            SegmentCandidate updated = original.withLshCandidates(candidates);

            assertThat(updated.lshCandidates()).containsExactlyInAnyOrder(1L, 2L, 3L);
            assertThat(original.lshCandidates()).isEmpty(); // Original unchanged
            assertThat(updated.hasLshCandidates()).isTrue();
        }

        @Test
        @DisplayName("withLshCandidates handles null")
        void withLshCandidatesHandlesNull() {
            SegmentCandidate original = new SegmentCandidate(
                1L, mockSegment(), mockGraph(), LifecycleState.ACTIVE);

            SegmentCandidate updated = original.withLshCandidates(null);

            assertThat(updated.lshCandidates()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Object methods")
    class ObjectMethods {

        @Test
        @DisplayName("equals compares segmentId, segment, graph, and state")
        void equalsComparesFields() {
            Segment segment = mockSegment();
            HnswGraph graph = mockGraph();

            SegmentCandidate c1 = new SegmentCandidate(1L, segment, graph, LifecycleState.ACTIVE);
            SegmentCandidate c2 = new SegmentCandidate(1L, segment, graph, LifecycleState.ACTIVE);

            assertThat(c1).isEqualTo(c2);
            assertThat(c1.hashCode()).isEqualTo(c2.hashCode());
        }

        @Test
        @DisplayName("equals returns false for different segmentId")
        void equalsReturnsFalseForDifferentSegmentId() {
            Segment segment = mockSegment();
            HnswGraph graph = mockGraph();

            SegmentCandidate c1 = new SegmentCandidate(1L, segment, graph, LifecycleState.ACTIVE);
            SegmentCandidate c2 = new SegmentCandidate(2L, segment, graph, LifecycleState.ACTIVE);

            assertThat(c1).isNotEqualTo(c2);
        }

        @Test
        @DisplayName("toString contains segmentId, state, and valid")
        void toStringContainsKeyFields() {
            SegmentCandidate candidate = new SegmentCandidate(
                123L, mockSegment(), mockGraph(), LifecycleState.INDEXING);

            String str = candidate.toString();

            assertThat(str).contains("segmentId=123");
            assertThat(str).contains("state=INDEXING");
            assertThat(str).contains("valid=true");
        }
    }
}
