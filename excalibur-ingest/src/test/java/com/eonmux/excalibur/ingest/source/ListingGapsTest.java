package com.eonmux.excalibur.ingest.source;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListingGapsTest {

    @Test
    void anEmptyRecorderIsACompleteListing() {
        ListingGaps gaps = new ListingGaps.Recorder().snapshot();

        assertThat(gaps).isSameAs(ListingGaps.NONE);
        assertThat(gaps.isComplete()).isTrue();
        assertThat(gaps.count()).isZero();
        assertThat(gaps.describe()).isEmpty();
        assertThat(gaps).hasToString("ListingGaps[none]");
    }

    @Test
    void aPlaceMetTwiceIsOneGap() {
        ListingGaps.Recorder recorder = new ListingGaps.Recorder();
        recorder.record("https://example.com/hub", "links not read: HTTP 503");
        recorder.record("https://example.com/hub", "links not read: HTTP 500");

        ListingGaps gaps = recorder.snapshot();

        assertThat(gaps.count()).isEqualTo(1);
        assertThat(gaps.sample()).containsExactly(
            new ListingGaps.Gap("https://example.com/hub", "links not read: HTTP 503"));
        assertThat(gaps.describe()).isEqualTo(
            "1 place(s) could not be read: https://example.com/hub (links not read: HTTP 503)");
    }

    @Test
    void theCountIsWholeAndTheSampleBounded() {
        ListingGaps.Recorder recorder = new ListingGaps.Recorder();
        IntStream.range(0, ListingGaps.SAMPLE_SIZE + 5)
            .forEach(i -> recorder.record("page-" + i, "HTTP 503"));

        ListingGaps gaps = recorder.snapshot();

        assertThat(gaps.count()).isEqualTo(ListingGaps.SAMPLE_SIZE + 5);
        assertThat(gaps.sample()).hasSize(ListingGaps.SAMPLE_SIZE);
        assertThat(gaps.sample().getFirst().location()).isEqualTo("page-0");
        assertThat(gaps.describe()).startsWith("15 place(s) could not be read: page-0 (HTTP 503), ")
            .endsWith("page-9 (HTTP 503) and 5 more");
    }

    @Test
    void snapshotsAreValues() {
        ListingGaps.Recorder first = new ListingGaps.Recorder();
        first.record("a", "r");
        ListingGaps.Recorder second = new ListingGaps.Recorder();
        second.record("a", "r");

        assertThat(first.snapshot()).isEqualTo(second.snapshot())
            .hasSameHashCodeAs(second.snapshot())
            .isNotEqualTo(ListingGaps.NONE)
            .isNotEqualTo("a");
        assertThat(first.snapshot().toString()).contains("a (r)");
    }

    @Test
    void aGapNamesItsPlaceAndReason() {
        assertThatThrownBy(() -> new ListingGaps.Gap(null, "r"))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ListingGaps.Gap("a", null))
            .isInstanceOf(NullPointerException.class);
    }
}
