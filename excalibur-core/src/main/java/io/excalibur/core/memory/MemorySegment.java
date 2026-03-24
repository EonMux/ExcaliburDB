package io.excalibur.core.memory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Deprecated memory segment interface - use {@link Segment} instead.
 *
 * <p>This interface exists for backward compatibility only. All new code should
 * use the {@link Segment} interface directly, which provides the same functionality
 * with better support for both Panama FFM and fallback implementations.</p>
 *
 * <h2>Migration Guide</h2>
 * <p>Replace:</p>
 * <pre>
 * import io.excalibur.core.memory.MemorySegment;
 * </pre>
 * <p>With:</p>
 * <pre>
 * import io.excalibur.core.memory.Segment;
 * </pre>
 *
 * @author ExcaliburDB
 * @deprecated Use {@link Segment} instead. This interface will be removed in a future version.
 * @see Segment
 * @see MemorySegmentFactory
 */
@Deprecated(since = "1.1", forRemoval = true)
public interface MemorySegment extends Segment {

    /**
     * Maps a file into memory.
     *
     * @param path The path to the file
     * @param size The size of the mapping
     * @return A memory segment backed by the file
     * @throws IOException if mapping fails
     * @deprecated Use {@link MemorySegmentFactory#mapFile(Path, long)} instead
     */
    @Deprecated(since = "1.1", forRemoval = true)
    static MemorySegment mapFile(Path path, long size) throws IOException {
        Segment segment = MemorySegmentFactory.mapFile(path, size);
        if (segment instanceof MemorySegment) {
            return (MemorySegment) segment;
        }
        // Wrap FallbackSegment in a backward-compatible adapter
        return new MemorySegmentAdapter(segment);
    }
}
