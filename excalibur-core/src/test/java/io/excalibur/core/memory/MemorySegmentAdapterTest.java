package io.excalibur.core.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MemorySegmentAdapter}.
 *
 * <p>Tests verify that the adapter correctly delegates all operations
 * to the underlying Segment implementation.</p>
 */
@DisplayName("MemorySegmentAdapter Tests")
@SuppressWarnings("removal") // MemorySegmentAdapter is deprecated for removal
class MemorySegmentAdapterTest {

    private Segment mockDelegate;
    private MemorySegmentAdapter adapter;

    @BeforeEach
    void setUp() {
        mockDelegate = mock(Segment.class);
        adapter = new MemorySegmentAdapter(mockDelegate);
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create adapter with delegate")
        void shouldCreateAdapterWithDelegate() {
            // Verify adapter was created and delegates calls correctly
            when(mockDelegate.byteSize()).thenReturn(1024L);
            assertEquals(1024L, adapter.byteSize());
        }
    }

    @Nested
    @DisplayName("byteSize")
    class ByteSizeTests {

        @Test
        @DisplayName("Should delegate to underlying segment")
        void shouldDelegate() {
            when(mockDelegate.byteSize()).thenReturn(1024L);

            assertEquals(1024L, adapter.byteSize());
            verify(mockDelegate).byteSize();
        }
    }

    @Nested
    @DisplayName("asSlice")
    class AsSliceTests {

        @Test
        @DisplayName("Should delegate to underlying segment")
        void shouldDelegate() {
            Segment slice = mock(Segment.class);
            when(mockDelegate.asSlice(100, 200)).thenReturn(slice);

            assertSame(slice, adapter.asSlice(100, 200));
            verify(mockDelegate).asSlice(100, 200);
        }
    }

    @Nested
    @DisplayName("getByte/setByte")
    class ByteAccessTests {

        @Test
        @DisplayName("getByte should delegate to underlying segment")
        void getByteShouldDelegate() {
            when(mockDelegate.getByte(42)).thenReturn((byte) 0x7F);

            assertEquals((byte) 0x7F, adapter.getByte(42));
            verify(mockDelegate).getByte(42);
        }

        @Test
        @DisplayName("setByte should delegate to underlying segment")
        void setByteShouldDelegate() {
            adapter.setByte(42, (byte) 0x7F);

            verify(mockDelegate).setByte(42, (byte) 0x7F);
        }
    }

    @Nested
    @DisplayName("getBytes/setBytes")
    class ByteArrayAccessTests {

        @Test
        @DisplayName("getBytes should delegate to underlying segment")
        void getBytesShouldDelegate() {
            byte[] expected = new byte[]{1, 2, 3};
            when(mockDelegate.getBytes(100, 3)).thenReturn(expected);

            assertArrayEquals(expected, adapter.getBytes(100, 3));
            verify(mockDelegate).getBytes(100, 3);
        }

        @Test
        @DisplayName("setBytes should delegate to underlying segment")
        void setBytesShouldDelegate() {
            byte[] data = new byte[]{1, 2, 3, 4, 5};
            adapter.setBytes(100, data, 1, 3);

            verify(mockDelegate).setBytes(100, data, 1, 3);
        }
    }

    @Nested
    @DisplayName("getInt/setInt")
    class IntAccessTests {

        @Test
        @DisplayName("getInt should delegate to underlying segment")
        void getIntShouldDelegate() {
            when(mockDelegate.getInt(200)).thenReturn(0x12345678);

            assertEquals(0x12345678, adapter.getInt(200));
            verify(mockDelegate).getInt(200);
        }

        @Test
        @DisplayName("setInt should delegate to underlying segment")
        void setIntShouldDelegate() {
            adapter.setInt(200, 0x12345678);

            verify(mockDelegate).setInt(200, 0x12345678);
        }
    }

    @Nested
    @DisplayName("getLong/setLong")
    class LongAccessTests {

        @Test
        @DisplayName("getLong should delegate to underlying segment")
        void getLongShouldDelegate() {
            when(mockDelegate.getLong(300)).thenReturn(0x123456789ABCDEFL);

            assertEquals(0x123456789ABCDEFL, adapter.getLong(300));
            verify(mockDelegate).getLong(300);
        }

        @Test
        @DisplayName("setLong should delegate to underlying segment")
        void setLongShouldDelegate() {
            adapter.setLong(300, 0x123456789ABCDEFL);

            verify(mockDelegate).setLong(300, 0x123456789ABCDEFL);
        }
    }

    @Nested
    @DisplayName("getFloat/setFloat")
    class FloatAccessTests {

        @Test
        @DisplayName("getFloat should delegate to underlying segment")
        void getFloatShouldDelegate() {
            when(mockDelegate.getFloat(400)).thenReturn(3.14f);

            assertEquals(3.14f, adapter.getFloat(400), 0.001f);
            verify(mockDelegate).getFloat(400);
        }

        @Test
        @DisplayName("setFloat should delegate to underlying segment")
        void setFloatShouldDelegate() {
            adapter.setFloat(400, 3.14f);

            verify(mockDelegate).setFloat(400, 3.14f);
        }
    }

    @Nested
    @DisplayName("getDouble/setDouble")
    class DoubleAccessTests {

