package com.eonmux.excalibur.graph.temporal;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemporalFilterTest {

    private static int day(int year, int month, int dayOfMonth) {
        return Math.toIntExact(LocalDate.of(year, month, dayOfMonth).toEpochDay());
    }

    @Test
    @DisplayName("intervals are half-open")
    void halfOpen() {
        int from = day(2020, 1, 1);
        int to = day(2021, 1, 1);

        assertThat(new TemporalFilter(from).admits(from, to))
            .as("the start day is inside").isTrue();
        assertThat(new TemporalFilter(to).admits(from, to))
            .as("the end day is outside").isFalse();
        assertThat(new TemporalFilter(to - 1).admits(from, to)).isTrue();
        assertThat(new TemporalFilter(from - 1).admits(from, to)).isFalse();
    }

    @Test
    @DisplayName("an open interval needs no special case at the comparison")
    void openInterval() {
        int from = day(2020, 1, 1);

        assertThat(new TemporalFilter(from).admits(from, TemporalFilter.OPEN)).isTrue();
        assertThat(new TemporalFilter(day(2999, 12, 31)).admits(from, TemporalFilter.OPEN))
            .as("OPEN is Integer.MAX_VALUE, which no real asOf reaches")
            .isTrue();
        assertThat(new TemporalFilter(from - 1).admits(from, TemporalFilter.OPEN)).isFalse();
    }

    @Test
    @DisplayName("ALL admits an interval that has already closed")
    void allAdmitsEverything() {
        assertThat(TemporalFilter.ALL.admits(day(2020, 1, 1), day(2020, 2, 1))).isTrue();
        assertThat(TemporalFilter.ALL.admits(Integer.MIN_VALUE, TemporalFilter.OPEN)).isTrue();
        assertThat(TemporalFilter.ALL.isUnbounded()).isTrue();
    }

    @Test
    @DisplayName("ALL is not the same as a far-future snapshot")
    void allIsNotFarFuture() {
        // The distinction that makes ALL worth having: a snapshot excludes what has ended,
        // ALL includes it.
        int from = day(2020, 1, 1);
        int to = day(2020, 2, 1);

        assertThat(TemporalFilter.ALL.admits(from, to)).isTrue();
        assertThat(new TemporalFilter(day(2100, 1, 1)).admits(from, to)).isFalse();
    }

    @Test
    @DisplayName("asOf converts a date to days since the epoch")
    void asOfDate() {
        assertThat(TemporalFilter.asOf(LocalDate.of(1970, 1, 1)).asOfDays()).isZero();
        assertThat(TemporalFilter.asOf(LocalDate.of(1970, 1, 2)).asOfDays()).isEqualTo(1);
        assertThat(TemporalFilter.asOf(LocalDate.of(1969, 12, 31)).asOfDays()).isEqualTo(-1);
    }

    @Test
    @DisplayName("now() is a snapshot, not unbounded")
    void nowIsASnapshot() {
        TemporalFilter now = TemporalFilter.now();
        assertThat(now.isUnbounded()).isFalse();
        assertThat(now.admits(day(2020, 1, 1), day(2020, 2, 1)))
            .as("a fact that ended in 2020 does not hold now")
            .isFalse();
        assertThat(now.admits(day(2020, 1, 1), TemporalFilter.OPEN)).isTrue();
    }

    @Test
    @DisplayName("a backwards interval is rejected at construction")
    void rejectsBackwardsInterval() {
        assertThatThrownBy(() -> TemporalFilter.checkInterval(day(2021, 1, 1), day(2020, 1, 1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be after");
    }

    @Test
    @DisplayName("an empty interval is rejected: no query could ever return it")
    void rejectsEmptyInterval() {
        int same = day(2020, 1, 1);
        assertThatThrownBy(() -> TemporalFilter.checkInterval(same, same))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("never visible");
    }

    @Test
    @DisplayName("a one-day interval is admitted on exactly one day")
    void singleDayInterval() {
        int from = day(2020, 6, 15);
        int to = from + 1;
        TemporalFilter.checkInterval(from, to);

        assertThat(new TemporalFilter(from).admits(from, to)).isTrue();
        assertThat(new TemporalFilter(from - 1).admits(from, to)).isFalse();
        assertThat(new TemporalFilter(to).admits(from, to)).isFalse();
    }
}
