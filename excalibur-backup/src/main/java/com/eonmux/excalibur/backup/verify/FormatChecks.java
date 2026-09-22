package com.eonmux.excalibur.backup.verify;

import com.eonmux.excalibur.backup.io.SparseFileCopy;
import com.eonmux.excalibur.filter.lsh.LshHashTable;
import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.store.Segment;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.SegmentIntegrityReport;
import com.eonmux.excalibur.store.catalog.CatalogFile;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.NodeDocumentMapping;
import com.eonmux.excalibur.vector.quantization.PQCodebookFile;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Hands each file in a snapshot to the engine's own checker for its format.
 *
 * <h2>Why not re-derive what a valid file looks like</h2>
 * <p>Each of these formats already has a verifier that lives beside the code that writes
 * it: {@link Segment#verifyIntegrity()} walks every occupied slot and compares each entry's
 * stored checksum; {@link HnswGraph#verifyIntegrity(Path)} and
 * {@link NodeDocumentMapping#verifyIntegrity(Path)} check the seal a clean close writes;
 * {@link PQCodebookFile#verifyIntegrity(Path)} and
 * {@link LshHashTable#verifyIntegrity(Path)} do the same for their sidecars; and
 * {@link CatalogFile} validates its own header and record CRCs as it opens. A second
 * implementation here would be a second definition of "valid", and the two would drift.</p>
 *
 * <h2>Why every check runs on a copy</h2>
 * <p>Several of these open their file read-write and repair it as they go - a catalog with
 * one damaged header slot is rewritten from the other, an interrupted HNSW compaction is
 * finished. That is right for a node opening its own data directory and wrong for a
 * verification, which must leave the image exactly as it found it, not least because it
 * has just certified the image's checksums. So each file is copied to scratch, checked
 * there and thrown away. The copy preserves holes, so checking a 755 MiB graph that holds
 * a few nodes costs what those few nodes cost.</p>
 *
 * @author ExcaliburDB
 */
public final class FormatChecks {

    /** The segment catalog, which is a {@code .dat} that is not a segment. */
    private static final String CATALOG_NAME = "catalog.dat";

    private FormatChecks() {
    }

    /**
     * Checks one file, if this build knows a checker for its format.
     *
     * @param payloadRoot  The payload root, so the report can name the file as the manifest
     *                     does
     * @param file         The file inside the payload
     * @param relativePath Its manifest path
     * @return What the format said about itself, or empty if no checker applies
     * @throws IOException          if scratch space cannot be created
     * @throws NullPointerException if any argument is null
     */
    public static Optional<ContentCheck> check(Path payloadRoot, Path file, String relativePath)
        throws IOException {
        Objects.requireNonNull(payloadRoot, "payloadRoot");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(relativePath, "relativePath");
        String format = formatOf(relativePath);
        if (format == null) {
            return Optional.empty();
        }
        Path scratch = Files.createTempDirectory("excalibur-backup-verify");
        try {
            Path copy = scratch.resolve(file.getFileName().toString());
            SparseFileCopy.copy(file, copy);
            return Optional.of(run(format, copy, relativePath));
        } finally {
            deleteQuietly(scratch);
        }
    }

    /**
     * Names the structure a path holds, or null if this build has no checker for it.
     *
     * @param relativePath The manifest path
     * @return The format name, or null
     */
    private static String formatOf(String relativePath) {
        String name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        if (name.equals(CATALOG_NAME)) {
            return "catalog";
        }
        if (name.endsWith(".hnsw.docmap") || name.endsWith(".hnsw.compact.docmap")) {
            return "docmap";
        }
        if (name.endsWith(".hnsw") || name.endsWith(".hnsw.compact")) {
            return "hnsw";
        }
        if (name.endsWith(PQCodebookFile.SUFFIX)) {
            return "codebook";
        }
        if (name.endsWith(".lsh")) {
            return "lsh";
        }
        return name.endsWith(".dat") ? "segment" : null;
    }

    /**
     * Runs the checker for one format over a scratch copy.
     *
     * @param format       Which checker to use
     * @param copy         The scratch copy
     * @param relativePath The manifest path, for the report
     * @return What the checker said
     */
    private static ContentCheck run(String format, Path copy, String relativePath) {
        return switch (format) {
            case "catalog" -> checkCatalog(copy, relativePath);
            case "segment" -> checkSegment(copy, relativePath);
            case "hnsw" -> report(relativePath, format, HnswGraph.verifyIntegrity(copy));
            case "docmap" -> report(relativePath, format,
                NodeDocumentMapping.verifyIntegrity(copy));
            case "codebook" -> report(relativePath, format,
                PQCodebookFile.verifyIntegrity(copy));
            case "lsh" -> report(relativePath, format, LshHashTable.verifyIntegrity(copy));
            default -> throw new IllegalStateException("No checker for format " + format);
        };
    }

    /**
     * Opens the catalog, which validates its header and every record CRC as it does.
     *
     * @param copy         The scratch copy
     * @param relativePath The manifest path
     * @return What opening it found
     */
    private static ContentCheck checkCatalog(Path copy, String relativePath) {
        try (CatalogFile catalog = new CatalogFile(copy)) {
            List<SegmentCatalog.SegmentMetadata> records = catalog.readAll();
            return new ContentCheck(relativePath, "catalog", true, "format version "
                + CatalogFile.VERSION + ", " + records.size() + " segment records, header and "
                + "record checksums all valid");
        } catch (IOException | RuntimeException e) {
            return new ContentCheck(relativePath, "catalog", false,
                "the catalog will not open: " + e);
        }
    }

    /**
     * Walks every occupied slot of a segment.
     *
     * @param copy         The scratch copy
     * @param relativePath The manifest path
     * @return What the walk found
     */
    private static ContentCheck checkSegment(Path copy, String relativePath) {
        try (Arena arena = Arena.ofConfined();
             Segment segment = Segment.openExisting(copy, arena)) {
            SegmentIntegrityReport report = segment.verifyIntegrity();
            String detail = report.entriesScanned() + " entries scanned, "
                + report.entriesVerified() + " verified, " + report.entriesUnverifiable()
                + " in a format carrying no checksum, " + report.damagedCount() + " damaged";
            if (report.isHealthy()) {
                return new ContentCheck(relativePath, "segment", true, detail);
            }
            return new ContentCheck(relativePath, "segment", false, detail + "; first fault: "
                + report.damaged().get(0).detail());
        } catch (IOException | RuntimeException e) {
            return new ContentCheck(relativePath, "segment", false,
                "the segment will not open: " + e);
        }
    }

    /**
     * Flattens a single-file verdict into this package's shape.
     *
     * <p>The vector and filter formats share one report type, and the storage engine's
     * segment walk has another shape entirely; both arrive here as a verdict and a
     * sentence so that one verification report can cover an image holding all of them.</p>
     *
     * @param relativePath The manifest path
     * @param format       The structure's name
     * @param report       What the checker said
     * @return The same verdict, in one shape
     */
    private static ContentCheck report(String relativePath, String format,
                                       IntegrityReport report) {
        return new ContentCheck(relativePath, format, report.loadable(),
            report.status() + ": " + report.detail());
    }

    /**
     * Removes a scratch directory, reporting nothing if it will not go.
     *
     * @param root The scratch directory
     */
    private static void deleteQuietly(Path root) {
        try {
            com.eonmux.excalibur.backup.io.AtomicPublish.deleteTree(root);
        } catch (IOException e) {
            root.toFile().deleteOnExit();
        }
    }
}
