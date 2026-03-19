package io.excalibur.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Discovers existing segment files in the data directory.
 *
 * <p>This class is responsible for scanning the data directory on startup
 * to find existing segment files that were persisted from previous runs.
 * This enables recovery of data after restart.</p>
 *
 * <h2>Segment File Naming Convention</h2>
 * <p>Segment files follow the pattern: {@code segment_<index>.dat}</p>
 * <ul>
 *   <li>{@code segment_0.dat} - Segment at index 0</li>
 *   <li>{@code segment_1.dat} - Segment at index 1</li>
 *   <li>{@code segment_123.dat} - Segment at index 123</li>
 * </ul>
 *
 * <h2>Write Log Files</h2>
 * <p>Each segment may have an associated write-ahead log:</p>
 * <ul>
 *   <li>{@code segment_<index>.wal} - Write log for the segment</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe for read operations. Discovery should
 * typically be performed during initialization before concurrent access begins.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 * @see DiscoveredSegment
 * @see StorageEngine#start()
 */
public class SegmentDiscovery {

    private static final Logger LOGGER = Logger.getLogger(SegmentDiscovery.class.getName());

    /**
     * Pattern for matching segment file names.
     * Captures the segment index from filenames like "segment_0.dat" or "segment_123.dat".
     */
    private static final Pattern SEGMENT_FILE_PATTERN = Pattern.compile(
        "^segment_(\\d+)\\.dat$"
    );

    /**
     * Pattern for matching write-ahead log file names.
     * Captures the segment index from filenames like "segment_0.wal".
     */
    private static final Pattern WAL_FILE_PATTERN = Pattern.compile(
        "^segment_(\\d+)\\.wal$"
    );

    /**
     * The data directory to scan for segment files.
     */
    private final Path dataDir;

    /**
     * Creates a new SegmentDiscovery for the specified data directory.
     *
     * @param dataDir The data directory to scan (must not be null)
     * @throws IllegalArgumentException if dataDir is null
     */
    public SegmentDiscovery(Path dataDir) {
        if (dataDir == null) {
            throw new IllegalArgumentException("Data directory cannot be null");
        }
        this.dataDir = dataDir;
    }

    /**
     * Scans the data directory for existing segment files.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Lists all files in the data directory</li>
     *   <li>Filters for files matching the segment pattern</li>
     *   <li>Parses segment IDs from filenames</li>
     *   <li>Returns a list of discovered segments with their metadata</li>
     * </ol>
     *
     * <p>If the data directory does not exist or cannot be read, an empty
     * list is returned with a warning log.</p>
     *
     * @return List of discovered segments (never null, may be empty)
     */
    public List<DiscoveredSegment> scanDirectory() {
        List<DiscoveredSegment> discovered = new ArrayList<>();

        if (!Files.isDirectory(dataDir)) {
            LOGGER.log(Level.WARNING, "Data directory does not exist or is not a directory: {0}", dataDir);
            return discovered;
        }

        try (Stream<Path> files = Files.list(dataDir)) {
            files.filter(Files::isRegularFile)
                 .filter(this::isSegmentFile)
                 .forEach(path -> {
                     try {
                         DiscoveredSegment segment = parseSegmentFile(path);
                         if (segment != null) {
                             discovered.add(segment);
                         }
                     } catch (Exception e) {
                         LOGGER.log(Level.WARNING, "Failed to parse segment file: " + path, e);
                     }
                 });
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to scan data directory: " + dataDir, e);
            throw new UncheckedIOException("Failed to scan data directory: " + dataDir, e);
        }

        LOGGER.log(Level.INFO, "Discovered {0} segment(s) in {1}",
            new Object[] { discovered.size(), dataDir });

        return discovered;
    }

    /**
     * Scans for the write-ahead log file associated with a segment.
     *
     * <p>Write log files follow the pattern: {@code segment_<index>.wal}</p>
     *
     * @param segmentIndex The segment index to find the WAL for
     * @return Path to the WAL file, or null if it doesn't exist
     */
    public Path findWriteLogForSegment(long segmentIndex) {
        Path walPath = dataDir.resolve("segment_" + segmentIndex + ".wal");
        if (Files.exists(walPath) && Files.isRegularFile(walPath)) {
            return walPath;
        }
        return null;
    }

    /**
     * Checks if a path matches the segment file pattern.
     *
     * @param path The path to check
     * @return true if the filename matches the segment pattern
     */
    private boolean isSegmentFile(Path path) {
        String filename = path.getFileName().toString();
        return SEGMENT_FILE_PATTERN.matcher(filename).matches();
    }

