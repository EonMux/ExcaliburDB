package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.ingest.registry.DocumentRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Opens the registry of which documents this node holds.
 *
 * <h2>Why it is on disk</h2>
 * <p>The registry maps a document to the chunk ids it was written under. Two operations have
 * nothing to work from without it: change detection, which decides whether a re-crawled document is
 * new, changed or unchanged, and erasure, which has to name a document's chunks to remove them.</p>
 *
 * <p>Held only in memory it is lost on restart, and neither failure announces itself. A re-crawl
 * then reads every document as new, so it reingests the whole corpus and tombstones none of the old
 * chunks; and {@code POST /v1/erasure} reports every document as one this node never held. So the
 * registry is opened against a file under the data directory, beside the data it describes.</p>
 *
 * <h2>A registry in an older format</h2>
 * <p>A registry written before the format carried a header and a record count is read, but a
 * truncated copy of one cannot be told from a whole one; a registry written before the format
 * carried a checksum is read too, but a record altered in place in one cannot be told from the
 * record that was written. So either is rewritten in the current format as it is opened, through
 * the same durable save every other change goes through, rather than at the first ingest or
 * erasure - which on a node that is only queried after an upgrade might never come. A rewrite
 * that fails is logged and left to the next save: every record was read, so the node has what it
 * needs to start.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the registry it returns is thread-safe.</p>
 */
public final class DocumentRegistryFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentRegistryFactory.class);

    /** The registry file, under the data directory. */
    public static final String FILE = "document-registry";

    private DocumentRegistryFactory() {
        throw new AssertionError("Utility class");
    }

    /**
     * Opens the registry, loading what a previous run recorded.
     *
     * @param dataDirectory The node's data directory
     * @return The registry
     * @throws NullPointerException                     if the directory is null
     * @throws DocumentRegistry.RegistryLoadException   if a registry exists and cannot be read.
     *                                                  Deliberately fatal: starting empty would
     *                                                  silently reingest the corpus and orphan
     *                                                  every chunk already written
     */
    public static DocumentRegistry open(Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory must not be null");
        Path file = dataDirectory.resolve(FILE);
        DocumentRegistry registry = new DocumentRegistry(file);
        LOGGER.info("Document registry opened at {} with {} document(s)", file, registry.size());
        // Nothing has been registered yet, so a registry already owing a save is one the load
        // itself marked for rewriting.
        if (registry.isModified()) {
            upgrade(registry, file);
        }
        return registry;
    }

    /**
     * Rewrites a registry loaded from an older format in the current one.
     *
     * @param registry The registry as loaded
     * @param file     Its file, for the log
     */
    private static void upgrade(DocumentRegistry registry, Path file) {
        try {
            registry.persist();
            LOGGER.info("Document registry at {} rewritten in the current format", file);
        } catch (IOException e) {
            LOGGER.warn("Could not rewrite the document registry at {} in the current format; "
                + "it stays in the older one until the next save succeeds: {}",
                file, e.getMessage());
        }
    }
}
