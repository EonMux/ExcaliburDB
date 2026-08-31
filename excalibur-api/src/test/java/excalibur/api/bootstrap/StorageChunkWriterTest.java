package excalibur.api.bootstrap;

import excalibur.ingest.registry.UpsertHandler;
import io.excalibur.store.Segment;
import io.excalibur.store.StorageEngine;
import io.excalibur.store.WriteLog;
import io.excalibur.store.hash.XxHash64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the reindex chunk writer.
 *
 * <p>The two writes have deliberately different routing: the segment-agnostic one must
 * go through the engine so the chunk lands where its key hashes and stays reachable by
 * key, while the segment-targeted one must address exactly the segment rolling
 * replacement created.</p>
 */
@DisplayName("StorageChunkWriter")
class StorageChunkWriterTest {

    private static final float[] EMBEDDING = {0.5f, -0.25f, 1.0f};

    @Nested
    @DisplayName("Hash-routed writes")
    class HashRoutedWrites {

        @Test
        @DisplayName("writes through the engine so the key routes to its own segment")
        void writesThroughEngine() {
            StorageEngine engine = mock(StorageEngine.class);
            Segment segment = segmentWithOffset(4096L);
            when(engine.getSegmentCount()).thenReturn(64);
            when(engine.getSegment(anyLong())).thenReturn(segment);

            long offset = new StorageChunkWriter(engine)
                .writeChunkWithOffset("doc-1:chunk:0", "hello", EMBEDDING);

            verify(engine).put(any(byte[].class), any(byte[].class));
            verify(segment, never()).put(any(byte[].class), any(byte[].class));
            assertThat(offset).isEqualTo(4096L);
        }

        @Test
        @DisplayName("does not pin every chunk to segment 0")
        void doesNotPinToSegmentZero() {
            StorageEngine engine = mock(StorageEngine.class);
            Segment routed = segmentWithOffset(1L);
            when(engine.getSegmentCount()).thenReturn(64);
            when(engine.getSegment(anyLong())).thenReturn(routed);
            StorageChunkWriter writer = new StorageChunkWriter(engine);

            // Find a chunk id that does not hash to index 0, then confirm the writer
            // resolves the segment that key actually routes to.
            String chunkId = null;
            int expectedIndex = 0;
            for (int i = 0; i < 512 && expectedIndex == 0; i++) {
                String candidate = "doc-" + i + ":chunk:0";
                int index = (int) (XxHash64.hash(candidate.getBytes(StandardCharsets.UTF_8)) & 63);
                if (index != 0) {
                    chunkId = candidate;
                    expectedIndex = index;
                }
            }
            assertThat(chunkId).as("a chunk id hashing off index 0").isNotNull();

            writer.writeChunkWithOffset(chunkId, "hello", EMBEDDING);

            verify(engine).getSegment(eq((long) expectedIndex));
        }

        @Test
        @DisplayName("reports failure when the engine refuses the write")
        void reportsEngineFailure() {
            StorageEngine engine = mock(StorageEngine.class);
            org.mockito.Mockito.doThrow(new IllegalStateException("engine not started"))
                .when(engine).put(any(byte[].class), any(byte[].class));

            long offset = new StorageChunkWriter(engine)
                .writeChunkWithOffset("doc-1:chunk:0", "hello", EMBEDDING);

            assertThat(offset).isEqualTo(StorageChunkWriter.WRITE_FAILED);
        }

        @Test
        @DisplayName("still returns a monotone offset when the segment has no write log")
        void monotoneFallbackOffsets() {
            StorageEngine engine = mock(StorageEngine.class);
            Segment segment = mock(Segment.class);
            when(segment.writeLog()).thenReturn(null);
            when(engine.getSegmentCount()).thenReturn(64);
            when(engine.getSegment(anyLong())).thenReturn(segment);
            StorageChunkWriter writer = new StorageChunkWriter(engine);

            long first = writer.writeChunkWithOffset("a", "x", EMBEDDING);
            long second = writer.writeChunkWithOffset("b", "y", EMBEDDING);

            assertThat(first).isNotNegative();
            assertThat(second).isGreaterThan(first);
        }
    }

    @Nested
    @DisplayName("Segment-targeted writes")
    class SegmentTargetedWrites {

        @Test
        @DisplayName("writes into the nominated segment, bypassing hash routing")
        void writesIntoTargetSegment() {
            StorageEngine engine = mock(StorageEngine.class);
            Segment target = segmentWithOffset(77L);
            when(engine.getSegment(9L)).thenReturn(target);

            long offset = new StorageChunkWriter(engine)
                .writeChunkWithOffset(9L, "doc-1:chunk:0", "hello", EMBEDDING);

            verify(target).put(any(byte[].class), any(byte[].class));
            verify(engine, never()).put(any(byte[].class), any(byte[].class));
            assertThat(offset).isEqualTo(77L);
        }

        @Test
        @DisplayName("reports failure when the target segment is gone")
        void reportsMissingSegment() {
            StorageEngine engine = mock(StorageEngine.class);
            when(engine.getSegment(9L)).thenReturn(null);

            long offset = new StorageChunkWriter(engine)
                .writeChunkWithOffset(9L, "doc-1:chunk:0", "hello", EMBEDDING);

            assertThat(offset).isEqualTo(StorageChunkWriter.WRITE_FAILED);
        }

        @Test
        @DisplayName("forSegment binds every write to that segment")
        void forSegmentBindsWrites() {
            StorageEngine engine = mock(StorageEngine.class);
            Segment target = segmentWithOffset(5L);
            when(engine.getSegment(3L)).thenReturn(target);

            new StorageChunkWriter(engine).forSegment(3L)
                .writeChunkWithOffset("doc-1:chunk:0", "hello", EMBEDDING);

            verify(target).put(any(byte[].class), any(byte[].class));
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
