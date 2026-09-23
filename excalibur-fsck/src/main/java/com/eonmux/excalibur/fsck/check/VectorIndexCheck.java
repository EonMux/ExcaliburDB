package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.NodeDocumentMapping;
import com.eonmux.excalibur.vector.quantization.PQCodebookFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifies the three vector index files a segment can own, and the mapping a compaction
 * stages beside them.
 *
 * <p>Each format's own {@code verifyIntegrity} does the work: they read the file through a
 * read-only channel, check a CRC32C over the header and another over the payload, and
 * report rather than throw. Nothing about those layouts is repeated here.</p>
 */
public final class VectorIndexCheck {

    private VectorIndexCheck() {
        throw new AssertionError("Utility class");
    }

    /**
     * Verifies an HNSW graph file.
     *
     * @param file     The {@code .hnsw} file
     * @param relative Its path relative to the data directory
     * @return The verdict
     */
    public static FileCheck graph(Path file, String relative) {
        return IntegrityReports.from(
            HnswGraph.verifyIntegrity(file), file, relative, null, sizeOf(file));
    }

    /**
     * Verifies an HNSW node-to-document mapping.
     *
     * @param file     The {@code .hnsw.docmap} file
     * @param relative Its path relative to the data directory
     * @return The verdict
     */
    public static FileCheck documentMapping(Path file, String relative) {
        return IntegrityReports.from(
            NodeDocumentMapping.verifyIntegrity(file), file, relative, null, sizeOf(file));
    }

    /**
     * Reaches a verdict on the mapping an HNSW compaction staged in the compacted numbering.
     *
     * <p>What the file is depends on whether the compacted graph it was staged beside is still
     * there, which is the same test the engine's recovery makes on open. With the graph beside
     * it, the compaction never swapped, the node discards both, and nothing reads either: it
     * is reported as the staging leftover it is, without being read, exactly as the graph
     * next to it is. Without the graph, the swap completed and this is the only mapping that
     * matches {@code segment_N.hnsw}; it is verified with the mapping format's own verifier,
     * and damage to it is put to an operator rather than into the repair plan, because setting
     * it aside would leave the node serving the compacted graph through the pre-compaction
     * mapping - confident answers naming the wrong documents.</p>
     *
     * @param file     The {@code .hnsw.compact.docmap} file
     * @param relative Its path relative to the data directory
     * @return The verdict
     */
    public static FileCheck stagedDocumentMapping(Path file, String relative) {
        Path compactedGraph = Path.of(FileClassifier.compactedGraphOf(file.toString()));
        long size = sizeOf(file);
        if (Files.exists(compactedGraph)) {
            return new FileCheck(file, relative, "staging leftover", null, CheckStatus.UNCHECKED,
                "the mapping an HNSW compaction staged beside "
                    + compactedGraph.getFileName() + ", which is still here: the compaction "
                    + "never swapped its graph in, so the node discards both the next time it "
                    + "opens the graph and nothing reads this one", size);
        }
        FileCheck verified = IntegrityReports.from(
            NodeDocumentMapping.verifyIntegrity(file), file, relative, null, size);
        String pending = " - pending: the graph beside it was swapped to a compacted numbering "
            + "and this mapping was not yet renamed into place. The node installs it over "
            + FileClassifier.liveMappingOf(file.getFileName().toString())
            + " the next time it opens the graph; until then it is the only mapping that "
            + "matches the graph, and it must not be moved or deleted";
        return new FileCheck(file, relative, "staged " + verified.format(), verified.version(),
            verified.status(), verified.detail() + pending, verified.sizeBytes(),
            verified.status().isDamage());
    }

    /**
     * Verifies a product quantizer codebook.
     *
     * @param file     The {@code .pqcb} file
     * @param relative Its path relative to the data directory
     * @return The verdict
     */
    public static FileCheck codebook(Path file, String relative) {
        return IntegrityReports.from(
            PQCodebookFile.verifyIntegrity(file), file, relative, null, sizeOf(file));
    }

    /**
     * Reads a file's size, answering -1 rather than failing the survey.
     *
     * @param file The file
     * @return Its size in bytes, or -1
     */
    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }
}
