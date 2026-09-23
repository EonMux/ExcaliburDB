package com.eonmux.excalibur.graph.integrity;

import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.graph.dictionary.LabelDictionaryIntegrity;
import com.eonmux.excalibur.graph.format.EdgeStoreIntegrity;
import com.eonmux.excalibur.graph.staging.GraphBuilder;
import com.eonmux.excalibur.graph.vector.EntityVectors;
import com.eonmux.excalibur.graph.vector.EntityVectorsIntegrity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A verdict on every file this module writes into a built graph directory, reached without
 * opening the graph.
 *
 * <h2>What it covers</h2>
 * <p>The edge store (its descriptor and every column), the entity and relation dictionaries, and
 * the entity vectors when the graph was built with them. These are the same checks the node runs
 * before it maps any of them, so a verdict here stands for what the node would do: a file
 * reported {@code CORRUPT} or {@code MISSING} is one the node refuses to serve, one reported
 * {@code LEGACY_UNPROTECTED} is one it serves unverified until the next build replaces it, and
 * one reported {@code UNREADABLE} is at a format version this build does not read.</p>
 *
 * <p>The two Lucene indexes a build also writes into the directory, {@code labels/} and
 * {@code documents/}, belong to the retrieval module and are judged by it; a caller surveying a
 * whole graph directory combines the two.</p>
 *
 * <h2>Read-only</h2>
 * <p>Every file is streamed through a channel and nothing is mapped or written, so this can be
 * pointed at a live data directory, a displaced {@code graph.previous}, or a snapshot's payload.</p>
 */
public final class ServedGraphIntegrity {

    private ServedGraphIntegrity() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reaches a verdict on every file of a built graph that this module writes.
     *
     * <p>Reports come in a fixed order: the edge store's, then the entity dictionary's, the
     * relation dictionary's, and the entity vectors' when that file is present. Its absence is a
     * graph built without an embedder, which is a configuration rather than damage, and is not
     * reported.</p>
     *
     * @param graphDirectory The graph directory
     * @return The reports, never empty
     * @throws NullPointerException if {@code graphDirectory} is null
     */
    public static List<IntegrityReport> inspect(Path graphDirectory) {
        Objects.requireNonNull(graphDirectory, "graphDirectory must not be null");
        List<IntegrityReport> reports = new ArrayList<>(EdgeStoreIntegrity.inspect(graphDirectory));
        reports.addAll(LabelDictionaryIntegrity.inspect(graphDirectory,
            GraphBuilder.ENTITY_DICTIONARY));
        reports.addAll(LabelDictionaryIntegrity.inspect(graphDirectory,
            GraphBuilder.RELATION_DICTIONARY));
        Path vectors = graphDirectory.resolve(EntityVectors.FILE_NAME);
        if (Files.exists(vectors)) {
            reports.add(EntityVectorsIntegrity.inspect(vectors));
        }
        return List.copyOf(reports);
    }
}
