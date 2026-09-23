package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.filter.HierarchicalCuckooFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What filing a written chunk's terms leaves the membership filter saying about its segment.
 *
 * <p>The filter prunes a segment for a term only while its level is trusted. A filing that fails
 * part-way through a trusted level has recorded some of the chunk's terms and not the others, and
 * the segment has to stop being pruned until a rebuild refills it - otherwise a query for one of
 * the terms that were not recorded skips the segment that holds it.</p>
 */
@DisplayName("Segment term recorder")
class SegmentTermRecorderTest {

    private static final String SEGMENT = "7";

    /** A filter that refuses, by throwing, every insert of one term. */
    private static final class Refusing extends HierarchicalCuckooFilter {

        private final String refused;

        Refusing(String refused) {
            super(10_000);
            this.refused = refused;
        }

        @Override
        public boolean insert(byte[] item, String segmentId) {
            if (new String(item, StandardCharsets.UTF_8).equals(refused)) {
                throw new IllegalStateException("Segment not registered: " + segmentId);
            }
            return super.insert(item, segmentId);
        }
    }

    /** A level a rebuild has vouched for, holding one term. */
    private static void trust(HierarchicalCuckooFilter filter) {
        long generation = filter.beginSegmentRebuild(SEGMENT);
        filter.insert("lighthouse".getBytes(StandardCharsets.UTF_8), SEGMENT);
        assertThat(filter.completeSegmentRebuild(SEGMENT, generation)).isTrue();
        assertThat(filter.coversSegment(SEGMENT)).isTrue();
    }

    @Test
    @DisplayName("a filing that fails part-way takes the segment out of pruning")
    void aFailedFilingDistrustsTheSegment() {
        Refusing filter = new Refusing("cormorant");
        trust(filter);
        SegmentTermRecorder recorder = new SegmentTermRecorder();
        recorder.setFilter(filter);

        assertThatCode(() -> recorder.record(7, "doc#0",
            List.of("estuary", "cormorant", "harbour")))
            .as("filing is never a condition of the write")
            .doesNotThrowAnyException();

        assertThat(filter.mightContainInSegment(
            "cormorant".getBytes(StandardCharsets.UTF_8), SEGMENT))
            .as("the refused term is not in the level")
            .isFalse();
        assertThat(filter.coversSegment(SEGMENT))
            .as("so the level may no longer rule the segment out")
            .isFalse();
        assertThat(filter.isSegmentStale(SEGMENT)).isTrue();
    }

    @Test
    @DisplayName("a filing that succeeds leaves a trusted level trusted")
    void aSuccessfulFilingKeepsTrust() {
        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(10_000);
        trust(filter);
        SegmentTermRecorder recorder = new SegmentTermRecorder();
        recorder.setFilter(filter);

        recorder.record(7, "doc#0", List.of("estuary", "cormorant", "estuary"));

        assertThat(filter.coversSegment(SEGMENT)).isTrue();
        assertThat(filter.mightContainInSegment(
            "cormorant".getBytes(StandardCharsets.UTF_8), SEGMENT)).isTrue();
    }

    @Test
    @DisplayName("a node with no filter, or a chunk with no terms, files nothing")
    void nothingToFile() {
        SegmentTermRecorder recorder = new SegmentTermRecorder();
        assertThatCode(() -> recorder.record(7, "doc#0", List.of("estuary")))
            .doesNotThrowAnyException();

        HierarchicalCuckooFilter filter = new HierarchicalCuckooFilter(10_000);
        recorder.setFilter(filter);
        recorder.record(7, "doc#0", List.of());

        assertThat(filter.getSegmentCount()).isZero();
    }
}
