package io.excalibur.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Factory for creating memory segments with automatic Panama FFM detection.
 *
 * <p>This factory detects at runtime whether Panama FFM (Foreign Function and Memory API)
 * is available and creates the appropriate Segment implementation:</p>
 * <ul>
 *   <li>{@link PanamaSegment} when Panama FFM is available (Java 21+ with native access)</li>
 *   <li>{@link FallbackSegment} when Panama FFM is unavailable (older JVMs, restricted environments)</li>
 * </ul>
 *
 * <h2>Detection Strategy</h2>
 * <p>The factory uses a multi-stage detection approach:</p>
 * <ol>
 *   <li>Check system property {@code excalibur.useFallback} - if true, use fallback</li>
 *   <li>Check if running on Java 21+ via runtime version</li>
 *   <li>Attempt to load {@code java.lang.foreign.MemorySegment} class</li>
 *   <li>Attempt a small memory allocation to verify native access permissions</li>
 * </ol>
 *
 * <h2>Configuration</h2>
 * <p>The following system properties control factory behavior:</p>
 * <ul>
 *   <li>{@code excalibur.useFallback} - Force fallback implementation (default: false)</li>
 *   <li>{@code excalibur.enableNativeAccessLog} - Log native access status (default: true)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The detection result is cached after first use.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a memory-mapped segment (automatically selects implementation)
 * Segment segment = MemorySegmentFactory.mapFile(path, 4096);
 *
 * // Check which implementation is being used
 * if (segment.isPanama()) {
 *     // Use Panama-specific features
 *     MemorySegment native = segment.nativeSegment();
 * } else {
 *     // Use fallback-safe operations only
 * }
 *
 * // Check availability before creating segments
 * if (MemorySegmentFactory.isPanamaAvailable()) {
 *     System.out.println("Using Panama FFM for optimal performance");
 * }
 * }</pre>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class MemorySegmentFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemorySegmentFactory.class);

    // System property names
    private static final String PROP_USE_FALLBACK = "excalibur.useFallback";
    private static final String PROP_ENABLE_LOG = "excalibur.enableNativeAccessLog";

    // Cached detection result
    private static volatile Boolean panamaAvailable = null;
    private static final Object detectionLock = new Object();

    // Private constructor - utility class
    private MemorySegmentFactory() {
    }

    /**
     * Checks if Panama FFM is available for use.
     *
     * <p>This method performs detection on first call and caches the result
     * for subsequent calls.</p>
     *
     * @return true if Panama FFM is available, false otherwise
     */
    public static boolean isPanamaAvailable() {
        if (panamaAvailable != null) {
            return panamaAvailable;
        }

        synchronized (detectionLock) {
            if (panamaAvailable != null) {
                return panamaAvailable;
            }

            panamaAvailable = detectPanamaAvailability();
            return panamaAvailable;
        }
    }

    /**
     * Performs the actual Panama FFM availability detection.
     */
    private static boolean detectPanamaAvailability() {
        boolean enableLog = Boolean.parseBoolean(System.getProperty(PROP_ENABLE_LOG, "true"));

        // Check if fallback is forced
        if (Boolean.getBoolean(PROP_USE_FALLBACK)) {
            if (enableLog) {
                LOGGER.info("Panama FFM disabled via system property excalibur.useFallback");
            }
            return false;
        }

        // Check Java version (Panama FFM requires Java 21+)
        int majorVersion = Runtime.version().feature();
        if (majorVersion < 21) {
            if (enableLog) {
                LOGGER.info("Panama FFM unavailable: Java 21+ required, running on Java {}", majorVersion);
            }
            return false;
        }

        // Check if MemorySegment class exists
        try {
            Class.forName("java.lang.foreign.MemorySegment");
        } catch (ClassNotFoundException e) {
            if (enableLog) {
                LOGGER.info("Panama FFM unavailable: java.lang.foreign.MemorySegment class not found");
            }
            return false;
        }

        // Check if Arena class exists
        try {
            Class.forName("java.lang.foreign.Arena");
        } catch (ClassNotFoundException e) {
            if (enableLog) {
                LOGGER.info("Panama FFM unavailable: java.lang.foreign.Arena class not found");
            }
            return false;
        }

        // Try to actually allocate native memory to verify permissions
        try {
            Arena testArena = Arena.ofConfined();
            MemorySegment testSegment = testArena.allocate(8, 8);
            testSegment.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, 0x1234567890ABCDEFL);
            long value = testSegment.get(ValueLayout.JAVA_LONG_UNALIGNED, 0);
            testArena.close();

            if (value != 0x1234567890ABCDEFL) {
                if (enableLog) {
                    LOGGER.warn("Panama FFM memory test failed: read/write mismatch");
                }
                return false;
            }
        } catch (Throwable t) {
            if (enableLog) {
                LOGGER.info("Panama FFM unavailable: native memory access denied - {}", t.getMessage());
            }
            return false;
        }

        if (enableLog) {
            LOGGER.info("Panama FFM available and enabled for memory-mapped I/O");
        }
        return true;
    }

    /**
     * Maps a file into memory, automatically selecting the appropriate implementation.
     *
     * <p>If Panama FFM is available, this method creates a {@link PanamaSegment}.
     * Otherwise, it creates a {@link FallbackSegment}.</p>
     *
     * @param path The path to the file
     * @param size The size of the mapping in bytes
     * @return A new Segment backed by the file
     * @throws IOException if mapping fails
     * @throws NullPointerException if path is null
     * @throws IllegalArgumentException if size is negative
     */
    public static Segment mapFile(Path path, long size) throws IOException {
        if (path == null) {
            throw new NullPointerException("Path cannot be null");
        }
        if (size < 0) {
            throw new IllegalArgumentException("Size cannot be negative: " + size);
        }

        if (isPanamaAvailable()) {
            return PanamaSegment.mapFile(path, size);
        } else {
            return FallbackSegment.mapFile(path, size);
        }
    }

    /**
     * Creates a native memory segment of the specified size.
     *
     * <p>If Panama FFM is available, this method allocates native memory using
     * a shared Arena. Otherwise, it creates a heap-backed ByteBuffer.</p>
     *
     * @param size The size in bytes
     * @return A new Segment backed by native or heap memory
     * @throws IllegalArgumentException if size is negative or too large
     */
    public static Segment allocateNative(long size) {
        if (size < 0) {
            throw new IllegalArgumentException("Size cannot be negative: " + size);
        }

        if (isPanamaAvailable()) {
            Arena arena = Arena.ofShared();
            MemorySegment nativeSegment = arena.allocate(size, 8);
            return PanamaSegment.wrapWithArena(nativeSegment, arena);
        } else {
            // Fallback uses direct ByteBuffer for better performance
            ByteBuffer buffer = ByteBuffer.allocateDirect((int) size);
            return new FallbackSegment(buffer, size);
        }
    }

    /**
     * Creates a segment from an existing file channel.
     *
     * @param channel The file channel
     * @param size The size of the mapping
     * @return A new Segment
     * @throws IOException if mapping fails
     */
    public static Segment mapChannel(FileChannel channel, long size) throws IOException {
        if (channel == null) {
            throw new NullPointerException("Channel cannot be null");
        }

        if (isPanamaAvailable()) {
            Arena arena = Arena.ofShared();
            MemorySegment nativeSegment = channel.map(FileChannel.MapMode.READ_WRITE, 0, size, arena);
            return PanamaSegment.wrapWithArena(nativeSegment, arena);
        } else {
            ByteBuffer buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, size);
            return new FallbackSegment(buffer, size);
        }
    }

    /**
     * Wraps an existing native MemorySegment (Panama FFM only).
     *
     * <p>This method is useful when you have a native MemorySegment from another
     * source and want to wrap it in the Segment abstraction.</p>
     *
     * @param nativeSegment The native MemorySegment to wrap
     * @return A new Segment wrapping the native segment
     * @throws UnsupportedOperationException if Panama FFM is unavailable
     * @throws NullPointerException if nativeSegment is null
     */
    public static Segment wrapNative(MemorySegment nativeSegment) {
        if (nativeSegment == null) {
            throw new NullPointerException("Native segment cannot be null");
        }

        if (!isPanamaAvailable()) {
            throw new UnsupportedOperationException(
                "Cannot wrap native MemorySegment: Panama FFM is unavailable");
        }

        return PanamaSegment.wrap(nativeSegment);
    }

    /**
     * Wraps a ByteBuffer as a Segment.
     *
     * <p>This method creates a read/write Segment view of the ByteBuffer.
     * Changes to the buffer are reflected in the segment and vice versa.</p>
     *
     * @param buffer The ByteBuffer to wrap
     * @return A new Segment backed by the buffer
     * @throws NullPointerException if buffer is null
     */
    public static Segment wrapBuffer(ByteBuffer buffer) {
        if (buffer == null) {
            throw new NullPointerException("Buffer cannot be null");
        }

        if (isPanamaAvailable()) {
            MemorySegment nativeSegment = MemorySegment.ofBuffer(buffer);
            return PanamaSegment.wrap(nativeSegment);
        } else {
            return new FallbackSegment(buffer, buffer.capacity());
        }
    }

    /**
     * Returns the name of the implementation currently in use.
     *
     * @return "Panama" if using Panama FFM, "Fallback" otherwise
     */
    public static String getImplementationName() {
        return isPanamaAvailable() ? "Panama" : "Fallback";
    }

    /**
     * Resets the cached detection result.
     *
     * <p>This method is primarily for testing purposes. After calling this
     * method, the next call to {@link #isPanamaAvailable()} will perform
     * detection again.</p>
     */
    static void resetDetection() {
        synchronized (detectionLock) {
            panamaAvailable = null;
        }
    }
}
