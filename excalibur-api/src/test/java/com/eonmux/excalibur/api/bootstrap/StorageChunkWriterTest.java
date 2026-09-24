package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.store.WriteLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the reindex chunk writer.
 *
 * <p>Every write goes through the engine, so the chunk lands where its key hashes and stays
 * reachable by key, and the segment reported for it is the one the engine says took it - the
 * segment its terms are then filed under.</p>
 */
@DisplayName("StorageChunkWriter")
class StorageChunkWriterTest {

    private static final float[] EMBEDDING = {0.5f, -0.25f, 1.0f};

    @Nested
    @DisplayName("Hash-routed writes")
    class HashRoutedWrites {

        @Test
        @DisplayName("writes through the engine and reads the offset of the segment it reports")
        void writesThroughEngine() {
            StorageEngine engine = mock(StorageEngine.class);
            Segment segment = segmentWithOffset(4096L);
            when(engine.putReportingSegment(any(byte[].class), any(byte[].class))).thenReturn(5L);
            when(engine.getSegment(5L)).thenReturn(segment);

            long offset = new StorageChunkWriter(engine)
                .writeChunkWithOffset("doc-1:chunk:0", "hello", EMBEDDING);

            verify(engine).putReportingSegment(any(byte[].class), any(byte[].class));
            verify(segment, never()).put(any(byte[].class), any(byte[].class));
            assertThat(offset).isEqualTo(4096L);
        }

        @Test
        @DisplayName("reports failure when the engine refuses the write")
        void reportsEngineFailure() {
            StorageEngine engine = mock(StorageEngine.class);
            when(engine.putReportingSegment(any(byte[].class), any(byte[].class)))
                .thenThrow(new IllegalStateException("engine not started"));
            StorageChunkWriter writer = new StorageChunkWriter(engine);

            assertThat(writer.writeChunkWithOffset("doc-1:chunk:0", "hello", EMBEDDING))
                .isEqualTo(StorageChunkWriter.WRITE_FAILED);
        }

        @Test
        @DisplayName("still returns a monotone offset when the segment has no write log")
        void monotoneFallbackOffsets() {
            StorageEngine engine = mock(StorageEngine.class);
            Segment segment = mock(Segment.class);
            when(segment.writeLog()).thenReturn(null);
            when(engine.putReportingSegment(any(byte[].class), any(byte[].class))).thenReturn(2L);
            when(engine.getSegment(anyLong())).thenReturn(segment);
            StorageChunkWriter writer = new StorageChunkWriter(engine);

            long first = writer.writeChunkWithOffset("a", "x", EMBEDDING);
            long second = writer.writeChunkWithOffset("b", "y", EMBEDDING);

            assertThat(first).isNotNegative();
            assertThat(second).isGreaterThan(first);
        }
    }

    @Nested
    @DisplayName("Durability")
    class Durability {

        /**
         * Atomic publication advances the visibility epoch without syncing anything, on the
         * strength of this: the write has been appended to the segment's write-ahead log and
         * forced to the device by the time the writer returns. The log advances its head only
         * after its fsync has returned, so a head that already covers the record is the proof.
         */
        @Test
        @DisplayName("returns only once the chunk's record is in the write-ahead log")
        void writeIsLoggedBeforeReturning(@TempDir Path tempDir) throws Exception {
            try (Arena arena = Arena.ofShared();
                 StorageEngine engine = new StorageEngine(tempDir, arena)) {
                engine.start();
                StorageChunkWriter writer = new StorageChunkWriter(engine);

                long offset = writer.writeChunkWithOffset("doc-1:chunk:0", "hello", EMBEDDING);

                List<WriteLog> logged = engine.getSegments().values().stream()
                    .map(Segment::writeLog)
                    .filter(Objects::nonNull)
                    .filter(log -> log.entriesAppended() > 0)
                    .distinct()
                    .toList();
                assertThat(logged)
                    .as("exactly one segment took the write, and it has a write log")
                    .hasSize(1);
                assertThat(logged.get(0).entriesAppended()).isEqualTo(1L);
                assertThat(offset)
                    .as("the offset reported is the log head just past the durable record")
                    .isEqualTo(logged.get(0).writeOffset())
                    .isPositive();
            }
        }
    }

    @Nested
    @DisplayName("Tombstone adapter")
    class TombstoneAdapter {

        @Test
        @DisplayName("actually deletes through the engine's routed delete")
        void deletesThroughEngine() {
            StorageEngine engine = mock(StorageEngine.class);
            byte[] key = "doc-1:chunk:0".getBytes(StandardCharsets.UTF_8);
            when(engine.delete(key)).thenReturn(true);

            UpsertHandler.StorageAdapter adapter = StorageChunkWriter.tombstoneAdapter(engine);

            assertThat(adapter.delete(key)).isTrue();
            verify(engine).delete(key);
        }

        @Test
        @DisplayName("reports false rather than propagating a storage failure")
        void survivesStorageFailure() {
            StorageEngine engine = mock(StorageEngine.class);
            when(engine.delete(any(byte[].class))).thenThrow(new IllegalStateException("closed"));

            assertThat(StorageChunkWriter.tombstoneAdapter(engine).delete("k".getBytes(StandardCharsets.UTF_8)))
                .isFalse();
        }
    }

    @Nested
    @DisplayName("Value encoding")
    class ValueEncoding {

        @Test
        @DisplayName("writes [dimension][floats][text] little-endian")
        void encodesChunkLayout() {
            byte[] value = StorageChunkWriter.encode("hi", EMBEDDING);

            ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
            assertThat(buffer.getInt()).isEqualTo(EMBEDDING.length);
            for (float expected : EMBEDDING) {
                assertThat(buffer.getFloat()).isEqualTo(expected);
            }
            byte[] text = new byte[buffer.remaining()];
            buffer.get(text);
            assertThat(new String(text, StandardCharsets.UTF_8)).isEqualTo("hi");
        }
    }

    private static Segment segmentWithOffset(long offset) {
        Segment segment = mock(Segment.class);
        WriteLog writeLog = mock(WriteLog.class);
        when(writeLog.writeOffset()).thenReturn(offset);
        when(segment.writeLog()).thenReturn(writeLog);
        return segment;
    }
}
