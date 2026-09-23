package com.eonmux.excalibur.graph.format;

import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.core.integrity.IntegrityStatus;
import com.eonmux.excalibur.graph.file.GraphCorruptionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The rules for checking an edge store's columns against its descriptor, shared by the store's
 * open and by any tool that surveys one.
 *
 * <h2>Verified in full, every time the store is opened</h2>
 * <p>The same policy the lexical segments and the HNSW graphs follow: every column is digested
 * before it is mapped, and one that no longer matches the digest the build recorded is refused,
 * named, and never served. A corrupt column cannot be caught any later - once it is mapped, a
 * flipped bit in the adjacency is an edge to the wrong entity and a flipped bit in an offset is
 * a run of somebody else's edges, and both read as answers. The cost is one sequential pass over
 * the store: memory bandwidth rather than CPU time, since CRC32C is a hardware instruction on
 * every CPU this runs on, and the pass leaves the pages the traversal will read in the page
 * cache. A namespace is opened when it is first used and again after it has been evicted, so a
 * node serving more namespaces than it keeps open pays the pass on each reopen - bounded by the
 * size of the graph, against a cost of serving wrong answers that is not.</p>
 *
 * <h2>Read-only</h2>
 * <p>Columns are streamed through a channel rather than mapped, so a store too damaged to serve,
 * or too large to map beside everything else, can still be judged, and nothing here writes. That
 * is what lets {@link #inspect} be pointed at a live data directory or a snapshot's payload.</p>
 *
 * <p>A store whose descriptor predates column digests is held to the sizes its counts imply and
 * reported as unprotected, never refused for the checksum it was not written with.</p>
 */
public final class EdgeStoreIntegrity {

    private static final Logger LOGGER = LoggerFactory.getLogger(EdgeStoreIntegrity.class);

    /** Name a column goes by in a refusal or a report. */
    public static final String COLUMN_FORMAT = "edge store column";

    private EdgeStoreIntegrity() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reads the descriptor and verifies every column against it, refusing a damaged store.
     *
     * <p>What {@code MappedEdgeStore.open} calls before it maps anything.</p>
     *
     * @param directory The store directory
     * @return The descriptor, every column of which matched it
     * @throws GraphCorruptionException if the descriptor or a column is missing, cut short or
     *         does not match its checksum
     * @throws EdgeStoreDescriptor.IncompatibleStoreException if the store was written by a build
     *         that made different format decisions
     * @throws IOException if a file cannot be read
     */
    public static EdgeStoreDescriptor requireIntact(Path directory) throws IOException {
        EdgeStoreDescriptor descriptor = EdgeStoreDescriptor.read(directory);
        for (EdgeColumn column : EdgeColumn.values()) {
            IntegrityReport report = verifyColumn(directory, descriptor, column);
            if (report.status() == IntegrityStatus.CORRUPT
                    || report.status() == IntegrityStatus.MISSING) {
                throw new GraphCorruptionException(report.file(), COLUMN_FORMAT + " "
                    + report.file() + " failed its integrity check: " + report.detail());
            }
            if (report.status() == IntegrityStatus.UNREADABLE) {
                throw new IOException(COLUMN_FORMAT + " " + report.file()
                    + " could not be read: " + report.detail());
            }
        }
        if (!descriptor.checksummed()) {
            LOGGER.warn("edge store at {} is format version {}, written before its columns "
                + "carried checksums: its sizes match its counts and nothing more can be shown. "
                + "It is derived from the namespace's staging logs, and the next build replaces "
                + "it with one whose every column is verified", directory,
                descriptor.formatVersion());
        }
        return descriptor;
    }

    /**
     * Reaches a verdict on the descriptor and every column, refusing nothing.
     *
     * <p>One report for the descriptor, then one per column in {@link EdgeColumn} order. When
     * the descriptor cannot be read, only its own report is returned: without it nothing says
     * what the columns should hold, and a verdict on them would be invented.</p>
     *
     * @param directory The store directory
     * @return The reports, never empty
     * @throws NullPointerException if {@code directory} is null
     */
    public static List<IntegrityReport> inspect(Path directory) {
        Objects.requireNonNull(directory, "directory must not be null");
        Path descriptorFile = directory.resolve(EdgeStoreDescriptor.FILE_NAME);
        EdgeStoreDescriptor descriptor;
        try {
            descriptor = EdgeStoreDescriptor.read(directory);
        } catch (GraphCorruptionException e) {
            IntegrityReport report = Files.exists(descriptorFile)
                ? IntegrityReport.corrupt(descriptorFile, EdgeStoreDescriptor.FORMAT,
                    e.getMessage())
                : IntegrityReport.missing(descriptorFile, EdgeStoreDescriptor.FORMAT);
            return List.of(report);
        } catch (EdgeStoreDescriptor.UnsupportedVersionException e) {
            return List.of(IntegrityReport.unsupportedVersion(descriptorFile,
                EdgeStoreDescriptor.FORMAT, e.version(), EdgeStoreDescriptor.OLDEST_VERSION,
                EdgeStoreDescriptor.CURRENT_VERSION));
        } catch (EdgeStoreDescriptor.IncompatibleStoreException e) {
            return List.of(IntegrityReport.unreadable(descriptorFile, EdgeStoreDescriptor.FORMAT,
                e.getCause().getMessage()));
        } catch (IOException e) {
            return List.of(IntegrityReport.unreadable(descriptorFile, EdgeStoreDescriptor.FORMAT,
                e.toString()));
        }

        List<IntegrityReport> reports = new ArrayList<>(EdgeColumn.values().length + 1);
        reports.add(descriptorReport(descriptorFile, descriptor));
        for (EdgeColumn column : EdgeColumn.values()) {
            reports.add(verifyColumn(directory, descriptor, column));
        }
        return List.copyOf(reports);
    }

    /**
     * Phrases what reading the descriptor proved.
     *
     * @param file       The descriptor
     * @param descriptor What it holds
     * @return The report
     */
    private static IntegrityReport descriptorReport(Path file, EdgeStoreDescriptor descriptor) {
        if (!descriptor.checksummed()) {
            return new IntegrityReport(file, EdgeStoreDescriptor.FORMAT,
                IntegrityStatus.LEGACY_UNPROTECTED, "format version "
                    + descriptor.formatVersion() + ", written before the descriptor and its "
                    + "columns carried checksums: its fields are well-formed, and nothing shows "
                    + "they are the ones written. The store is derived from the namespace's "
                    + "staging logs, and the next build replaces it with a checksummed one");
        }
        return IntegrityReport.intact(file, EdgeStoreDescriptor.FORMAT, "format version "
            + descriptor.formatVersion() + ": the checksum at its end matched every byte "
            + "before it, so the counts (" + descriptor.entityCount() + " entities, "
            + descriptor.edgeCount() + " edges) and the " + descriptor.digests().size()
            + " column digests are the ones the build wrote");
    }

    /**
     * Verifies one column against what the descriptor says it holds.
     *
     * @param directory  The store directory
     * @param descriptor The descriptor, already read
     * @param column     The column
     * @return The report on that column
     */
    static IntegrityReport verifyColumn(Path directory, EdgeStoreDescriptor descriptor,
                                        EdgeColumn column) {
        Path file = directory.resolve(column.fileName());
        if (!Files.isRegularFile(file)) {
            return new IntegrityReport(file, COLUMN_FORMAT, IntegrityStatus.MISSING,
                "the descriptor describes this column and the store does not hold it");
        }
        long expected = descriptor.byteSizeOf(column);
        long actual;
        try {
            actual = Files.size(file);
        } catch (IOException e) {
            return IntegrityReport.unreadable(file, COLUMN_FORMAT, e.toString());
        }
        if (actual != expected) {
            return IntegrityReport.corrupt(file, COLUMN_FORMAT, "the column is " + actual
                + " bytes where " + descriptor.entityCount() + " entities and "
                + descriptor.edgeCount() + " edges make it " + expected + ", so it has "
                + (actual < expected ? "lost its tail" : "been written past its end"));
        }
        if (!descriptor.checksummed()) {
            return new IntegrityReport(file, COLUMN_FORMAT, IntegrityStatus.LEGACY_UNPROTECTED,
                "its " + actual + " bytes are the size the counts imply; the descriptor is "
                    + "format version " + descriptor.formatVersion() + ", which records no "
                    + "checksum for it, so its contents are unverified until the next build "
                    + "replaces the store");
        }
        ColumnDigest recorded = descriptor.digestOf(column);
        long digest;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            digest = IndexChecksum.of(channel, 0, actual);
        } catch (IOException e) {
            return IntegrityReport.unreadable(file, COLUMN_FORMAT, e.toString());
        }
        if (digest != recorded.checksum()) {
            return IntegrityReport.corrupt(file, COLUMN_FORMAT, "the descriptor records "
                + "0x" + Long.toHexString(recorded.checksum()) + " over its " + actual
                + " bytes but they digest to 0x" + Long.toHexString(digest) + ", so the column "
                + "kept its length and lost its contents; every entity, relation, offset or "
                + "interval it decodes to may be a different number from the one built");
        }
        return IntegrityReport.intact(file, COLUMN_FORMAT, "the CRC32C over all " + actual
            + " bytes matched the digest the descriptor records");
    }
}