    /**
     * Parses a segment file path to extract segment metadata.
     *
     * @param path The segment file path
     * @return DiscoveredSegment with extracted metadata, or null if parsing fails
     */
    private DiscoveredSegment parseSegmentFile(Path path) {
        String filename = path.getFileName().toString();
        Matcher matcher = SEGMENT_FILE_PATTERN.matcher(filename);

        if (!matcher.matches()) {
            return null;
        }

        try {
            long segmentIndex = Long.parseLong(matcher.group(1));
            long fileSize = Files.size(path);

            // Check for associated write log
            Path walPath = findWriteLogForSegment(segmentIndex);
            long walSize = walPath != null ? Files.size(walPath) : 0;

            return new DiscoveredSegment(segmentIndex, path, fileSize, walPath, walSize);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to get file size for: " + path, e);
            return null;
        } catch (NumberFormatException e) {
            LOGGER.log(Level.WARNING, "Failed to parse segment index from: " + filename, e);
            return null;
        }
    }

    /**
     * Returns the data directory being scanned.
     *
     * @return The data directory path
     */
    public Path getDataDir() {
        return dataDir;
    }

    /**
     * Metadata about a discovered segment file.
     *
     * <h2>Domain Correctness</h2>
     * <ul>
     *   <li>{@code segmentIndex}: Non-negative long representing the segment's position in the keyspace</li>
     *   <li>{@code path}: Absolute path to the segment file (guaranteed to exist at discovery time)</li>
     *   <li>{@code fileSize}: Positive long representing the segment file size in bytes</li>
     *   <li>{@code walPath}: Path to the write-ahead log, or null if no WAL exists</li>
     *   <li>{@code walSize}: Size of the WAL file in bytes, or 0 if no WAL exists</li>
     * </ul>
     */
    public static final class DiscoveredSegment {

        private final long segmentIndex;
        private final Path path;
        private final long fileSize;
        private final Path walPath;
        private final long walSize;

        /**
         * Creates a new DiscoveredSegment.
         *
         * @param segmentIndex The segment index (0-based position in keyspace)
         * @param path         The path to the segment file
         * @param fileSize     The size of the segment file in bytes
         * @param walPath      The path to the write-ahead log (may be null)
         * @param walSize      The size of the WAL file in bytes (0 if no WAL)
         */
        public DiscoveredSegment(long segmentIndex, Path path, long fileSize,
                                  Path walPath, long walSize) {
            if (segmentIndex < 0) {
                throw new IllegalArgumentException("Segment index cannot be negative: " + segmentIndex);
            }
            if (path == null) {
                throw new IllegalArgumentException("Path cannot be null");
            }
            if (fileSize < 0) {
                throw new IllegalArgumentException("File size cannot be negative: " + fileSize);
            }

            this.segmentIndex = segmentIndex;
            this.path = path;
            this.fileSize = fileSize;
            this.walPath = walPath;
            this.walSize = walSize;
        }

        /**
         * Returns the segment index.
         *
         * <p>The segment index is the 0-based position of this segment
         * in the keyspace. It determines which keys hash to this segment.</p>
         *
         * @return The segment index (non-negative)
         */
        public long segmentIndex() {
            return segmentIndex;
        }

        /**
         * Returns the path to the segment file.
         *
         * @return The segment file path
         */
        public Path path() {
            return path;
        }

        /**
         * Returns the size of the segment file in bytes.
         *
         * @return The file size
         */
        public long fileSize() {
            return fileSize;
        }

        /**
         * Returns the path to the write-ahead log file.
         *
         * @return The WAL path, or null if no WAL exists
         */
        public Path walPath() {
            return walPath;
        }

        /**
         * Returns the size of the write-ahead log file in bytes.
         *
         * @return The WAL size, or 0 if no WAL exists
         */
        public long walSize() {
            return walSize;
        }

        /**
         * Checks if this segment has an associated write-ahead log.
         *
         * @return true if a WAL file exists
         */
        public boolean hasWriteLog() {
            return walPath != null && walSize > 0;
        }

        /**
         * Estimates if the segment has entries based on file size.
         *
         * <p>A segment with no entries will have a minimum file size
         * (just the slot table). If the file size is larger than the
         * minimum, it likely contains entries.</p>
         *
         * @return true if the segment likely has entries
         */
        public boolean likelyHasEntries() {
            // Minimum segment size is approximately 1MB (slot table + minimum data region)
            // If file is larger than 1MB, it likely has data
            return fileSize > (1024 * 1024);
        }

        @Override
        public String toString() {
            return String.format(
                "DiscoveredSegment{index=%d, path=%s, size=%d, walPath=%s, walSize=%d}",
                segmentIndex, path, fileSize, walPath, walSize
            );
        }
    }
}
