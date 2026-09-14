package excalibur.ingest.registry;

import io.excalibur.store.hash.XxHash64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentRegistry.class);

    private static final String KEY_SEPARATOR = ":";

    /** Separates the fields of one persisted record. */
    private static final char FIELD_SEPARATOR_CHAR = '|';
    private static final String FIELD_SEPARATOR = String.valueOf(FIELD_SEPARATOR_CHAR);

    /** Separates the chunk ids inside the last field of a record. */
    private static final char CHUNK_SEPARATOR_CHAR = ',';
    private static final String CHUNK_SEPARATOR = String.valueOf(CHUNK_SEPARATOR_CHAR);

    /** Introduces an escape sequence in a persisted field. */
    private static final char ESCAPE_CHAR = '\\';

    private final ConcurrentHashMap<String, DocumentMetadata> documents;
    /** Extension of the file a save is written to before it is renamed over the registry. */
    private static final String REPLACEMENT_SUFFIX = ".new";

    /** Distinguishes one save's replacement file from another's. */
    private static final java.util.concurrent.atomic.AtomicLong saveCounter =
        new java.util.concurrent.atomic.AtomicLong();

    private final Path persistencePath;
    /**
     * Counts mutations, so a save can tell what it wrote from what arrived while it was writing.
     *
     * <p>A boolean cleared after the write loses a registration that landed mid-iteration: the
     * record is not in the file, and the flag says there is nothing to save, so that document's
     * own save returns without writing. It stays searchable and absent from the registry, which
     * after a restart makes it neither erasable nor reindexable. A counter cannot lose it - the
     * save records the version it actually wrote, and a later mutation leaves the two unequal.</p>
     */
    private final java.util.concurrent.atomic.AtomicLong version =
        new java.util.concurrent.atomic.AtomicLong();

    /** The value of {@link #version} as of the last published save. */
    private volatile long persistedVersion;

    /** Serialises publishing, so two saves cannot interleave onto one registry file. */
    private final Object persistLock = new Object();

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
     * Thrown when a persisted registry exists but cannot be read.
     *
     * <p>An unreadable registry is not an empty registry: starting empty makes every previously
     * indexed document look new, so a re-crawl duplicates the whole corpus and tombstones none of
     * the old chunks. Failing here is the only way the caller can tell the two apart.</p>
     */
    public static final class RegistryLoadException extends RuntimeException {
        RegistryLoadException(Path path, IOException cause) {
            super("Failed to load document registry from " + path, cause);
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
        version.incrementAndGet();
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
            version.incrementAndGet();
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
            version.incrementAndGet();
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
        version.incrementAndGet();
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
     * <h3>Why it is written to one file and renamed onto another</h3>
     * <p>This file is the node's only record of which chunks belong to which document, and every
     * later erasure and reindex is driven from it. Rewriting it in place means that from the
     * truncate until the last line is flushed there is no complete registry anywhere: a crash in
     * that window leaves a file that parses cleanly and silently omits documents the node is
     * still serving, so those documents can no longer be erased or refreshed and nothing reports
     * it. Writing the replacement beside it and renaming closes the window - a reader sees the
     * old registry or the new one, and the rename is what publishes the change.</p>
     *
     * <p>The replacement is forced to disk before the rename, because the rename being durable
     * while the content is not would publish an empty or partial file after a power loss - the
     * same failure the rename exists to prevent, arrived at from the other side.</p>
     *
     * @throws IOException if persistence fails
     */
    public void persist() throws IOException {
        if (persistencePath == null) {
            return;
        }
        
        // Read before anything is written, and recorded only after. A mutation that lands while
        // this save is iterating leaves version above it, so the next save still has work to do
        // rather than being told the registry is clean.
        long writing = version.get();
        if (writing == persistedVersion) {
            return;
        }
        
        Path parent = persistencePath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        // Serialised, because four callers reach this - an ingest run, an erasure, a reindex and
        // shutdown - and the inline-document route reaches it from request threads, so two saves
        // genuinely overlap. Two writers sharing a replacement path is worse than no replacement
        // path at all: the second writer's open handle follows the file the first has just
        // published, and its remaining writes land in the live registry.
        synchronized (persistLock) {
            // Beside the registry rather than in the system temp directory: a rename is only
            // atomic within a filesystem, and a temp directory is routinely on a different one,
            // where the move silently degrades to the copy this is here to avoid. Named per save
            // so that a second registry instance over the same path - which this lock does not
            // cover - still cannot write into a replacement this one is publishing.
            Path replacement = persistencePath.resolveSibling(persistencePath.getFileName()
                + "." + saveCounter.incrementAndGet() + REPLACEMENT_SUFFIX);
            try {
                try (BufferedWriter writer = Files.newBufferedWriter(replacement,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    for (DocumentMetadata metadata : documents.values()) {
                        writer.write(formatMetadata(metadata));
                        writer.newLine();
                    }
                    writer.flush();
                    force(replacement);
                }
                move(replacement, persistencePath);
            } catch (IOException e) {
                // The old registry is still the published one, so the only thing to undo is the
                // half-written replacement. Left behind it would never be read, but a failed
                // save that also leaves litter reads as a worse failure. Suppressed rather than
                // thrown: losing the real cause to a tidy-up failure is a worse trade.
                try {
                    Files.deleteIfExists(replacement);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
                throw e;
            }

            persistedVersion = writing;
        }
    }

    /**
     * Forces a written file to disk.
     *
     * <p>Best effort: a filesystem that does not support syncing a file channel is not a reason
     * to fail a save that has otherwise succeeded, and the rename below still gives a reader
     * either the whole old registry or the whole new one.</p>
     *
     * @param path The file to force
     */
    private static void force(Path path) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        } catch (IOException e) {
            LOGGER.warn("Could not force the document registry to disk before publishing it: {}",
                e.getMessage());
        }
    }

    /**
     * Publishes the replacement over the live registry.
     *
     * @param replacement The file just written
     * @param target      The registry path
     * @throws IOException if the file cannot be published
     */
    private static void move(Path replacement, Path target) throws IOException {
        try {
            Files.move(replacement, target,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // A filesystem that cannot rename atomically leaves no better option than the
            // replacing move. Said out loud rather than silently accepted: on such a deployment
            // the window this method exists to close is open again.
            LOGGER.warn("This filesystem cannot rename atomically; the document registry is "
                + "published with a plain replace and a crash during it can truncate the file");
            Files.move(replacement, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Loads the registry from a file.
     *
     * @param path The path to load from
     * @throws RegistryLoadException if the file exists but cannot be read
     */
    private void loadFromFile(Path path) {
        int skipped = 0;
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                DocumentMetadata metadata = parseMetadata(line);
                if (metadata == null) {
                    skipped++;
                    continue;
                }
                documents.put(makeKey(metadata.getSourceId(), metadata.getDocumentId()), metadata);
            }
        } catch (IOException e) {
            throw new RegistryLoadException(path, e);
        }

        if (skipped > 0) {
            LOGGER.warn("Skipped {} unparseable record(s) while loading document registry from {}",
                skipped, path);
        }
    }

    /**
     * Formats metadata for persistence.
     *
     * <p>Format: {@code sourceId|documentId|lastModifiedMillis|contentHash|contentLength|indexTimeMillis|chunkId1,chunkId2,...}
     * with every free-text field escaped.</p>
     *
     * <p>Escaping is not optional here: document ids are filesystem paths and absolute URLs, and
     * chunk ids are built from them, so all three delimiters - {@code |}, {@code ,} and the
     * record-terminating newline - occur naturally in them. Written raw, one such id shifts every
     * later field of its record, and an embedded newline lets a document id forge a second record
     * on reload.</p>
     */
    private String formatMetadata(DocumentMetadata metadata) {
        StringBuilder chunkIds = new StringBuilder();
        for (String chunkId : metadata.getChunkIds()) {
            if (chunkIds.length() > 0) {
                chunkIds.append(CHUNK_SEPARATOR);
            }
            chunkIds.append(escape(chunkId));
        }

        return String.join(FIELD_SEPARATOR,
            escape(metadata.getSourceId()),
            escape(metadata.getDocumentId()),
            Long.toString(metadata.getLastModifiedMillis()),
            Long.toString(metadata.getContentHash()),
            Long.toString(metadata.getContentLength()),
            Long.toString(metadata.getIndexTimeMillis()),
            chunkIds.toString()
        );
    }

    /**
     * Parses metadata from a persisted line.
     *
     * @return The metadata, or null if the line is malformed
     */
    private DocumentMetadata parseMetadata(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }

        List<String> parts = splitEscaped(line, FIELD_SEPARATOR_CHAR);
        if (parts.size() < 6) {
            return null;
        }

        try {
            DocumentMetadata.Builder builder = DocumentMetadata.builder()
                .sourceId(unescape(parts.get(0)))
                .documentId(unescape(parts.get(1)))
                .lastModifiedMillis(Long.parseLong(parts.get(2)))
                .contentHash(Long.parseLong(parts.get(3)))
                .contentLength(Long.parseLong(parts.get(4)))
                .indexTimeMillis(Long.parseLong(parts.get(5)));

            if (parts.size() > 6 && !parts.get(6).isEmpty()) {
                for (String chunkId : splitEscaped(parts.get(6), CHUNK_SEPARATOR_CHAR)) {
                    if (!chunkId.isEmpty()) {
                        builder.addChunkId(unescape(chunkId));
                    }
                }
            }

            return builder.build();
        } catch (IllegalArgumentException e) {
            // NumberFormatException for a corrupt numeric field, or a builder rejection for a
            // record whose identifiers did not survive the round trip.
            return null;
        }
    }

    /**
     * Escapes the characters that carry structure in the persisted format.
     *
     * <p>{@code \\} first, so unescaping is unambiguous.</p>
     */
    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case ESCAPE_CHAR -> escaped.append(ESCAPE_CHAR).append(ESCAPE_CHAR);
                case FIELD_SEPARATOR_CHAR -> escaped.append(ESCAPE_CHAR).append('p');
                case CHUNK_SEPARATOR_CHAR -> escaped.append(ESCAPE_CHAR).append('c');
                case '\n' -> escaped.append(ESCAPE_CHAR).append('n');
                case '\r' -> escaped.append(ESCAPE_CHAR).append('r');
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /**
     * Reverses {@link #escape(String)}.
     */
    private static String unescape(String value) {
        if (value.indexOf(ESCAPE_CHAR) < 0) {
            return value;
        }

        StringBuilder plain = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != ESCAPE_CHAR || i + 1 >= value.length()) {
                plain.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case ESCAPE_CHAR -> plain.append(ESCAPE_CHAR);
                case 'p' -> plain.append(FIELD_SEPARATOR_CHAR);
                case 'c' -> plain.append(CHUNK_SEPARATOR_CHAR);
                case 'n' -> plain.append('\n');
                case 'r' -> plain.append('\r');
                default -> plain.append(ESCAPE_CHAR).append(next);
            }
        }
        return plain.toString();
    }

    /**
     * Splits on a delimiter, ignoring delimiters that were escaped.
     *
     * <p>A plain {@code String.split} would cut inside an escaped id and silently corrupt the
     * record it belongs to.</p>
     */
    private static List<String> splitEscaped(String value, char delimiter) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ESCAPE_CHAR && i + 1 < value.length()) {
                current.append(c).append(value.charAt(++i));
            } else if (c == delimiter) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());

        return parts;
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
        return version.get() != persistedVersion;
    }

    @Override
    public String toString() {
        return String.format("DocumentRegistry[size=%d, persisted=%s]", 
            documents.size(), persistencePath != null);
    }
}
