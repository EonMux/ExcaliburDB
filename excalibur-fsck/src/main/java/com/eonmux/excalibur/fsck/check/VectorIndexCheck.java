package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.NodeDocumentMapping;
import com.eonmux.excalibur.vector.quantization.PQCodebookFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifies the three vector index files a segment can own.
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
