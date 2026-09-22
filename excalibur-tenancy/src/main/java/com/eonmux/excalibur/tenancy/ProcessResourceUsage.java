package com.eonmux.excalibur.tenancy;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How many memory mappings and file descriptors the whole process holds right now.
 *
 * <p>The limits a {@link MappingBudget} works to - {@code vm.max_map_count} and
 * {@code RLIMIT_NOFILE} - are limits on the process, not on the graphs. The graphs are the part
 * of the process a budget can account for one by one, because they are what it opens and closes,
 * but they are not most of it: the storage engine maps every segment, each segment carries an
 * HNSW graph, the chunk index holds its own files, and the JVM maps its heap, its code cache and
 * every jar on the class path. A budget that compared the graphs alone against the process limit
 * would let the process reach that limit while believing it had room, and the first thing to fail
 * would be whichever allocation happened to come next, graph or not.</p>
 *
 * <p>So the budget asks the process what it holds, and treats everything that is not a graph as
 * already spent. This interface is that question, separate from the budget so that a test can
 * answer it without owning a process of a particular shape.</p>
 *
 * <h2>When the process cannot be asked</h2>
 * <p>Either figure may be unknown, and unknown is reported as empty rather than as zero: a zero
 * would tell the budget that the rest of the node holds nothing, which is the under-count this
 * exists to remove. Where it is empty the budget keeps the last figure it was given, or none, and
 * falls back on the headroom its high-water fraction leaves below the limit.</p>
 *
 * @author ExcaliburDB
 */
public interface ProcessResourceUsage {

    /**
     * Memory mappings the process holds now.
     *
     * @return The count, or empty where the platform does not say
     */
    OptionalLong mappings();

    /**
     * File descriptors the process holds now.
     *
     * @return The count, or empty where the platform does not say
     */
    OptionalLong descriptors();

    /**
     * A process that cannot be asked.
     *
     * <p>What a budget built with explicit limits uses, and what the budget behaves as on a
     * platform with neither {@code /proc} nor a JVM that counts its own descriptors.</p>
     *
     * @return Usage that is never known
     */
    static ProcessResourceUsage unmeasured() {
        return Unmeasured.INSTANCE;
    }

    /**
     * The running process, read from {@code /proc/self} where it exists.
     *
     * <p>Mappings are the lines of {@code /proc/self/maps}, one per mapping. Descriptors are the
     * entries of {@code /proc/self/fd}, and where that directory cannot be listed, the count the
     * JVM's own operating-system bean reports on a Unix platform that has no {@code /proc} - so a
     * descriptor count survives on macOS, where no mapping limit applies anyway.</p>
     *
     * @return Usage of the calling process
     */
    static ProcessResourceUsage ofThisProcess() {
        return new ProcFs(Path.of("/proc/self/maps"), Path.of("/proc/self/fd"),
            ProcFs::jvmDescriptorCount);
    }

    /**
     * Usage read from {@code /proc}-shaped files at chosen paths.
     *
     * <p>The seam a test drives: a maps file of known length and a directory of known entries are
     * a process of known shape.</p>
     *
     * @param maps                A file with one line per mapping
     * @param descriptorDirectory A directory with one entry per descriptor
     * @param descriptorFallback  Asked when the directory cannot be listed; returns a negative
     *                            number when it cannot answer either
     * @return Usage read from those paths
     */
    static ProcessResourceUsage fromProcFs(Path maps, Path descriptorDirectory,
                                           LongSupplier descriptorFallback) {
        return new ProcFs(maps, descriptorDirectory, descriptorFallback);
    }

    /** Usage that is never known. */
    enum Unmeasured implements ProcessResourceUsage {
        INSTANCE;

        @Override
        public OptionalLong mappings() {
            return OptionalLong.empty();
        }

        @Override
        public OptionalLong descriptors() {
            return OptionalLong.empty();
        }

        @Override
        public String toString() {
            return "unmeasured";
        }
    }

    /**
     * Usage read from {@code /proc/self}.
     *
     * @param maps                One line per mapping
     * @param descriptorDirectory One entry per open descriptor
     * @param descriptorFallback  Asked when the directory cannot be listed
     */
    record ProcFs(Path maps, Path descriptorDirectory, LongSupplier descriptorFallback)
            implements ProcessResourceUsage {

        private static final Logger LOGGER = LoggerFactory.getLogger(ProcFs.class);

        /** Read size for the maps file, which the kernel generates as it is read. */
        private static final int READ_BUFFER_BYTES = 64 * 1024;

        /**
         * Validates the three sources.
         *
         * @throws NullPointerException if any is null
         */
        public ProcFs {
            Objects.requireNonNull(maps, "maps must not be null");
            Objects.requireNonNull(descriptorDirectory, "descriptorDirectory must not be null");
            Objects.requireNonNull(descriptorFallback, "descriptorFallback must not be null");
        }

        @Override
        public OptionalLong mappings() {
            try (InputStream in = new BufferedInputStream(
                    Files.newInputStream(maps), READ_BUFFER_BYTES)) {
                return OptionalLong.of(countLines(in));
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("could not read {}", maps, e);
                return OptionalLong.empty();
            }
        }

        /**
         * Counts the entries of the descriptor directory.
         *
         * <p>Listing {@code /proc/self/fd} holds a descriptor of its own while it runs, and it
         * appears in its own listing. It is counted rather than subtracted: one descriptor too
         * many costs an eviction a fraction earlier, and subtracting it would be wrong for any
         * directory that is not the process's own.</p>
         */
        @Override
        public OptionalLong descriptors() {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(descriptorDirectory)) {
                long count = 0;
                for (Path ignored : entries) {
                    count++;
                }
                return OptionalLong.of(count);
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("could not list {}", descriptorDirectory, e);
            }
            long fallback = descriptorFallback.getAsLong();
            return fallback >= 0 ? OptionalLong.of(fallback) : OptionalLong.empty();
        }

        /**
         * Counts the lines of a stream, a final line without its newline included.
         *
         * <p>Counted as bytes rather than decoded as text: the maps file names every mapped
         * path, and on a node with tens of thousands of mappings decoding them would be most
         * of the cost of a question whose answer is only how many there are.</p>
         *
         * @param in The stream
         * @return How many lines it holds
         * @throws IOException if it cannot be read
         */
        static long countLines(InputStream in) throws IOException {
            byte[] buffer = new byte[READ_BUFFER_BYTES];
            long lines = 0;
            boolean openLine = false;
            int read;
            while ((read = in.read(buffer)) != -1) {
                for (int i = 0; i < read; i++) {
                    if (buffer[i] == '\n') {
                        lines++;
                        openLine = false;
                    } else {
                        openLine = true;
                    }
                }
            }
            return openLine ? lines + 1 : lines;
        }

        /**
         * The descriptor count the JVM reports on a Unix platform, or -1 where it has none.
         *
         * @return The count, or -1
         */
        static long jvmDescriptorCount() {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
                return unix.getOpenFileDescriptorCount();
            }
            return -1;
        }

        @Override
        public String toString() {
            return "procfs[" + maps + ", " + descriptorDirectory + "]";
        }
    }
}
