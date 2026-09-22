package com.eonmux.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The write-log compaction trigger has to fire inside the log it is meant to reclaim.
 *
 * <p>A write log is mapped once at a fixed capacity and never grown, and an append past that
 * capacity is refused for good. Compaction is the only thing that reclaims a log, and on an
 * append-only segment - one that is written to and never deleted from - the tombstone ratio is
 * zero forever, so the write-log size is the only trigger that can ever fire. A threshold at or
 * above the log's capacity therefore does not merely delay compaction: it removes the last
 * trigger, and the segment refuses writes permanently while telling the operator to enable a
 * compaction that by construction cannot run.</p>
 */
@DisplayName("Write-log size as a compaction trigger")
class WriteLogCompactionTriggerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("the highest configurable threshold still leaves room inside the log")
    void theCeilingFitsInsideTheLog() throws IOException {
        try (WriteLog log = new WriteLog(tempDir.resolve("segment_0.wal"))) {
            assertThat(CompactionWorker.MAX_WRITE_LOG_SIZE_THRESHOLD)
                .as("a trigger at or above the log's capacity can never fire, so the log fills "
                    + "and the segment stops accepting writes with no way back")
                .isLessThan(log.fileSize());
        }
    }

    @Test
    @DisplayName("the worker refuses a threshold the log can never reach")
    void theWorkerRefusesAThresholdTheLogCannotReach() throws IOException {
        long capacity;
        try (WriteLog log = new WriteLog(tempDir.resolve("segment_1.wal"))) {
            capacity = log.fileSize();
        }

        assertThatThrownBy(() -> new CompactionWorker(java.lang.foreign.Arena.ofAuto(),
            (oldSegment, newSegment) -> CompactionOutcome.INSTALLED,
            CompactionWorker.DEFAULT_COMPACTION_INTERVAL_MS,
            CompactionWorker.DEFAULT_TOMBSTONE_RATIO_THRESHOLD,
            capacity,
            CompactionWorker.DEFAULT_MAX_CONCURRENT_COMPACTIONS))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(String.valueOf(CompactionWorker.MAX_WRITE_LOG_SIZE_THRESHOLD));
    }
}
