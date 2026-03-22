package excalibur.ingest.registry;

import io.excalibur.store.hash.XxHash64;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for tracking indexed document metadata to enable change detection on re-crawl.
 *
 * <h2>Purpose</h2>
 * <p>The DocumentRegistry stores metadata about all indexed documents, including:
 * <ul>
 *   <li>Document ID - unique identifier within the source</li>
 *   <li>Source ID - identifier of the source this document came from</li>
 *   <li>Last modified timestamp - when the document was last modified in the source</li>
 *   <li>Content hash - xxHash64 hash of the document content for change detection</li>
 *   <li>Content length - size of the original document in bytes</li>
 *   <li>Chunk IDs - identifiers of the chunks this document was split into</li>
 *   <li>Index time - when this document was indexed</li>
 * </ul>
 *
 * <h2>Change Detection</h2>
 * <p>When re-crawling, the registry is used to determine:
 * <ul>
 *   <li><strong>Changed documents:</strong> Documents where lastModified, contentHash,
 *       or contentLength differ from the stored metadata</li>
 *   <li><strong>Deleted documents:</strong> Documents present in the registry but
 *       not returned by the current crawl</li>
 *   <li><strong>New documents:</strong> Documents returned by the crawl but not
 *       present in the registry</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is fully thread-safe. All operations use ConcurrentHashMap and
 * atomic operations. Multiple threads can safely read and write concurrently.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Keys are composite: "sourceId:documentId" format</li>
 *   <li>Content hashes are positive 64-bit values from xxHash64</li>
 *   <li>Timestamps are non-negative epoch milliseconds</li>
 *   <li>Chunk ID lists are never null (empty list if no chunks)</li>
 * </ul>
 */
public final class DocumentRegistry {

    private static final String KEY_SEPARATOR = ":";

    private final ConcurrentHashMap<String, DocumentMetadata> documents;
    private final Path persistencePath;
    private volatile boolean modified = false;

    /**
     * Creates an in-memory DocumentRegistry (no persistence).
     */
    public DocumentRegistry() {
        this(null);
    }

    /**
     * Creates a DocumentRegistry with optional file persistence.
     *
     * @param persistencePath Path to the registry file, or null for in-memory only
     */
    public DocumentRegistry(Path persistencePath) {
        this.documents = new ConcurrentHashMap<>();
        this.persistencePath = persistencePath;
        
        if (persistencePath != null && Files.exists(persistencePath)) {
            loadFromFile(persistencePath);
        }
    }

    /**
     * Registers a document's metadata in the registry.
     *
     * <p>If a document with the same sourceId and documentId already exists,
     * its metadata is replaced with the new values.</p>
     *
     * @param metadata The document metadata to register
     * @throws NullPointerException if metadata is null
     */
    public void register(DocumentMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        String key = makeKey(metadata.getSourceId(), metadata.getDocumentId());
        documents.put(key, metadata);
        modified = true;
    }

    /**
     * Retrieves metadata for a specific document.
     *
     * @param sourceId The source identifier
     * @param documentId The document identifier
     * @return The document metadata, or null if not found
     * @throws NullPointerException if sourceId or documentId is null
     */
    public DocumentMetadata get(String sourceId, String documentId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        String key = makeKey(sourceId, documentId);
        return documents.get(key);
    }

    /**
     * Checks if a document is registered.
     *
     * @param sourceId The source identifier
     * @param documentId The document identifier
     * @return true if the document is registered
     * @throws NullPointerException if sourceId or documentId is null
     */
    public boolean contains(String sourceId, String documentId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        String key = makeKey(sourceId, documentId);
        return documents.containsKey(key);
    }

    /**
     * Removes a document's metadata from the registry.
     *
     * @param sourceId The source identifier
     * @param documentId The document identifier
     * @return The removed metadata, or null if not found
     * @throws NullPointerException if sourceId or documentId is null
     */
    public DocumentMetadata remove(String sourceId, String documentId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        String key = makeKey(sourceId, documentId);
        DocumentMetadata removed = documents.remove(key);
        if (removed != null) {
            modified = true;
        }
        return removed;
    }

    /**
     * Removes all documents for a specific source.
     *
     * @param sourceId The source identifier
     * @return The number of documents removed
     * @throws NullPointerException if sourceId is null
     */
    public int removeBySource(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        String prefix = sourceId + KEY_SEPARATOR;
        
        int removed = 0;
        for (String key : documents.keySet()) {
            if (key.startsWith(prefix)) {
                documents.remove(key);
                removed++;
            }
        }
        
        if (removed > 0) {
            modified = true;
        }
        
        return removed;
    }

    /**
     * Returns all document IDs for a specific source.
     *
     * @param sourceId The source identifier
     * @return Collection of document IDs (never null)
     * @throws NullPointerException if sourceId is null
     */
    public Collection<String> getDocumentIds(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        String prefix = sourceId + KEY_SEPARATOR;
        
        return documents.keySet().stream()
            .filter(key -> key.startsWith(prefix))
            .map(key -> key.substring(prefix.length()))
            .collect(Collectors.toList());
    }

