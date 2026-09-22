package com.eonmux.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The configured compaction delay has to reach the worker that schedules compaction.
 *
 * <p>{@code excalibur.shard.compaction-delay-ms} was parsed, range-checked, shipped in the
 * example properties, documented in the README and echoed in a startup log line - and reached no
 * compaction code. An operator who set it to keep compaction off the back of a bulk load saw it
 * confirmed at startup and got the same schedule as before.</p>
 */
@DisplayName("Compaction start delay")
class CompactionStartDelayTest {

    @Test
    @DisplayName("the worker keeps the delay it was given")
    void theWorkerKeepsItsDelay() {
        try (Arena arena = Arena.ofShared()) {
            CompactionWorker worker = new CompactionWorker(arena, (oldSegment, newSegment) -> CompactionOutcome.INSTALLED);

            assertThat(worker.getStartDelayMs()).isZero();

            worker.withStartDelayMs(45_000L);

            assertThat(worker.getStartDelayMs()).isEqualTo(45_000L);
        }
    }

    @Test
    @DisplayName("a negative delay is treated as none")
    void aNegativeDelayIsNone() {
        try (Arena arena = Arena.ofShared()) {
            CompactionWorker worker = new CompactionWorker(arena, (oldSegment, newSegment) -> CompactionOutcome.INSTALLED);

            worker.withStartDelayMs(-1L);

            assertThat(worker.getStartDelayMs()).isZero();
        }
    }
}
