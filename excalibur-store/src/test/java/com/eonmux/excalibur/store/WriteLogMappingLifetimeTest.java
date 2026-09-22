package com.eonmux.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lifetime of a write-ahead log's memory mapping.
 *
 * <p>Each log maps its file at full size, and a mapping is released only by closing the Arena
 * it was made in - there is no per-segment unmap. A log that mapped into an Arena belonging to
 * something longer-lived therefore held its address space until that Arena closed, which for
 * the storage engine means process exit. Segments are retired routinely, by splits and by
 * compaction, and each takes a log with it.</p>
 */
@DisplayName("Write log mapping lifetime")
class WriteLogMappingLifetimeTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("closing a log unmaps its file")
    void closeReleasesTheMapping() throws IOException {
        WriteLog log = new WriteLog(tempDir.resolve("closed.wal"));
        MemorySegment mapping = log.getNativeSegment();

        assertThat(mapping).isNotNull();
        assertThat(mapping.scope().isAlive()).isTrue();

        log.close();

        assertThat(mapping.scope().isAlive())
            .as("the mapping is gone once the log that owned it is closed")
            .isFalse();
    }

    @Test
    @DisplayName("rotation unmaps the log it replaced")
    void rotationReleasesThePreviousMapping() throws IOException {
        WriteLog log = new WriteLog(tempDir.resolve("rotated.wal"));
        try {
            log.appendPut("k".getBytes(), "v".getBytes());
            MemorySegment before = log.getNativeSegment();

            log.rotate();

            assertThat(before.scope().isAlive())
                .as("a rotation that keeps the old mapping accumulates one per rotation")
                .isFalse();
            assertThat(log.getNativeSegment().scope().isAlive()).isTrue();
        } finally {
            log.close();
        }
    }

    @Test
    @DisplayName("a rotated log has the capacity of a fresh one")
    void rotatedLogKeepsFullCapacity() throws IOException {
        long freshCapacity;
        WriteLog fresh = new WriteLog(tempDir.resolve("fresh.wal"));
        try {
            freshCapacity = fresh.fileSize();
        } finally {
            fresh.close();
        }

        WriteLog log = new WriteLog(tempDir.resolve("cycled.wal"));
        try {
            log.appendPut("k".getBytes(), "v".getBytes());
            log.rotate();

            // Rotation exists to give the log room again. Re-mapping it smaller would cap it
            // below the threshold that decides when it is full, so it would never rotate out
            // of that state.
            assertThat(log.fileSize()).isEqualTo(freshCapacity);
        } finally {
            log.close();
        }
    }

    @Test
    @DisplayName("a log stays usable after rotation")
    void rotatedLogStillAccepts() throws IOException {
        WriteLog log = new WriteLog(tempDir.resolve("reuse.wal"));
        try {
            log.appendPut("before".getBytes(), "1".getBytes());
            log.rotate();

            long lsn = log.appendPut("after".getBytes(), "2".getBytes());
            assertThat(lsn).isZero();
            assertThat(log.writeOffset()).isPositive();
        } finally {
            log.close();
        }
    }
}
