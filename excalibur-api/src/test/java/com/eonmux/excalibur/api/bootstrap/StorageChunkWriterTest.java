package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.ingest.pipeline.ChunkPlacement;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.store.WriteLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
        @DisplayName("reports the segment the engine wrote to, with the chunk's terms handed over")
        void reportsTheSegmentTheEngineChose() {
            StorageEngine engine = mock(StorageEngine.class);
            List<String> terms = List.of("hello", "world");
            Segment landed = segmentWithOffset(12L);
            when(engine.putReportingSegment(any(byte[].class), any(byte[].class), eq(terms)))
                .thenReturn(37L);
            when(engine.getSegment(37L)).thenReturn(landed);

            ChunkPlacement placement = new StorageChunkWriter(engine)
                .writeChunk("doc-1:chunk:0", "hello world", terms, EMBEDDING);

            assertThat(placement.written()).isTrue();
            assertThat(placement.segmentId()).isEqualTo(37L);
            assertThat(placement.writeLogOffset()).isEqualTo(12L);
            verify(engine, never()).getSegmentCount();
        }

        @Test
        @DisplayName("reports failure when the engine refuses the write")
        void reportsEngineFailure() {
            StorageEngine engine = mock(StorageEngine.class);
            when(engine.putReportingSegment(any(byte[].class), any(byte[].class)))
                .thenThrow(new IllegalStateException("engine not started"));
            when(engine.putReportingSegment(any(byte[].class), any(byte[].class), anyList()))
                .thenThrow(new IllegalStateException("engine not started"));
            StorageChunkWriter writer = new StorageChunkWriter(engine);

            assertThat(writer.writeChunkWithOffset("doc-1:chunk:0", "hello", EMBEDDING))
                .isEqualTo(StorageChunkWriter.WRITE_FAILED);
            assertThat(writer.writeChunk("doc-1:chunk:0", "hello", List.of("hello"), EMBEDDING))
                .isEqualTo(ChunkPlacement.FAILED);
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
