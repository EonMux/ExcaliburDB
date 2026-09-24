package com.eonmux.excalibur.backup.verify;

import com.eonmux.excalibur.backup.io.AtomicPublish;
import com.eonmux.excalibur.backup.io.SparseFileCopy;
import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.filter.lsh.LshHashTable;
import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.ConsistencyFinding;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.FileInspector;
import com.eonmux.excalibur.fsck.check.FileKind;
import com.eonmux.excalibur.fsck.check.FindingSeverity;
import com.eonmux.excalibur.fsck.check.GraphStoreCheck;
import com.eonmux.excalibur.fsck.check.LexicalStoreCheck;
import com.eonmux.excalibur.fsck.check.LshTableCheck;
import com.eonmux.excalibur.fsck.check.LuceneChunkIndexCheck;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Hands each file in a snapshot to the checker for its format.
 *
 * <h2>Why not re-derive what a valid file looks like</h2>
 * <p>Every format a data directory holds already has a verifier, and a second implementation
 * here would be a second definition of "valid" that drifts from the first. Two sets are
 * used, and between them they cover every file an image can contain.</p>
 *
 * <p>The engine's own, for the six formats whose verifier lives beside the code that writes
 * them: {@link Segment#verifyIntegrity()} walks every occupied slot of a segment and compares
 * each entry's stored checksum; {@link HnswGraph#verifyIntegrity(Path)} and
 * {@link NodeDocumentMapping#verifyIntegrity(Path)} check the seal a clean close writes on a
 * graph and its node mapping, which names each node's chunk, or its document in a file an
 * older build wrote; {@link PQCodebookFile#verifyIntegrity(Path)} and
 * {@link LshHashTable#verifyIntegrity(Path)} do the same for their sidecars; and
 * {@link CatalogFile} validates its own header and record CRCs as it opens.</p>
 *
 * <p>{@code excalibur-fsck}'s, through {@link FileInspector}, for everything else: the
 * write-ahead logs, whose records are replayed and their checksums compared; the routing
 * directory, whose trailing checksum is compared with every byte before it; the document
 * registry, the source catalog, the source status history and the erasure receipts, which are
 * checked against the header version, the record count and the checksum their readers
 * require; the in-flight ingest journal, whose every line carries its own checksum; the graph staging logs; the
 * native lexical store, which is checked as a whole by {@link #withLexicalStore} because its
 * manifest - verified against the checksum on its end line - the segments it names, the key
 * table and tombstones beside each, and its erasure log are one structure; the Lucene chunk
 * index, checked as a whole from its commit point by {@link #withLuceneChunkIndex}, and the
 * erasure log beside it, line by line; and each tenant's
 * built graph, checked as a whole by {@link #withGraphStores} because its descriptor and
 * dictionaries record what every other file of it must hold, and because what a directory
 * beside it is depends on its neighbours. The same dispatch is what {@code excalibur-fsck}
 * surveys a live directory with, so the two tools cannot disagree about which checker a file
 * belongs to.</p>
 *
 * <p>That includes the pair an interrupted HNSW compaction stages,
 * {@code segment_N.hnsw.compact} and {@code segment_N.hnsw.compact.docmap}, even though each
 * is written in a format the engine checks. What the staged mapping is depends on its
 * neighbour: beside the staged graph the compaction never swapped, the node discards both on
 * open, and nothing reads either, so a torn pair is a leftover and not damage; alone, the
 * graph swap completed, and the mapping is the only one that matches the live graph, so it is
 * verified and its damage is real. Handed to the format checkers by name, a torn pair would
 * fail an image that restores to a node that opens cleanly, and {@code excalibur-fsck} would
 * pass the same directory - the disagreement one dispatch exists to rule out.</p>
 *
 * <h2>What "proven" means</h2>
 * <p>Some files carry nothing a checker could prove: a routing directory, lexical store
 * manifest, key table, tombstone file, erasure log or graph an older build wrote before those
 * formats carried a checksum; a registry or in-flight journal written before its format carried
 * one, which has only its count or nothing at all; a file in the lexical store beside no segment
 * its manifest names; a file in a graph directory that no build writes or that the node deletes
 * on open; and a term sketch an earlier build kept beside a segment, which nothing in this build
 * reads. Those are reported as checked and unproven - healthy, because nothing wrong was found,
 * and not proven, because nothing could have been - rather than left out, which would read as
 * though nothing in the image went unverified.</p>
 *
 * <h2>Why the engine's checks run on a copy</h2>
 * <p>Several of the engine's open paths open their file read-write and repair it as they go -
 * a catalog with one damaged header slot is rewritten from the other, an interrupted HNSW
 * compaction is finished. That is right for a node opening its own data directory and wrong
 * for a verification, which must leave the image exactly as it found it, not least because
 * it has just certified the image's checksums. So each such file is copied to scratch,
 * checked there and thrown away. The copy preserves holes, so checking a 755 MiB graph that
 * holds a few nodes costs what those few nodes cost. {@code excalibur-fsck}'s checkers map
 * read-only and write nothing, so they read the image where it lies.</p>
 *
 * @author ExcaliburDB
 */
public final class FormatChecks {

    /** The segment catalog, which is a {@code .dat} that is not a segment. */
    private static final String CATALOG_NAME = "catalog.dat";

    private FormatChecks() {
    }

    /**
     * Checks one file with the checker for its format.
     *
     * @param file         The file inside the payload
     * @param relativePath Its manifest path, whose name and location decide its format
     * @return What the format said about itself
     * @throws IOException          if scratch space for an engine check cannot be created
     * @throws NullPointerException if either argument is null
     */
    public static ContentCheck check(Path file, String relativePath) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(relativePath, "relativePath");
        String format = engineFormatOf(relativePath);
        if (format == null) {
            return fromFsck(FileInspector.inspect(file, relativePath).check());
        }
        Path scratch = Files.createTempDirectory("excalibur-backup-verify");
        try {
            Path copy = scratch.resolve(file.getFileName().toString());
            SparseFileCopy.copy(file, copy);
            return run(format, copy, relativePath);
        } finally {
            deleteQuietly(scratch);
        }
    }

    /**
     * Replaces the per-file verdicts on a lexical store's contents with the verdict on the
     * store as a whole.
     *
     * <p>Every file under the store is, taken alone, a lexical store part with nothing to
     * prove. The store is verified by reading its manifest, streaming every column of every
     * segment the manifest names, and checking each named segment's key table and tombstones
     * and the erasure log against the checksums they were written with, so those verdicts speak
     * for the manifest, each segment directory and each of those files, and supersede the
     * per-file verdicts on them. A file under the store that none of them judged - one beside
     * no segment the manifest names - keeps its own. A segment the
     * manifest names and the image does not hold is a verdict here and nowhere else, since no
     * per-file check can report a file that is not there.</p>
     *
     * @param payloadRoot The payload root
     * @param checks      The per-file verdicts on the image
     * @return The same verdicts with the lexical store's folded in, unchanged if the image has
     *         no lexical store
     * @throws NullPointerException if either argument is null
     */
    public static List<ContentCheck> withLexicalStore(Path payloadRoot,
                                                      List<ContentCheck> checks) {
        Objects.requireNonNull(payloadRoot, "payloadRoot");
        Objects.requireNonNull(checks, "checks");
        Optional<LexicalStoreCheck.LexicalInspection> store =
            FileInspector.lexicalStore(payloadRoot);
        if (store.isEmpty()) {
            return List.copyOf(checks);
        }
        LexicalStoreCheck.LexicalInspection inspection = store.get();
        List<ContentCheck> folded = new ArrayList<>(checks.size());
        for (ContentCheck check : checks) {
            if (!inspection.covers(check.path())) {
                folded.add(check);
            }
        }
        inspection.verdicts().forEach(verdict -> folded.add(fromFsck(verdict)));
        inspection.findings().forEach(finding -> folded.add(fromFinding(finding)));
        return List.copyOf(folded);
    }

    /**
     * Replaces the per-file verdicts on the Lucene chunk index's files with the verdict on the
     * index as a whole.
     *
     * <p>Every file under {@code lucene-chunks} but the erasure log is, taken alone, a part of an
     * index with nothing to prove. The index is verified from its commit point, with the footer
     * of every file every segment is made of checked against the checksum in it, and that
     * verdict speaks for all of them. The erasure log keeps its own verdict, reached line by line
     * by the parser the index opens it with.</p>
     *
     * @param payloadRoot The payload root
     * @param checks      The per-file verdicts on the image
     * @return The same verdicts with the index's folded in, unchanged if the image holds none
     * @throws NullPointerException if either argument is null
     */
    public static List<ContentCheck> withLuceneChunkIndex(Path payloadRoot,
                                                          List<ContentCheck> checks) {
        Objects.requireNonNull(payloadRoot, "payloadRoot");
        Objects.requireNonNull(checks, "checks");
        Optional<LuceneChunkIndexCheck.LuceneChunkInspection> index =
            FileInspector.luceneChunkIndex(payloadRoot);
        if (index.isEmpty()) {
            return List.copyOf(checks);
        }
        List<ContentCheck> folded = new ArrayList<>(checks.size());
        for (ContentCheck check : checks) {
            if (!index.get().covers(check.path())) {
                folded.add(check);
            }
        }
        folded.add(fromFsck(index.get().index()));
        return List.copyOf(folded);
    }

    /**
     * Replaces the per-file verdicts on each tenant's built graph with the verdicts on the graph
     * as a whole.
     *
     * <p>Every file under {@code <namespace>/graph}, and under the directories a publish, a build
     * or an erasure compaction leaves beside it, is, taken alone, a graph part with nothing to
     * prove. The graph is verified by the checks the node runs before it serves one: the edge
     * store's descriptor and each column against the length and CRC32C it records, each label
     * dictionary's index and text against the trailer the index carries, the entity vectors
     * against the checksums in their header, and each Lucene index against the CRC32 footer of
     * every file in it. Those verdicts, and the unchecked verdicts on scratch and on a copy the
     * node deletes on open, supersede the per-file ones. A file the descriptor or a dictionary
     * names and the image does not hold is a verdict here and nowhere else.</p>
     *
     * @param payloadRoot The payload root
     * @param checks      The per-file verdicts on the image
     * @return The same verdicts with every graph's folded in, unchanged if the image holds none
     * @throws NullPointerException if either argument is null
     */
    public static List<ContentCheck> withGraphStores(Path payloadRoot,
                                                     List<ContentCheck> checks) {
        Objects.requireNonNull(payloadRoot, "payloadRoot");
        Objects.requireNonNull(checks, "checks");
        List<GraphStoreCheck.GraphInspection> graphs = FileInspector.graphStores(payloadRoot);
        if (graphs.isEmpty()) {
            return List.copyOf(checks);
        }
        List<ContentCheck> folded = new ArrayList<>(checks.size());
        for (ContentCheck check : checks) {
            if (graphs.stream().noneMatch(graph -> graph.covers(check.path()))) {
                folded.add(check);
            }
        }
        graphs.forEach(graph -> graph.verdicts().forEach(verdict ->
            folded.add(fromFsck(verdict))));
        return List.copyOf(folded);
    }

    /**
     * Names the structure a path holds, when one of the engine's own checkers owns it.
     *
     * @param relativePath The manifest path
     * @return The format name, or null when {@code excalibur-fsck}'s dispatch owns the file
     */
    private static String engineFormatOf(String relativePath) {
        String name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        if (name.equals(CATALOG_NAME)) {
            return "catalog";
        }
        // A compaction's staged pair ends in .hnsw.compact and .hnsw.compact.docmap, which
        // neither test matches, so it falls through to excalibur-fsck's dispatch; see above.
        if (name.endsWith(".hnsw.docmap")) {
            return "docmap";
        }
        if (name.endsWith(".hnsw")) {
            return "hnsw";
        }
        if (name.endsWith(PQCodebookFile.SUFFIX)) {
            return "codebook";
        }
        // Before the segment test: an LSH table is a .dat too, inside a segment's .lsh
        // directory, and opened as a segment it would fail as one.
        if (LshTableCheck.matches(name)) {
            return "lsh";
        }
        return FileKind.SEGMENT_DATA_NAME.matcher(name).matches() ? "segment" : null;
    }

    /**
     * Runs the engine's checker for one format over a scratch copy.
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
            return new ContentCheck(relativePath, "catalog", true, true, "format version "
                + CatalogFile.VERSION + ", " + records.size() + " segment records, header and "
                + "record checksums all valid");
        } catch (IOException | RuntimeException e) {
            return new ContentCheck(relativePath, "catalog", false, false,
                "the catalog will not open: " + e);
        }
    }

    /**
     * Walks every occupied slot of a segment.
     *
     * <p>A segment is proven only when every entry in it carried a checksum. Entries written
     * in the format that predates per-entry checksums can be found intact in shape and still
     * not be shown to hold what was written.</p>
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
                return new ContentCheck(relativePath, "segment", true,
                    report.entriesUnverifiable() == 0, detail);
            }
            return new ContentCheck(relativePath, "segment", false, false, detail
                + "; first fault: " + report.damaged().get(0).detail());
        } catch (IOException | RuntimeException e) {
            return new ContentCheck(relativePath, "segment", false, false,
                "the segment will not open: " + e);
        }
    }

    /**
     * Flattens an engine checker's single-file verdict into this package's shape.
     *
     * @param relativePath The manifest path
     * @param format       The structure's name
     * @param report       What the checker said
     * @return The same verdict, in one shape
     */
    private static ContentCheck report(String relativePath, String format,
                                       IntegrityReport report) {
        return new ContentCheck(relativePath, format, report.loadable(), report.verified(),
            report.status() + ": " + report.detail());
    }

    /**
     * Flattens one of {@code excalibur-fsck}'s verdicts into this package's shape.
     *
     * <p>Only damage is unhealthy: a file whose format carries nothing to check, or one
     * sealed at a version that predates its seal, is not evidence of a bad image. Damage is
     * what {@link FileCheck#isDamage()} says it is, the same judgement {@code excalibur-fsck}
     * gates on, so a lexical segment's key table that fails its checksum is healthy and
     * unproven here as it is not damage there: a node restored from the image rebuilds it on
     * open. Only a
     * positively verified file with nothing in it left unverified is proven. The status leads
     * the sentence so that the report says which of the two a healthy file was.</p>
     *
     * @param check The verdict
     * @return The same verdict, in one shape
     */
    private static ContentCheck fromFsck(FileCheck check) {
        CheckStatus status = check.status();
        boolean healthy = !check.isDamage();
        return new ContentCheck(check.relative(), check.format(), healthy,
            healthy && status.isProven() && !check.unverifiedContent(),
            status + ": " + check.detail());
    }

    /**
     * Flattens an inconsistency the lexical store check found between its manifest and the
     * directory.
     *
     * @param finding What was found
     * @return A verdict on the thing it names, unhealthy when the finding is damage
     */
    private static ContentCheck fromFinding(ConsistencyFinding finding) {
        boolean damage = finding.severity() == FindingSeverity.DAMAGE;
        return new ContentCheck(finding.subject(), LexicalStoreCheck.FORMAT, !damage, false,
            finding.severity() + " " + finding.kind() + ": " + finding.detail());
    }

    /**
     * Removes a scratch directory, reporting nothing if it will not go.
     *
     * @param root The scratch directory
     */
    private static void deleteQuietly(Path root) {
        try {
            AtomicPublish.deleteTree(root);
        } catch (IOException e) {
            root.toFile().deleteOnExit();
        }
    }
}
