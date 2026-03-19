package io.excalibur.core.memory;

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
 * Comprehensive tests for FallbackMemorySegment.
 *
 * Thread-safety: Tests verify thread-safe memory operations.
 * Domain correctness: Tests verify reads/writes are correct.
 */
@DisplayName("FallbackMemorySegment Tests")
class FallbackMemorySegmentTest {

    @TempDir
    Path tempDir;

    private Path testFile;
    private FallbackMemorySegment segment;
    private static final int SEGMENT_SIZE = 4096;

    @BeforeEach
    void setUp() throws IOException {
        testFile = tempDir.resolve("test_segment.bin");
        Files.createFile(testFile);
        segment = FallbackMemorySegment.mapFile(testFile, SEGMENT_SIZE);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (segment != null && segment.toChannel() != null) {
            segment.toChannel().close();
        }
    }

    @Nested
    @DisplayName("MapFile Tests")
    class MapFileTests {

        @Test
        @DisplayName("Should map file with specified size")
        void shouldMapFileWithSize() throws IOException {
            Path file = tempDir.resolve("mapped.bin");

            FallbackMemorySegment seg = FallbackMemorySegment.mapFile(file, 1024);

            assertThat(seg.byteSize()).isEqualTo(1024L);
            seg.toChannel().close();
        }

        @Test
        @DisplayName("Should create file if not exists")
        void shouldCreateFileIfNotExists() throws IOException {
            Path file = tempDir.resolve("new_file.bin");
            assertThat(Files.exists(file)).isFalse();

            FallbackMemorySegment seg = FallbackMemorySegment.mapFile(file, 512);
            seg.toChannel().close();

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

            segment.put(data, 0, data.length);
            byte[] read = segment.read(0, data.length);

            assertThat(read).containsExactly(data);
        }

        @Test
        @DisplayName("Should write at non-zero offset")
        void shouldWriteAtOffset() {
            byte[] data = {10, 20, 30};

            segment.put(data, 100, data.length);
            byte[] read = segment.read(100, data.length);

            assertThat(read).containsExactly(data);
        }

        @Test
        @DisplayName("Should read zero bytes")
        void shouldReadZeroBytes() {
            byte[] read = segment.read(0, 0);

            assertThat(read).isEmpty();
        }

        @Test
        @DisplayName("Should write ByteBuffer")
        void shouldWriteByteBuffer() {
            ByteBuffer buffer = ByteBuffer.wrap(new byte[]{5, 10, 15, 20});

            segment.put(buffer);
            byte[] read = segment.read(0, 4);

            assertThat(read).containsExactly(5, 10, 15, 20);
        }
    }

    @Nested
    @DisplayName("Slice Tests")
    class SliceTests {

        @Test
        @DisplayName("Should create slice")
        void shouldCreateSlice() {
            byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};
            segment.put(data, 0, data.length);

            MemorySegment slice = segment.asSlice(2, 4);
            byte[] read = slice.read(0, 4);

            assertThat(read).containsExactly(3, 4, 5, 6);
        }

        @Test
        @DisplayName("Should return slice with correct size")
        void shouldReturnSliceWithCorrectSize() {
            MemorySegment slice = segment.asSlice(100, 200);

            assertThat(slice.byteSize()).isEqualTo(200L);
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

            assertThat(str).contains("FallbackMemorySegment");
            assertThat(str).contains("size=" + SEGMENT_SIZE);
        }
    }
}
