package com.eonmux.excalibur.ingest.registry;

import com.eonmux.excalibur.store.hash.XxHash64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *   <li>Entity tag - the validator the source's server named the indexed version by</li>
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
 * <h2>Record format</h2>
 * <p>A first line, {@code \#document-registry|<version>}, names the format. One line per
 * document follows, and a last line, {@code \#end|<count>|<crc>}, closes the file: how many
 * records precede it, and the CRC32C of every byte before it, header included, in eight
 * lowercase hexadecimal digits (see {@link EndLine}). The header cannot collide with a record and
 * neither can the closing line: {@link #escape(String)} doubles every backslash, so no formatted
 * record begins with a single one.</p>
 *
 * <p>A record may carry the entity tag as a last field after the chunk ids. It is persisted
 * rather than held in memory because it is what a re-crawl sends back as {@code If-None-Match}: a
 * node that forgot it at every restart would transfer every unchanged page again on its first
 * re-crawl after one, which is exactly the crawl a restarted node runs. The field is optional
 * rather than tied to a version: a record that ends at the chunk ids reads as a document without
 * a tag, in every version.</p>
 *
 * <p>Each part of the frame answers a different failure. A registry that lost its tail is a
 * registry of whole, well-formed records that simply omits documents, and those documents are
 * then neither erasable nor reindexable while their chunks stay in storage and stay queryable -
 * the count is what makes that detectable. A record altered in place is a different but equally
 * well-formed record - another document's chunk ids, a content hash that makes a changed
 * document look unchanged - and only the checksum sees it. The header is what tells a short file
 * from an old one: truncation takes the tail, so a header is present in every file this build
 * wrote, and its absence can only mean a build that wrote no header.</p>
 *
 * <h2>Reading an older or newer file</h2>
 * <p>Version 1 closed with {@code \#end|<count>} and no checksum; it is read as it always was,
 * with its count checked and its records unverified. A file with no header at all is older still:
 * its lines are records, there is nothing to check them against, and it is loaded with a warning
 * saying so. Either is marked as owing a save, so the next save rewrites it in the current
 * format - and the node's start-up makes that save at once rather than waiting for the first
 * ingest or erasure. A file whose header names a version this build does not know is refused
 * rather than reinterpreted - guessing at an unknown layout is how a registry turns into a
 * shorter registry. A file carrying a header and no matching closing line was cut short by a
 * crash, and one whose checksum does not match its bytes was altered after it was written; both
 * are refused for the same reason {@link RegistryLoadException} exists.</p>
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

    /**
     * The format this build writes, and the newest it reads.
     *
     * <p>Public for {@code excalibur-fsck} and {@code excalibur-backup verify --deep}, which
     * judge this file offline and must accept exactly what this class accepts.</p>
     */
    public static final int FORMAT_VERSION = 2;

    /**
     * The first version whose closing line carries a checksum.
     *
     * <p>Public for {@code excalibur-fsck} and {@code excalibur-backup verify --deep}, which
     * judge this file offline and must accept exactly what this class accepts.</p>
     */
    public static final int FIRST_CHECKSUMMED_VERSION = 2;

    /**
     * The oldest versioned format this build reads.
     *
     * <p>A file with no header at all is older still and is read too; see the class
     * documentation.</p>
     *
     * <p>Public for {@code excalibur-fsck} and {@code excalibur-backup verify --deep}, which
     * judge this file offline and must accept exactly what this class accepts.</p>
     */
    public static final int OLDEST_READABLE_VERSION = 1;

    /**
     * Opens the first line, which names the format the rest of the file is in.
     *
     * <p>Public for {@code excalibur-fsck} and {@code excalibur-backup verify --deep}, which
     * judge this file offline and must accept exactly what this class accepts.</p>
     */
    public static final String HEADER_MARKER = "\\#document-registry";

    /**
     * Opens the last line, which states how many records precede it and, from {@link
     * #FIRST_CHECKSUMMED_VERSION}, the checksum of every byte before it.
     *
     * <p>Public for {@code excalibur-fsck} and {@code excalibur-backup verify --deep}, which
     * judge this file offline and must accept exactly what this class accepts.</p>
     */
    public static final String END_MARKER = "\\#end";

    /** What {@link #declaredVersion(String)} reports for a file written before the header. */
    private static final int NO_HEADER = -1;

    /** What {@link #declaredVersion(String)} reports for a header whose version is not a number. */
    private static final int UNREADABLE_HEADER = -2;

    private final ConcurrentHashMap<String, DocumentMetadata> documents;
    /** Extension of the file a save is written to before it is renamed over the registry. */
    private static final String REPLACEMENT_SUFFIX = ".new";

    /** Distinguishes one save's replacement file from another's. */
    private static final java.util.concurrent.atomic.AtomicLong saveCounter =
        new java.util.concurrent.atomic.AtomicLong();

    private final Path persistencePath;
    private final RegistryWriter writer;
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
     * @throws RegistryLoadException if a registry exists at that path and cannot be trusted
     */
    public DocumentRegistry(Path persistencePath) {
        this(persistencePath, fileSystemWriter());
    }

    /**
     * Creates a registry against an explicit set of disk operations.
     *
     * <p>Exists so a test can observe the order the contents and the rename are made durable in,
     * which is the whole of the crash-safety this class claims and is otherwise only visible to
     * a power cut.</p>
     *
     * @param persistencePath Path to the registry file, or null for in-memory only
     * @param writer          The operations that publish a save
     * @throws NullPointerException  if the writer is null
     * @throws RegistryLoadException if a registry exists at that path and cannot be trusted
     */
    DocumentRegistry(Path persistencePath, RegistryWriter writer) {
        this.documents = new ConcurrentHashMap<>();
        this.persistencePath = persistencePath;
        this.writer = Objects.requireNonNull(writer, "writer must not be null");

        if (persistencePath != null && Files.exists(persistencePath)) {
            loadFromFile(persistencePath);
        }
    }

    /**
     * Where this registry is kept, so that what belongs beside it can be put beside it.
     *
     * @return The registry file, or null when this registry is in memory only
     */
    public Path persistencePath() {
        return persistencePath;
    }

    /**
     * The disk operations that publish a saved registry.
     *
     * <p>Durability here is an ordering rather than any single call: the replacement's contents
     * have to reach the platter before the rename that names it the registry, and the rename is
     * itself a directory change that is no more durable than the contents were. A test cannot cut
     * the power between two of those steps, so the ordering is pinned by recording the steps as
     * they are taken.</p>
     */
    interface RegistryWriter {

        /**
         * Forces a written file's contents to the underlying device.
         *
         * @param path The file to force
         * @throws IOException if the contents cannot be made durable
         */
        void force(Path path) throws IOException;

        /**
         * Renames a finished replacement over the live registry.
         *
         * @param replacement The file holding the new contents
         * @param target      The registry path
         * @throws IOException if the replacement cannot be published
         */
        void publish(Path replacement, Path target) throws IOException;

        /**
         * Forces the directory entry a rename has just changed.
         *
         * @param directory The directory holding the registry
         */
        void forceDirectory(Path directory);
    }

    /**
     * Returns the operations as the filesystem performs them.
     *
     * @return A writer that forces and renames for real
     */
    static RegistryWriter fileSystemWriter() {
        return new FileSystemWriter();
    }

    /**
     * Thrown when a persisted registry exists but cannot be read, or cannot be trusted.
     *
     * <p>An unreadable registry is not an empty registry: starting empty makes every previously
     * indexed document look new, so a re-crawl duplicates the whole corpus and tombstones none of
     * the old chunks. A registry cut short is the same failure over the documents it lost, and is
     * refused for the same reason. Failing here is the only way the caller can tell either from a
     * node that has genuinely indexed nothing.</p>
     */
    public static final class RegistryLoadException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        RegistryLoadException(Path path, IOException cause) {
            super("Failed to load document registry from " + path, cause);
        }

        RegistryLoadException(Path path, String reason) {
            super("Failed to load document registry from " + path + ": " + reason);
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
     * Replaces a document's metadata, but only if it is still exactly what the caller read.
     *
     * <p>For a caller that derived the replacement from an earlier read and must not overwrite a
     * newer registration made in between - a re-crawl refreshing the validators of an unchanged
     * document while a reindex of the same document registers the version it just indexed. The
     * reindex's metadata describes the chunks in storage; overwriting it with a copy of the old
     * entry would point the registry at chunks that no longer exist.</p>
     *
     * @param expected    The metadata the caller read, compared by equality
     * @param replacement What to register in its place, for the same document
     * @return true if the replacement was registered, false if the entry had changed or gone
     * @throws NullPointerException     if either argument is null
     * @throws IllegalArgumentException if the two name different documents
     */
    public boolean replace(DocumentMetadata expected, DocumentMetadata replacement) {
        Objects.requireNonNull(expected, "expected must not be null");
        Objects.requireNonNull(replacement, "replacement must not be null");
        if (!expected.getSourceId().equals(replacement.getSourceId())
                || !expected.getDocumentId().equals(replacement.getDocumentId())) {
            throw new IllegalArgumentException("replacement names " + replacement.getSourceId()
                + KEY_SEPARATOR + replacement.getDocumentId() + ", not "
                + expected.getSourceId() + KEY_SEPARATOR + expected.getDocumentId());
        }
        String key = makeKey(expected.getSourceId(), expected.getDocumentId());
        if (!documents.replace(key, expected, replacement)) {
            return false;
        }
        version.incrementAndGet();
        return true;
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
            com.eonmux.excalibur.ingest.source.FetchedDocument document) {
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
     * same failure the rename exists to prevent, arrived at from the other side. The directory
     * is forced after the rename, because a rename is a directory change and is no more durable
     * on its own than the contents were.</p>
     *
     * <p>Forcing the contents is not best effort. Every caller of this method - an ingest run, an
     * erasure, a reindex, shutdown - goes on to behave as though the documents it holds are
     * recorded, so a force that failed has to reach them rather than be logged and passed over.
     * Forcing the directory is best effort: a platform that will not open a directory channel is
     * not a reason to fail a save whose contents are otherwise on disk.</p>
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
                write(replacement);
                writer.force(replacement);
                writer.publish(replacement, persistencePath);
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

            if (parent != null) {
                writer.forceDirectory(parent);
            }
            persistedVersion = writing;
        }
    }

    /**
     * Writes the header, every record, and the closing line that proves the file is whole.
     *
     * <p>The count is taken from the same pass that writes the records rather than from
     * {@code documents.size()}, so it states what actually reached the file, and the checksum
     * from the bytes as they were written.</p>
     */
    private void write(Path replacement) throws IOException {
        try (ChecksummedTextWriter out = ChecksummedTextWriter.create(replacement,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            out.writeLine(HEADER_MARKER + FIELD_SEPARATOR + FORMAT_VERSION);

            int written = 0;
            for (DocumentMetadata metadata : documents.values()) {
                out.writeLine(formatMetadata(metadata));
                written++;
            }

            out.writeEnd(END_MARKER, written);
        }
    }

    /**
     * The operations as the filesystem performs them.
     */
    private static final class FileSystemWriter implements RegistryWriter {

        @Override
        public void force(Path path) throws IOException {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }

        @Override
        public void publish(Path replacement, Path target) throws IOException {
            try {
                Files.move(replacement, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // A filesystem that cannot rename atomically leaves no better option than the
                // replacing move. Said out loud rather than silently accepted: on such a
                // deployment the window the rename closes is open again.
                LOGGER.warn("This filesystem cannot rename atomically; the document registry is "
                    + "published with a plain replace and a crash during it can truncate the file");
                Files.move(replacement, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        @Override
        public void forceDirectory(Path directory) {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            } catch (IOException e) {
                LOGGER.warn("Could not force the directory entry for the document registry at {}; "
                    + "a host crash immediately after a save can lose the rename that published "
                    + "it: {}", directory, e.getMessage());
            }
        }
    }

    /**
     * Loads the registry from a file, refusing one that cannot be trusted to be whole.
     *
     * @param path The path to load from
     * @throws RegistryLoadException if the file exists but cannot be read, names a format this
     *                               build does not know, is missing records it declares, or does
     *                               not match its checksum
     */
    private void loadFromFile(Path path) {
        try (ChecksummedLineReader reader = ChecksummedLineReader.open(path)) {
            String first = reader.readLine();
            if (first == null) {
                // Indistinguishable from a registry an older build wrote while holding nothing,
                // and the consequence of reading it as empty is the documented one for a missing
                // registry: the next re-crawl treats every document as new.
                LOGGER.warn("Document registry at {} is empty; every document will read as new "
                    + "to the next re-crawl", path);
                return;
            }

            int version = declaredVersion(first);
            if (version == NO_HEADER) {
                loadWithoutHeader(path, first, reader);
                return;
            }
            if (version == UNREADABLE_HEADER) {
                throw new RegistryLoadException(path,
                    "its first line names no format version this build can read: " + first);
            }
            if (version < OLDEST_READABLE_VERSION || version > FORMAT_VERSION) {
                throw new RegistryLoadException(path, "it declares format version " + version
                    + " and this build reads version " + FORMAT_VERSION);
            }
            loadRecords(path, reader, version);
        } catch (IOException e) {
            throw new RegistryLoadException(path, e);
        }
    }

    /**
     * Loads a file that names its format, checking it against the line it closes with.
     *
     * <p>Held one line behind, because the last line is the closing line rather than a record
     * and a registry is too large to read into a list first only to drop its tail. The checksum
     * the reader reports before the last line is the checksum of everything the closing line
     * vouches for.</p>
     *
     * <p>A version this build no longer writes is marked as owing a save, so the next one
     * rewrites it in the current format - with a checksum its records do not carry yet.</p>
     */
    private void loadRecords(Path path, ChecksummedLineReader reader, int fileVersion)
            throws IOException {
        int records = 0;
        int skipped = 0;
        String previous = reader.readLine();
        long beforePrevious = reader.checksumBeforeLine();
        String line;
        while ((line = reader.readLine()) != null) {
            records++;
            if (!accept(previous)) {
                skipped++;
            }
            previous = line;
            beforePrevious = reader.checksumBeforeLine();
        }

        boolean checksummed = fileVersion >= FIRST_CHECKSUMMED_VERSION;
        EndLine end = EndLine.parse(previous, END_MARKER, checksummed);
        if (end == null) {
            throw new RegistryLoadException(path, "it does not end with a record count"
                + (checksummed ? " and checksum" : "") + ", so it was cut short before the save "
                + "that published it finished");
        }
        if (end.count() != records) {
            throw new RegistryLoadException(path,
                "it declares " + end.count() + " record(s) and carries " + records);
        }
        if (checksummed && end.checksum() != beforePrevious) {
            throw new RegistryLoadException(path, end.mismatch(beforePrevious));
        }
        if (!checksummed) {
            version.incrementAndGet();
            LOGGER.info("Document registry at {} is in format version {}, whose records carry no "
                + "checksum; it will be rewritten in version {} at the next save", path,
                fileVersion, FORMAT_VERSION);
        }
        reportSkipped(path, skipped);
    }

    /**
     * Loads a file from a build that wrote neither a header nor a count.
     *
     * <p>Every line is a record and nothing states how many there should be, so a file this
     * shape cannot be checked for truncation - which is the whole reason the header exists. It
     * is accepted rather than refused because refusing would strand a working node on an upgrade,
     * and the registry is rewritten in the current format at the next save: the version counter
     * is bumped so the save actually happens, even if nothing is ingested or erased first.</p>
     */
    private void loadWithoutHeader(Path path, String first, ChecksummedLineReader reader)
            throws IOException {
        int records = 0;
        int skipped = 0;
        for (String line = first; line != null; line = reader.readLine()) {
            records++;
            if (!accept(line)) {
                skipped++;
            }
        }

        version.incrementAndGet();
        LOGGER.warn("Document registry at {} was written before the format carried a version, so "
            + "its {} record(s) cannot be checked against a count; it will be rewritten in "
            + "version {} at the next save", path, records, FORMAT_VERSION);
        reportSkipped(path, skipped);
    }

    /**
     * Parses one line and registers what it describes.
     *
     * @return false if the line is not a record this build can read
     */
    private boolean accept(String line) {
        DocumentMetadata metadata = parseMetadata(line);
        if (metadata == null) {
            return false;
        }
        documents.put(makeKey(metadata.getSourceId(), metadata.getDocumentId()), metadata);
        return true;
    }

    private static void reportSkipped(Path path, int skipped) {
        if (skipped > 0) {
            LOGGER.warn("Skipped {} unparseable record(s) while loading document registry from {}",
                skipped, path);
        }
    }

    /**
     * Reads the format version out of a candidate header line.
     *
     * @return The declared version, {@link #NO_HEADER} if the line is not a header, or -2 if it
     *         is a header whose version is not a number this build can compare
     */
    private static int declaredVersion(String line) {
        String prefix = HEADER_MARKER + FIELD_SEPARATOR;
        if (!line.startsWith(prefix)) {
            return NO_HEADER;
        }
        try {
            return Integer.parseInt(line.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return UNREADABLE_HEADER;
        }
    }

    /**
     * Formats metadata for persistence.
     *
     * <p>Format: {@code sourceId|documentId|lastModifiedMillis|contentHash|contentLength|indexTimeMillis|chunkId1,chunkId2,...|entityTag}
     * with every free-text field escaped, and an empty last field for a document with no entity
     * tag. The tag comes last, where a reader that knows nothing of it - the build before this
     * one - reads past it, and where a record that ends at the chunk ids is a record with no
     * tag.</p>
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
            chunkIds.toString(),
            metadata.getEntityTag() == null ? "" : escape(metadata.getEntityTag())
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
            if (parts.size() > 7 && !parts.get(7).isEmpty()) {
                builder.entityTag(unescape(parts.get(7)));
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
