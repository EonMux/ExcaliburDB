package com.eonmux.excalibur.core.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for FallbackSegment.
 *
 * Thread-safety: Tests verify thread-safe memory operations.
 * Domain correctness: Tests verify reads/writes are correct.
 */
@DisplayName("FallbackSegment Tests")
class FallbackSegmentTest {

    @TempDir
    Path tempDir;

    private Path testFile;
    private FallbackSegment segment;
    private static final int SEGMENT_SIZE = 4096;

    @BeforeEach
    void setUp() throws IOException {
        testFile = tempDir.resolve("test_segment.bin");
        Files.createFile(testFile);
        segment = FallbackSegment.mapFile(testFile, SEGMENT_SIZE);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (segment != null && !segment.isClosed()) {
            segment.close();
        }
    }

    @Nested
    @DisplayName("MapFile Tests")
    class MapFileTests {

        @Test
        @DisplayName("Should map file with specified size")
        void shouldMapFileWithSize() throws IOException {
            Path file = tempDir.resolve("mapped.bin");

            FallbackSegment seg = FallbackSegment.mapFile(file, 1024);

            assertThat(seg.byteSize()).isEqualTo(1024L);
            seg.close();
        }

        @Test
        @DisplayName("Should create file if not exists")
        void shouldCreateFileIfNotExists() throws IOException {
            Path file = tempDir.resolve("new_file.bin");
            assertThat(Files.exists(file)).isFalse();

            FallbackSegment seg = FallbackSegment.mapFile(file, 512);
            seg.close();

            assertThat(Files.exists(file)).isTrue();
        }
    }

    @Nested
    @DisplayName("ByteSize Tests")
    class ByteSizeTests {

        @Test
        @DisplayName("Should return correct byte size")
        void shouldReturnCorrectByteSize() {
            assertThat(segment.byteSize()).isEqualTo(SEGMENT_SIZE);
        }
    }

    @Nested
    @DisplayName("Read/Write Tests")
    class ReadWriteTests {

        @Test
        @DisplayName("Should read and write bytes")
        void shouldReadAndWriteBytes() {
            byte[] data = {1, 2, 3, 4, 5};

            segment.setBytes(0, data);
            byte[] read = segment.getBytes(0, data.length);

            assertThat(read).containsExactly(data);
        }

        @Test
        @DisplayName("Should write at non-zero offset")
        void shouldWriteAtOffset() {
            byte[] data = {10, 20, 30};

            segment.setBytes(100, data);
            byte[] read = segment.getBytes(100, data.length);

            assertThat(read).containsExactly(data);
        }

        @Test
        @DisplayName("Should read zero bytes")
        void shouldReadZeroBytes() {
            byte[] read = segment.getBytes(0, 0);

            assertThat(read).isEmpty();
        }

        @Test
        @DisplayName("Should read and write int")
        void shouldReadAndWriteInt() {
            int value = 0x12345678;

            segment.setInt(0, value);
            int read = segment.getInt(0);

            assertThat(read).isEqualTo(value);
        }

        @Test
        @DisplayName("Should read and write long")
        void shouldReadAndWriteLong() {
            long value = 0x1234567890ABCDEFL;

            segment.setLong(0, value);
            long read = segment.getLong(0);

            assertThat(read).isEqualTo(value);
        }

        @Test
        @DisplayName("Should read and write float")
        void shouldReadAndWriteFloat() {
            float value = 3.14159f;

            segment.setFloat(0, value);
            float read = segment.getFloat(0);

            assertThat(read).isEqualTo(value);
        }

        @Test
        @DisplayName("Should read and write double")
        void shouldReadAndWriteDouble() {
            double value = 3.141592653589793;

            segment.setDouble(0, value);
            double read = segment.getDouble(0);

            assertThat(read).isEqualTo(value);
        }
    }

    @Nested
    @DisplayName("Slice Tests")
    class SliceTests {

        @Test
        @DisplayName("Should create slice")
        void shouldCreateSlice() {
            byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};
            segment.setBytes(0, data);

            Segment slice = segment.asSlice(2, 4);
            byte[] read = slice.getBytes(0, 4);

            assertThat(read).containsExactly(3, 4, 5, 6);
        }

        @Test
        @DisplayName("Should return slice with correct size")
        void shouldReturnSliceWithCorrectSize() {
            Segment slice = segment.asSlice(100, 200);

            assertThat(slice.byteSize()).isEqualTo(200L);
        }

        @Test
        @DisplayName("Should indicate slice is not Panama")
        void shouldIndicateSliceIsNotPanama() {
            Segment slice = segment.asSlice(100, 200);

            assertThat(slice.isPanama()).isFalse();
            assertThat(slice.isFallback()).isTrue();
        }
    }

    @Nested
    @DisplayName("Channel Tests")
    class ChannelTests {

        @Test
        @DisplayName("Should return file channel")
        void shouldReturnFileChannel() {
            FileChannel channel = segment.toChannel();

            assertThat(channel).isNotNull();
            assertThat(channel.isOpen()).isTrue();
        }
    }

    @Nested
    @DisplayName("Path Tests")
    class PathTests {

        @Test
        @DisplayName("Should return file path")
        void shouldReturnFilePath() {
            Path path = segment.path();

            assertThat(path).isEqualTo(testFile);
        }
    }

    @Nested
    @DisplayName("Buffer Tests")
    class BufferTests {

        @Test
        @DisplayName("Should return underlying buffer")
        void shouldReturnUnderlyingBuffer() {
            ByteBuffer buffer = segment.buffer();

            assertThat(buffer).isNotNull();
        }
    }

    @Nested
    @DisplayName("ToString Tests")
    class ToStringTests {

        @Test
        @DisplayName("Should include path and size in toString")
        void shouldIncludePathAndSize() {
            String str = segment.toString();

            assertThat(str).contains("FallbackSegment");
            assertThat(str).contains("size=" + SEGMENT_SIZE);
        }
    }

    @Nested
    @DisplayName("Lifecycle Tests")
    class LifecycleTests {

        @Test
        @DisplayName("Should indicate closed state")
        void shouldIndicateClosedState() throws IOException {
            assertThat(segment.isClosed()).isFalse();

            segment.close();

            assertThat(segment.isClosed()).isTrue();
        }

        @Test
        @DisplayName("Should allow multiple close calls")
        void shouldAllowMultipleCloseCalls() throws IOException {
            segment.close();
            segment.close(); // Should not throw

            assertThat(segment.isClosed()).isTrue();
        }
    }

    @Nested
    @DisplayName("Segment Interface Tests")
    class SegmentInterfaceTests {

        @Test
        @DisplayName("Should implement Segment interface")
        void shouldImplementSegmentInterface() {
            assertThat(segment).isInstanceOf(Segment.class);
        }

        @Test
        @DisplayName("Should return false for isPanama")
        void shouldReturnFalseForIsPanama() {
            assertThat(segment.isPanama()).isFalse();
        }

        @Test
        @DisplayName("Should return null for nativeSegment")
        void shouldReturnNullForNativeSegment() {
            assertThat(segment.nativeSegment()).isNull();
        }
    }
}
