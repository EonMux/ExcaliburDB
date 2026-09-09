package excalibur.api.bootstrap;

import excalibur.ingest.registry.DocumentRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        return registry;
    }
}