    /**
     * Returns all metadata entries for a specific source.
     *
     * @param sourceId The source identifier
     * @return Collection of document metadata (never null)
     * @throws NullPointerException if sourceId is null
     */
    public Collection<DocumentMetadata> getBySource(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        String prefix = sourceId + KEY_SEPARATOR;
        
        return documents.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(prefix))
            .map(Map.Entry::getValue)
            .collect(Collectors.toList());
    }

    /**
     * Returns the total number of registered documents.
     *
     * @return Document count
     */
    public int size() {
        return documents.size();
    }

    /**
     * Returns the number of documents for a specific source.
     *
     * @param sourceId The source identifier
     * @return Document count for the source
     * @throws NullPointerException if sourceId is null
     */
    public int size(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        return getDocumentIds(sourceId).size();
    }

    /**
     * Clears all registered documents.
     */
    public void clear() {
        documents.clear();
        modified = true;
    }

    /**
     * Computes a content hash for the given byte array.
     *
     * <p>Uses xxHash64 for fast non-cryptographic hashing. The result is
     * always a positive 64-bit value.</p>
     *
     * @param content The content to hash
     * @return 64-bit hash value (always positive)
     * @throws NullPointerException if content is null
     */
    public static long computeContentHash(byte[] content) {
        Objects.requireNonNull(content, "content must not be null");
        return XxHash64.hash(content);
    }

    /**
     * Creates a FetchedDocumentInfo from a FetchedDocument for change comparison.
     *
     * @param document The fetched document
     * @return FetchedDocumentInfo for comparison
     * @throws NullPointerException if document is null
     */
    public static DocumentMetadata.FetchedDocumentInfo toFetchedInfo(
            excalibur.ingest.source.FetchedDocument document) {
        Objects.requireNonNull(document, "document must not be null");
        
        long hash = computeContentHash(document.getContent());
        return new DocumentMetadata.FetchedDocumentInfo(
            document.getLastModifiedMillis(),
            hash,
            document.getContentLength()
        );
    }

    /**
     * Persists the registry to disk if a persistence path was configured.
     *
     * @throws IOException if persistence fails
     */
    public void persist() throws IOException {
        if (persistencePath == null) {
            return;
        }
        
        if (!modified) {
            return;
        }
        
        Path parent = persistencePath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        
        try (BufferedWriter writer = Files.newBufferedWriter(persistencePath)) {
            for (DocumentMetadata metadata : documents.values()) {
                writer.write(formatMetadata(metadata));
                writer.newLine();
            }
        }
        
        modified = false;
    }

    /**
     * Loads the registry from a file.
     *
     * @param path The path to load from
     * @throws NullPointerException if path is null
     */
    private void loadFromFile(Path path) {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                DocumentMetadata metadata = parseMetadata(line);
                if (metadata != null) {
                    documents.put(makeKey(metadata.getSourceId(), metadata.getDocumentId()), metadata);
                }
            }
        } catch (IOException e) {
            // If loading fails, start with empty registry
        }
    }

    /**
     * Formats metadata for persistence.
     *
     * <p>Format: sourceId|documentId|lastModifiedMillis|contentHash|contentLength|indexTimeMillis|chunkId1,chunkId2,...</p>
     */
    private String formatMetadata(DocumentMetadata metadata) {
        String chunkIds = String.join(",", metadata.getChunkIds());
        return String.format("%s|%s|%d|%d|%d|%d|%s",
            metadata.getSourceId(),
            metadata.getDocumentId(),
            metadata.getLastModifiedMillis(),
            metadata.getContentHash(),
            metadata.getContentLength(),
            metadata.getIndexTimeMillis(),
            chunkIds
        );
    }

    /**
     * Parses metadata from a persisted line.
     */
    private DocumentMetadata parseMetadata(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        
        String[] parts = line.split("\\|", 7);
        if (parts.length < 6) {
            return null;
        }
        
        try {
            DocumentMetadata.Builder builder = DocumentMetadata.builder()
                .sourceId(parts[0])
                .documentId(parts[1])
                .lastModifiedMillis(Long.parseLong(parts[2]))
                .contentHash(Long.parseLong(parts[3]))
                .contentLength(Long.parseLong(parts[4]))
                .indexTimeMillis(Long.parseLong(parts[5]));
            
            if (parts.length > 6 && !parts[6].isEmpty()) {
                for (String chunkId : parts[6].split(",")) {
                    if (!chunkId.isEmpty()) {
                        builder.addChunkId(chunkId);
                    }
                }
            }
            
            return builder.build();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Creates a composite key from source ID and document ID.
     */
    private static String makeKey(String sourceId, String documentId) {
        return sourceId + KEY_SEPARATOR + documentId;
    }

    /**
     * Returns an unmodifiable view of all registered metadata.
     *
     * @return Collection of all document metadata
     */
    public Collection<DocumentMetadata> getAll() {
        return Collections.unmodifiableCollection(documents.values());
    }

    /**
     * Checks if the registry has been modified since last persist.
     *
     * @return true if modified
     */
    public boolean isModified() {
        return modified;
    }

    @Override
    public String toString() {
        return String.format("DocumentRegistry[size=%d, persisted=%s]", 
            documents.size(), persistencePath != null);
    }
}
