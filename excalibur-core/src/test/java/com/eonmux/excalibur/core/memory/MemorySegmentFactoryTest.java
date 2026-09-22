package com.eonmux.excalibur.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for MemorySegmentFactory runtime detection and segment creation.
 *
 * <h2>Thread Safety</h2>
 * <p>All tests are thread-safe.</p>
 */
@DisplayName("MemorySegmentFactory Tests")
class MemorySegmentFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Should detect Panama FFM availability on Java 21+")
    void shouldDetectPanamaAvailability() {
        // On Java 21+, Panama should be available unless explicitly disabled
        boolean available = MemorySegmentFactory.isPanamaAvailable();

        // This should be true on Java 21+ unless --Dcom.eonmux.excalibur.useFallback=true
        assertThat(available).isTrue();
    }

    @Test
    @DisplayName("Should return correct implementation name")
    void shouldReturnCorrectImplementationName() {
        String name = MemorySegmentFactory.getImplementationName();

        // Should be "Panama" on Java 21+
        assertThat(name).isIn("Panama", "Fallback");

        // Verify consistency with availability check
        if (MemorySegmentFactory.isPanamaAvailable()) {
            assertThat(name).isEqualTo("Panama");
        } else {
            assertThat(name).isEqualTo("Fallback");
        }
    }

    @Test
    @DisplayName("Should map file to segment")
    void shouldMapFileToSegment() throws IOException {
        Path file = tempDir.resolve("test_map.bin");

        Segment segment = MemorySegmentFactory.mapFile(file, 4096);

        assertThat(segment).isNotNull();
        assertThat(segment.byteSize()).isEqualTo(4096L);
        assertThat(segment.isClosed()).isFalse();

        // Verify it's the correct implementation type
        if (MemorySegmentFactory.isPanamaAvailable()) {
            assertThat(segment.isPanama()).isTrue();
            assertThat(segment.nativeSegment()).isNotNull();
        } else {
            assertThat(segment.isFallback()).isTrue();
        }

        segment.close();
        assertThat(segment.isClosed()).isTrue();
    }

    @Test
    @DisplayName("Should read and write through segment abstraction")
    void shouldReadAndWriteThroughSegment() throws IOException {
        Path file = tempDir.resolve("test_rw.bin");

        try (Segment segment = MemorySegmentFactory.mapFile(file, 1024)) {
            // Write some data
            segment.setInt(0, 0x12345678);
            segment.setLong(4, 0xABCDEFL);
            segment.setFloat(12, 3.14f);
            segment.setDouble(16, 2.71828);
            segment.setByte(24, (byte) 0xFF);

            // Force to disk
            segment.force();

            // Read it back
            assertThat(segment.getInt(0)).isEqualTo(0x12345678);
            assertThat(segment.getLong(4)).isEqualTo(0xABCDEFL);
            assertThat(segment.getFloat(12)).isEqualTo(3.14f);
            assertThat(segment.getDouble(16)).isEqualTo(2.71828);
            assertThat(segment.getByte(24)).isEqualTo((byte) 0xFF);
        }
    }

    @Test
    @DisplayName("Should create segment slice")
    void shouldCreateSegmentSlice() throws IOException {
        Path file = tempDir.resolve("test_slice.bin");

        try (Segment segment = MemorySegmentFactory.mapFile(file, 1024)) {
            // Write some data
            segment.setInt(0, 0x11111111);
            segment.setInt(100, 0x22222222);
            segment.setInt(200, 0x33333333);

            // Create a slice
            Segment slice = segment.asSlice(100, 100);

            assertThat(slice.byteSize()).isEqualTo(100L);
            assertThat(slice.getInt(0)).isEqualTo(0x22222222);

            // Slice should be same implementation type
            assertThat(slice.isPanama()).isEqualTo(segment.isPanama());
        }
    }

    @Test
    @DisplayName("Should copy between segments")
    void shouldCopyBetweenSegments() throws IOException {
        Path file = tempDir.resolve("test_copy.bin");

        try (Segment segment = MemorySegmentFactory.mapFile(file, 1024)) {
            // Write source data
            byte[] sourceData = {1, 2, 3, 4, 5, 6, 7, 8};
            segment.setBytes(0, sourceData);

            // Copy to another location
            segment.copyTo(0, segment, 100, 8);

            // Verify copy
            byte[] destData = segment.getBytes(100, 8);
            assertThat(destData).containsExactly(sourceData);
        }
    }

    @Test
    @DisplayName("Should handle byte array operations")
    void shouldHandleByteArrayOperations() throws IOException {
        Path file = tempDir.resolve("test_bytes.bin");

        try (Segment segment = MemorySegmentFactory.mapFile(file, 1024)) {
            byte[] data = new byte[100];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) i;
            }

            // Write using setBytes
            segment.setBytes(0, data);

            // Read back using getBytes
            byte[] read = segment.getBytes(0, data.length);
            assertThat(read).containsExactly(data);

            // Test partial read
            byte[] partial = segment.getBytes(50, 25);
            assertThat(partial).startsWith(50, 51, 52, 53, 54);
        }
    }

    @Test
    @DisplayName("Should reject invalid parameters")
    void shouldRejectInvalidParameters() {
        assertThatThrownBy(() -> MemorySegmentFactory.mapFile(null, 1024))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> MemorySegmentFactory.mapFile(tempDir.resolve("test"), -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should allocate native memory")
    void shouldAllocateNativeMemory() {
        Segment segment = MemorySegmentFactory.allocateNative(1024);

        try {
            assertThat(segment).isNotNull();
            assertThat(segment.byteSize()).isEqualTo(1024L);

            // Verify it works
            segment.setInt(0, 0x12345678);
            assertThat(segment.getInt(0)).isEqualTo(0x12345678);
        } finally {
            segment.close();
        }
    }
}