        @Test
        @DisplayName("getDouble should delegate to underlying segment")
        void getDoubleShouldDelegate() {
            when(mockDelegate.getDouble(500)).thenReturn(3.141592653589793);

            assertEquals(3.141592653589793, adapter.getDouble(500), 0.0001);
            verify(mockDelegate).getDouble(500);
        }

        @Test
        @DisplayName("setDouble should delegate to underlying segment")
        void setDoubleShouldDelegate() {
            adapter.setDouble(500, 3.141592653589793);

            verify(mockDelegate).setDouble(500, 3.141592653589793);
        }
    }

    @Nested
    @DisplayName("copyTo/copyFrom (byte array)")
    class CopyByteArrayTests {

        @Test
        @DisplayName("copyTo byte array should delegate to underlying segment")
        void copyToByteArrayShouldDelegate() {
            byte[] dst = new byte[100];
            adapter.copyTo(50, dst, 10, 20);

            verify(mockDelegate).copyTo(50, dst, 10, 20);
        }

        @Test
        @DisplayName("copyFrom byte array should delegate to underlying segment")
        void copyFromByteArrayShouldDelegate() {
            byte[] src = new byte[100];
            adapter.copyFrom(src, 10, 50, 20);

            verify(mockDelegate).copyFrom(src, 10, 50, 20);
        }
    }

    @Nested
    @DisplayName("copyTo/copyFrom (Segment)")
    class CopySegmentTests {

        @Test
        @DisplayName("copyTo Segment should delegate to underlying segment")
        void copyToSegmentShouldDelegate() {
            Segment dst = mock(Segment.class);
            adapter.copyTo(50, dst, 10, 20);

            verify(mockDelegate).copyTo(50, dst, 10, 20);
        }

        @Test
        @DisplayName("copyFrom Segment should delegate to underlying segment")
        void copyFromSegmentShouldDelegate() {
            Segment src = mock(Segment.class);
            adapter.copyFrom(src, 10, 50, 20);

            verify(mockDelegate).copyFrom(src, 10, 50, 20);
        }
    }

    @Nested
    @DisplayName("read/write (ByteBuffer)")
    class ByteBufferTests {

        @Test
        @DisplayName("read should delegate to underlying segment")
        void readShouldDelegate() {
            ByteBuffer buffer = ByteBuffer.allocate(100);
            adapter.read(50, buffer, 50);

            verify(mockDelegate).read(50, buffer, 50);
        }

        @Test
        @DisplayName("write should delegate to underlying segment")
        void writeShouldDelegate() {
            ByteBuffer buffer = ByteBuffer.allocate(100);
            adapter.write(50, buffer);

            verify(mockDelegate).write(50, buffer);
        }
    }

    @Nested
    @DisplayName("force")
    class ForceTests {

        @Test
        @DisplayName("force should delegate to underlying segment")
        void forceShouldDelegate() {
            adapter.force();

            verify(mockDelegate).force();
        }
    }

    @Nested
    @DisplayName("toChannel")
    class ToChannelTests {

        @Test
        @DisplayName("toChannel should delegate to underlying segment")
        void toChannelShouldDelegate() throws IOException {
            FileChannel channel = mock(FileChannel.class);
            when(mockDelegate.toChannel()).thenReturn(channel);

            assertSame(channel, adapter.toChannel());
            verify(mockDelegate).toChannel();
        }
    }

    @Nested
    @DisplayName("path")
    class PathTests {

        @Test
        @DisplayName("path should delegate to underlying segment")
        void pathShouldDelegate() {
            Path expected = Path.of("/test/path");
            when(mockDelegate.path()).thenReturn(expected);

            assertEquals(expected, adapter.path());
            verify(mockDelegate).path();
        }
    }

    @Nested
    @DisplayName("close/isClosed")
    class CloseTests {

        @Test
        @DisplayName("close should delegate to underlying segment")
        void closeShouldDelegate() throws IOException {
            adapter.close();

            verify(mockDelegate).close();
        }

        @Test
        @DisplayName("isClosed should delegate to underlying segment")
        void isClosedShouldDelegate() {
            when(mockDelegate.isClosed()).thenReturn(true);

            assertTrue(adapter.isClosed());
            verify(mockDelegate).isClosed();
        }
    }

    @Nested
    @DisplayName("nativeSegment")
    class NativeSegmentTests {

        @Test
        @DisplayName("nativeSegment should delegate to underlying segment")
        void nativeSegmentShouldDelegate() {
            java.lang.foreign.MemorySegment nativeSeg = java.lang.foreign.MemorySegment.NULL;
            when(mockDelegate.nativeSegment()).thenReturn(nativeSeg);

            assertSame(nativeSeg, adapter.nativeSegment());
            verify(mockDelegate).nativeSegment();
        }
    }

    @Nested
    @DisplayName("isPanama")
    class IsPanamaTests {

        @Test
        @DisplayName("isPanama should delegate to underlying segment")
        void isPanamaShouldDelegate() {
            when(mockDelegate.isPanama()).thenReturn(true);

            assertTrue(adapter.isPanama());
            verify(mockDelegate).isPanama();
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("toString should contain delegate reference")
        void toStringShouldContainDelegate() {
            when(mockDelegate.toString()).thenReturn("MockSegment");

            String result = adapter.toString();
            assertTrue(result.contains("MemorySegmentAdapter"));
            assertTrue(result.contains("MockSegment"));
        }
    }
}
