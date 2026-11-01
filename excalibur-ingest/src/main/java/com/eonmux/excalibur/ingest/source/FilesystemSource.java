package com.eonmux.excalibur.ingest.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Filesystem-based document source for local file ingestion.
 *
 * <p>Crawls a local directory tree and fetches individual files as documents.
 * Supports configurable file extensions, exclusion patterns, and maximum depth.</p>
 *
 * <h2>Document IDs</h2>
 * <p>Document IDs are relative paths from the root directory, using forward slashes
 * as separators (e.g., "docs/readme.txt", "data/reports/2024/q1.csv").</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The configuration is immutable after construction.
 * Multiple threads may call {@link #crawl()} and {@link #fetch(String)} concurrently.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Root path must be an existing, readable directory</li>
 *   <li>File extensions are case-insensitive (e.g., ".TXT" == ".txt")</li>
 *   <li>Maximum file size: 1 GiB for a PDF and 100 MiB for anything else, the largest documents
 *       ingestion indexes (configurable downwards). A larger file is still listed, and refused
 *       when it is fetched, so a run counts it failed rather than a re-crawl taking it as
 *       deleted</li>
 *   <li>Symlinks are not followed by default (configurable). When they are followed, a path
 *       whose real location lies outside the root is not part of the source</li>
 *   <li>Hidden files are excluded by default (configurable), and so is everything under a
 *       hidden directory below the root</li>
 *   <li>An excluded directory name is matched at any depth, and the walk does not enter it</li>
 * </ul>
 *
 * <h2>Encoding</h2>
 * <p>A file carries no {@code Content-Type}, so the encoding its bytes are in comes from two
 * places: a byte-order mark at the front of the file, and the {@code charset} the operator
 * declared for the whole source. The mark wins where there is one - it describes this file,
 * where the declaration describes a directory someone else may have written into - and a file
 * with neither is read as UTF-8. See {@link TextEncoding} for the reasoning behind that
 * order.</p>
 *
 * <h2>Resource Management</h2>
 * <p>The {@link #crawl()} stream holds open directory handles. Callers should
 * close the stream when done, typically via try-with-resources.</p>
 *
 * @see Source
 * @see FetchedDocument
 */
public class FilesystemSource implements Source {

    private static final Logger LOG = LoggerFactory.getLogger(FilesystemSource.class);

    private static final String SOURCE_TYPE = "filesystem";

    private static final long DEFAULT_MAX_FILE_SIZE = DocumentLimits.MAX_PDF_BYTES;
    private static final int DEFAULT_MAX_DEPTH = 100;
    private static final long MILLIS_PER_SECOND = 1000L;

    private static final Map<String, String> EXTENSION_TO_MIME_TYPE = Map.ofEntries(
        Map.entry(".txt", "text/plain"),
        Map.entry(".md", "text/markdown"),
        Map.entry(".html", "text/html"),
        Map.entry(".htm", "text/html"),
        Map.entry(".xhtml", "application/xhtml+xml"),
        Map.entry(".css", "text/css"),
        Map.entry(".csv", "text/csv"),
        Map.entry(".json", "application/json"),
        Map.entry(".xml", "application/xml"),
        Map.entry(".yaml", "application/x-yaml"),
        Map.entry(".yml", "application/x-yaml"),
        Map.entry(".pdf", "application/pdf"),
        Map.entry(".doc", "application/msword"),
        Map.entry(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        Map.entry(".js", "application/javascript"),
        Map.entry(".ts", "application/typescript"),
        Map.entry(".java", "text/x-java-source"),
        Map.entry(".py", "text/x-python"),
        Map.entry(".c", "text/x-c"),
        Map.entry(".cpp", "text/x-c++"),
        Map.entry(".h", "text/x-c"),
        Map.entry(".rs", "text/x-rust"),
        Map.entry(".go", "text/x-go"),
        Map.entry(".rb", "text/x-ruby"),
        Map.entry(".php", "text/x-php"),
        Map.entry(".sh", "text/x-shellscript"),
        Map.entry(".sql", "application/sql"),
        Map.entry(".rtf", "application/rtf"),
        Map.entry(".svg", "image/svg+xml"),
        Map.entry(".png", "image/png"),
        Map.entry(".jpg", "image/jpeg"),
        Map.entry(".jpeg", "image/jpeg"),
        Map.entry(".gif", "image/gif"),
        Map.entry(".webp", "image/webp")
    );

    private final String sourceId;
    private final Path rootPath;
    private final Path realRootPath;
    private final Set<String> includedExtensions;
    private final Set<String> excludedDirectories;
    private final boolean followSymlinks;
    private final boolean includeHidden;
    private final long maxFileSize;
    private final int maxDepth;

    /** The encoding the operator declared for files without a byte-order mark, or null. */
    private final Charset charset;
    private volatile boolean closed = false;

    /**
     * The files the current run could not judge, and so left out of its listing; replaced when
     * a crawl begins.
     */
    private volatile ListingGaps.Recorder listingGaps = new ListingGaps.Recorder();

    private FilesystemSource(Builder builder) {
        this.sourceId = Objects.requireNonNull(builder.sourceId, "sourceId must not be null");
        if (sourceId.isEmpty()) {
            throw new IllegalArgumentException("sourceId must not be empty");
        }
        if (sourceId.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(
                "sourceId must not exceed " + MAX_ID_LENGTH + " characters");
        }

        this.rootPath = Objects.requireNonNull(builder.rootPath, "rootPath must not be null")
            .toAbsolutePath()
            .normalize();

        if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                "Root path must be an existing directory: " + rootPath
            );
        }

        try {
            this.realRootPath = rootPath.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Root path could not be resolved: " + rootPath, e);
        }

        this.includedExtensions = builder.includedExtensions.isEmpty()
            ? Set.of()
            : Set.copyOf(normalizeExtensions(builder.includedExtensions));
        this.excludedDirectories = Set.copyOf(normalizeNames(builder.excludedDirectories));
        this.followSymlinks = builder.followSymlinks;
        this.includeHidden = builder.includeHidden;
        this.maxFileSize = Math.max(1,
            DocumentLimits.requireIndexable(builder.maxFileSize, "maxFileSize"));
        this.maxDepth = Math.max(1, builder.maxDepth);
        this.charset = builder.charset;

        LOG.debug("FilesystemSource created: id={}, root={}, extensions={}, excluded={}, "
                + "charset={}", sourceId, rootPath, includedExtensions, excludedDirectories,
            charset != null ? charset : "undeclared");
    }

    @Override
    public String getSourceId() {
        return sourceId;
    }

    @Override
    public String getSourceType() {
        return SOURCE_TYPE;
    }

    @Override
    public String getDescription() {
        return SOURCE_TYPE + ":" + rootPath;
    }

    @Override
    public Stream<String> crawl() throws SourceException {
        ensureOpen();

        LOG.info("Starting crawl for source {}: {}", sourceId, rootPath);

        LinkOption[] linkOptions = linkOptions();
        ListingGaps.Recorder gaps = new ListingGaps.Recorder();
        listingGaps = gaps;

        try {
            return walk()
                .map(this::stillListing)
                .filter(path -> shouldProcessFile(path, linkOptions, gaps))
                .map(this::toDocumentId)
                .filter(Objects::nonNull)
                .onClose(() -> LOG.debug("Crawl completed for source {}", sourceId));

        } catch (IOException e) {
            throw new SourceException(
                "Failed to start crawl for source: " + sourceId,
                sourceId,
                true,
                e
            );
        }
    }

    @Override
    public FetchedDocument fetch(String documentId) throws SourceException, DocumentNotFoundException {
        ensureOpen();
        Objects.requireNonNull(documentId, "documentId must not be null");
        if (documentId.isEmpty()) {
            throw new IllegalArgumentException("documentId must not be empty");
        }

        Path filePath = resolveForFetch(documentId);
        LinkOption[] linkOptions = linkOptions();

        try {
            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class, linkOptions);
            long fileSize = attrs.size();

            if (fileSize > maxFileSize) {
                throw new DocumentRefusedException(
                    "File too large: " + fileSize + " bytes (max: " + maxFileSize + "): " + documentId,
                    sourceId
                );
            }
            // Only a PDF may be larger than a text document; its header says whether it is one,
            // and reading the header first keeps a large file of any other kind out of memory.
            if (fileSize > DocumentLimits.MAX_DOCUMENT_BYTES && !isPdf(filePath)) {
                throw new DocumentRefusedException(
                    "File too large: " + fileSize + " bytes (max: "
                        + DocumentLimits.MAX_DOCUMENT_BYTES + " for a document that is not a PDF): "
                        + documentId,
                    sourceId
                );
            }

            byte[] content = Files.readAllBytes(filePath);
            String contentType = detectContentType(filePath);
            long lastModifiedMillis = attrs.lastModifiedTime().toMillis();
            Charset encoding = TextEncoding.resolve(content, charset);

            LOG.debug("Fetched document: {} ({} bytes, {}, {})",
                documentId, content.length, contentType, encoding);

            // Handed over rather than copied: nothing else holds the array just read, and a
            // copy of the largest PDF would be another gigabyte of heap.
            return FetchedDocument.adopting(documentId, content, contentType, encoding,
                lastModifiedMillis, null);

        } catch (IOException e) {
            if (e instanceof java.nio.file.NoSuchFileException) {
                throw new DocumentNotFoundException(documentId, sourceId, e);
            }
            throw new SourceException(
                "Failed to fetch document: " + documentId,
                sourceId,
                true,
                e
            );
        }
    }

    @Override
    public long getDocumentCount() throws SourceException {
        ensureOpen();

        try (Stream<Path> paths = walk()) {
            LinkOption[] linkOptions = linkOptions();

            long count = paths
                .filter(path -> shouldProcessFile(path, linkOptions, null))
                .count();

            LOG.debug("Document count for source {}: {}", sourceId, count);
            return count;

        } catch (IOException e) {
            throw new SourceException(
                "Failed to count documents for source: " + sourceId,
                sourceId,
                true,
                e
            );
        } catch (UncheckedIOException e) {
            throw new SourceException(e.getMessage(), sourceId, false, e.getCause());
        }
    }

    @Override
    public long getEstimatedTotalSize() throws SourceException {
        ensureOpen();

        try (Stream<Path> paths = walk()) {
            LinkOption[] linkOptions = linkOptions();

            long totalSize = paths
                .filter(path -> shouldProcessFile(path, linkOptions, null))
                .mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .sum();

            LOG.debug("Estimated total size for source {}: {} bytes", sourceId, totalSize);
            return totalSize;

        } catch (IOException e) {
            throw new SourceException(
                "Failed to estimate size for source: " + sourceId,
                sourceId,
                true,
                e
            );
        } catch (UncheckedIOException e) {
            throw new SourceException(e.getMessage(), sourceId, false, e.getCause());
        }
    }

    @Override
    public void close() {
        closed = true;
        LOG.debug("FilesystemSource closed: {}", sourceId);
    }

    public Path getRootPath() {
        return rootPath;
    }

    public Set<String> getIncludedExtensions() {
        return includedExtensions;
    }

    public Set<String> getExcludedDirectories() {
        return excludedDirectories;
    }

    public boolean isFollowSymlinks() {
        return followSymlinks;
    }

    public boolean isIncludeHidden() {
        return includeHidden;
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    /**
     * The encoding declared for files that carry no byte-order mark.
     *
     * @return The declared charset, or null when none was declared and such files are read as
     *         UTF-8
     */
    public Charset getCharset() {
        return charset;
    }

    private void ensureOpen() throws SourceException {
        if (closed) {
            throw new SourceException("Source is closed: " + sourceId, sourceId, false);
        }
    }

    private LinkOption[] linkOptions() {
        return followSymlinks
            ? new LinkOption[0]
            : new LinkOption[]{LinkOption.NOFOLLOW_LINKS};
    }

    /**
     * Walks the tree the way this source is configured to see it.
     *
     * <p>The crawl, the document count and the size estimate all have to describe the same set
     * of files, so they all come through here rather than each deciding for itself whether links
     * are part of the tree.</p>
     *
     * <p>Excluded and hidden directories are pruned rather than filtered: the walk never opens
     * them, so an excluded {@code node_modules} costs nothing however large it is, and a link
     * cycle inside one cannot abort a crawl that was never going to list its contents.</p>
     */
    private Stream<Path> walk() throws IOException {
        return abortingClearly(PrunedWalk.of(rootPath, maxDepth, followSymlinks,
            this::shouldDescend));
    }

    /**
     * Decides whether the walk enters a directory below the root.
     *
     * <p>The root itself is never asked about: an operator who registers a hidden directory, or
     * one whose name is on the exclusion list, asked for exactly that directory.</p>
     *
     * @param directory A directory below the root
     * @return Whether anything under it can be a document of this source
     */
    private boolean shouldDescend(Path directory) {
        Path name = directory.getFileName();
        if (name != null && excludedDirectories.contains(normalizeName(name.toString()))) {
            return false;
        }
        return includeHidden || !isHidden(directory);
    }

    /**
     * Wraps a walk so a failure that surfaces while it is being consumed arrives as one clear
     * error instead of an anonymous one from wherever the stream happened to be pulled.
     *
     * <p>The walk is lazy, and with links followed it reports a symlink cycle as an
     * {@link UncheckedIOException} raised mid-traversal rather than from the call that started
     * the walk. The walk must still fail: a caller handed a short listing would take every
     * document the walk never reached as deleted and tombstone it. So the error is re-thrown,
     * carrying the source it belongs to and saying that the listing is incomplete.</p>
     */
    private <T> Stream<T> abortingClearly(Stream<T> walk) {
        Spliterator<T> elements = walk.spliterator();
        Spliterator<T> guarded = new Spliterators.AbstractSpliterator<>(
            elements.estimateSize(), elements.characteristics()) {

            @Override
            public boolean tryAdvance(Consumer<? super T> action) {
                try {
                    return elements.tryAdvance(action);
                } catch (UncheckedIOException e) {
                    throw walkAborted(e);
                }
            }
        };
        return StreamSupport.stream(guarded, false).onClose(walk::close);
    }

    /**
     * Checks, as the walk is consumed, that the source has not been closed underneath it.
     *
     * <p>Filtering the closed case out instead ends the stream normally, and a listing that ends
     * normally is read as the source's whole contents: change detection subtracts it from the
     * registry and the reindex tombstones the difference, so a walk stopped near its start
     * deletes most of the corpus. Deregistering a source closes it while its crawl may still be
     * running, which is the ordinary way this happens.</p>
     */
    private Path stillListing(Path path) {
        if (closed) {
            LOG.error("Walk of source {} under {} aborted: the source was closed while the crawl "
                + "was still running", sourceId, rootPath);
            throw new IncompleteCrawlException(
                "Walk of source " + sourceId + " aborted at " + rootPath + ": the source was "
                    + "closed while the crawl was still running. What it listed is not the full "
                    + "contents of the source and must not be read as one: the documents it "
                    + "never reached still exist.");
        }
        return path;
    }

    private UncheckedIOException walkAborted(UncheckedIOException cause) {
        IOException reason = cause.getCause();
        String what = reason instanceof FileSystemLoopException loop
            ? "a symlink cycle at " + loop.getFile()
            : reason.toString();
        LOG.error("Walk of source {} under {} aborted: {}", sourceId, rootPath, what);
        return new UncheckedIOException(
            "Walk of source " + sourceId + " aborted at " + rootPath + ": " + what
                + ". What it listed is not the full contents of the source and must not be read "
                + "as one: the documents it never reached still exist.",
            reason);
    }

    /**
     * Resolves a document id to the file to read, refusing one that is not part of this source.
     *
     * @param documentId The document id to resolve
     * @return The file to read
     * @throws DocumentNotFoundException if nothing exists at the resolved path
     * @throws SourceException if the path is not a regular file, or leaves the source root
     */
    private Path resolveForFetch(String documentId)
            throws SourceException, DocumentNotFoundException {
        Path filePath = toFilePath(documentId);
        LinkOption[] linkOptions = linkOptions();

        if (!Files.exists(filePath, linkOptions)) {
            throw new DocumentNotFoundException(documentId, sourceId);
        }

        if (!Files.isRegularFile(filePath, linkOptions)) {
            throw new DocumentRefusedException(
                "Document is not a regular file: " + documentId,
                sourceId
            );
        }

        if (followSymlinks) {
            switch (containment(filePath)) {
                case INSIDE -> { }
                case OUTSIDE -> throw new DocumentRefusedException(
                    "Document " + documentId + " is reached through a link that leaves the source "
                        + "root " + rootPath,
                    sourceId);
                case GONE -> throw new DocumentNotFoundException(documentId, sourceId);
                case UNRESOLVED -> throw new SourceException(
                    "Could not resolve where document " + documentId + " really is", sourceId,
                    true);
            }
        }

        return filePath;
    }

    /**
     * The files the latest crawl left out because it could not judge them: their attributes
     * could not be read, or, following links, their real location could not be resolved.
     *
     * <p>Read once the crawl's stream has been consumed, since the walk is lazy. Every other
     * way a walk can fall short - a directory that cannot be listed, a symlink cycle, the source
     * closed underneath it - ends the crawl with an error instead, because what was listed up
     * to then says nothing about the rest of the tree. A file that cannot be judged says nothing
     * about any other file, so the listing stays sound around it.</p>
     */
    @Override
    public ListingGaps getListingGaps() {
        return listingGaps.snapshot();
    }

    /**
     * Decides whether a path reached by following links is still part of this source.
     *
     * <p>Containment is a property of the file, not of the name it was reached by. A deployment
     * confines a source to an allowed root by canonicalising that root, and a link inside it
     * would otherwise pull in a file from anywhere on the machine under a document id that
     * relativises cleanly against the root - the textual path stays inside while the bytes come
     * from outside. Comparing real path against real root is what actually closes that.</p>
     *
     * <p>A path whose real location cannot be read is refused too, although a file this node
     * merely cannot read is kept in the crawl and left for its fetch to fail: a file that cannot
     * be shown to lie inside the source may lie outside it, and indexing the contents of an
     * unknown file is worse than leaving one file out of the crawl. Left out, it is a gap in
     * the listing rather than a deletion: the question of where it lies went unanswered, so a
     * re-crawl keeps whatever version of it was indexed, and reports the gap.</p>
     *
     * @param path A path reached by following links
     * @param gaps Told of a path whose real location could not be resolved, or null when the walk
     *             is not a crawl
     */
    private boolean staysInsideRoot(Path path, ListingGaps.Recorder gaps) {
        Containment where = containment(path);
        if (where == Containment.UNRESOLVED && gaps != null) {
            String documentId = toDocumentId(path);
            if (documentId != null) {
                gaps.record(documentId, "real location not resolved");
            }
        }
        return where == Containment.INSIDE;
    }

    /**
     * Where a path reached by following links really lies, as far as can be told.
     *
     * <p>Only {@link Containment#INSIDE} is admitted. The others are told apart so that a fetch
     * reports each as what it is: a link leaving the root is the source's answer about the file,
     * a file gone before it could be resolved is a deletion, and anything else is a question that
     * went unanswered.</p>
     */
    private Containment containment(Path path) {
        try {
            Path realPath = path.toRealPath();
            if (realPath.startsWith(realRootPath)) {
                return Containment.INSIDE;
            }
            LOG.warn("Skipping {}: it resolves to {}, so the link leaves the source root {}.",
                path, realPath, realRootPath);
            return Containment.OUTSIDE;

        } catch (NoSuchFileException e) {
            return Containment.GONE;
        } catch (IOException e) {
            LOG.warn("Skipping {}: its real location could not be resolved ({}), so it cannot be "
                + "shown to lie inside the source root {}.", path, e.getMessage(), realRootPath);
            return Containment.UNRESOLVED;
        }
    }

    /**
     * Whether a file is a PDF, judged from its first and last kilobyte without reading the rest.
     *
     * @param file The file
     * @return Whether {@link PdfSignature} takes it for a PDF
     * @throws IOException if the file cannot be read
     */
    private static boolean isPdf(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            int window = (int) Math.min(size, PdfSignature.SEARCH_WINDOW);
            return PdfSignature.isPdf(readFully(channel, 0, window),
                readFully(channel, size - window, window));
        }
    }

    private static ByteBuffer readFully(FileChannel channel, long position, int length)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) {
                break;
            }
        }
        return buffer.flip();
    }

    /** Where a path reached by following links lies; see {@link #containment}. */
    private enum Containment { INSIDE, OUTSIDE, GONE, UNRESOLVED }

    /**
     * Decides whether a path the walk reached is a document of this source.
     *
     * <p>What this admits is also what tells a re-crawl which documents still exist: a registered
     * document absent from the crawl is taken as deleted and its chunks are tombstoned. So it
     * judges only what makes a path part of the source - a regular file, reached as the source is
     * configured to reach it, visible, of an included extension, inside the root - and never
     * whether the file can be indexed as it now stands, nor anything it would have to read the
     * file's attributes to know. A file that grew past {@code maxFileSize} is still listed and
     * refused by {@link #fetch}: a re-crawl keeps the version it indexed before and counts the
     * document failed, as it does an HTTP response grown past its limit, where leaving it out of
     * the listing erased it.</p>
     *
     * <p>Whether it is a regular file does need its attributes, and there the answer is
     * sometimes that there is none: a path that is gone - a link to nothing, a file deleted as
     * the walk passed - is not a document, and leaves the listing. Attributes that could not be
     * read for any other reason - a link into a directory this node may not search, a network
     * mount that failed - say nothing about the file, so it is left out as a gap in the listing
     * rather than as a deletion: a re-crawl keeps the version indexed before, and reports the
     * gap. The name tests go first, so a file the source would never list is never a gap.</p>
     *
     * @param path        A path the walk reached
     * @param linkOptions How links are treated, as the source is configured
     * @param gaps        Told of a file left out because it could not be judged, or null when
     *                    the walk is not a crawl
     * @return Whether the path is one of this source's documents
     */
    private boolean shouldProcessFile(Path path, LinkOption[] linkOptions,
                                      ListingGaps.Recorder gaps) {
        if (!followSymlinks && Files.isSymbolicLink(path)) {
            return false;
        }

        if (!includeHidden && isHidden(path)) {
            return false;
        }

        if (!includedExtensions.isEmpty() && !includedExtensions.contains(getExtension(path))) {
            return false;
        }

        if (!isRegularFile(path, linkOptions, gaps)) {
            return false;
        }

        return !followSymlinks || staysInsideRoot(path, gaps);
    }

    /**
     * Whether a path is a regular file, recording a gap when that cannot be told.
     *
     * @param path        A path the walk reached
     * @param linkOptions How links are treated, as the source is configured
     * @param gaps        Told of a path whose attributes could not be read, or null
     * @return true for a regular file; false for anything else, for a path that is gone, and for
     *         one whose attributes could not be read
     */
    private boolean isRegularFile(Path path, LinkOption[] linkOptions,
                                  ListingGaps.Recorder gaps) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, linkOptions)
                .isRegularFile();
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            LOG.warn("Leaving {} out of the listing of source {}: its attributes could not be "
                + "read ({}); a version already indexed is kept", path, sourceId, e.toString());
            if (gaps != null) {
                String documentId = toDocumentId(path);
                if (documentId != null) {
                    gaps.record(documentId, "attributes not read: "
                        + e.getClass().getSimpleName());
                }
            }
            return false;
        }
    }

    private boolean isHidden(Path path) {
        try {
            if (Files.isHidden(path)) {
                return true;
            }
            String fileName = path.getFileName().toString();
            return fileName.startsWith(".");
        } catch (IOException e) {
            return false;
        }
    }

    private String toDocumentId(Path filePath) {
        try {
            Path relative = rootPath.relativize(filePath);
            return relative.toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            LOG.warn("Could not create document ID for path: {}", filePath);
            return null;
        }
    }

    /**
     * Resolves a document id to the file it names, inside the root.
     *
     * <p>Traversal is rejected a segment at a time. A substring test for ".." also rejects
     * ordinary file names - "report..final.pdf", "schema..v2.sql" - which the crawl happily
     * lists and then cannot fetch, so those files are never indexed and never reported as
     * failed. What actually contains traversal is the {@code startsWith} check below, on the
     * normalised path.</p>
     *
     * @param documentId The document id, as produced by the crawl
     * @return The resolved path, inside the root
     * @throws IllegalArgumentException if the id names a path outside the root
     */
    private Path toFilePath(String documentId) {
        String[] parts = documentId.split("/");
        Path resolved = rootPath;
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new IllegalArgumentException(
                    "Document ID contains invalid path traversal: " + documentId);
            }
            resolved = resolved.resolve(part);
        }
        resolved = resolved.normalize();

        if (!resolved.startsWith(rootPath)) {
            throw new IllegalArgumentException("Document ID escapes root path: " + documentId);
        }

        return resolved;
    }

    private String getExtension(Path path) {
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex).toLowerCase(Locale.ROOT);
    }

    private String detectContentType(Path path) {
        String extension = getExtension(path);
        String mimeType = EXTENSION_TO_MIME_TYPE.get(extension);
        if (mimeType != null) {
            return mimeType;
        }

        try {
            String probedType = Files.probeContentType(path);
            if (probedType != null && !probedType.isEmpty()) {
                return probedType;
            }
        } catch (IOException e) {
            LOG.debug("Could not probe content type for {}: {}", path, e.getMessage());
        }

        return "application/octet-stream";
    }

    private static Set<String> normalizeExtensions(Set<String> extensions) {
        Set<String> normalized = new HashSet<>();
        for (String ext : extensions) {
            String trimmed = ext.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                if (!trimmed.startsWith(".")) {
                    trimmed = "." + trimmed;
                }
                normalized.add(trimmed);
            }
        }
        return normalized;
    }

    private static Set<String> normalizeNames(Set<String> names) {
        Set<String> normalized = new HashSet<>();
        for (String name : names) {
            String trimmed = name.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }

    private static String normalizeName(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String sourceId;
        private Path rootPath;
        private Set<String> includedExtensions = Set.of();
        private Set<String> excludedDirectories = Set.of(".git", ".svn", ".hg", "node_modules", "target", "build", ".idea", ".vscode");
        private boolean followSymlinks = false;
        private boolean includeHidden = false;
        private long maxFileSize = DEFAULT_MAX_FILE_SIZE;
        private int maxDepth = DEFAULT_MAX_DEPTH;
        private Charset charset;

        public Builder sourceId(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        public Builder rootPath(Path rootPath) {
            this.rootPath = rootPath;
            return this;
        }

        public Builder rootPath(String rootPath) {
            this.rootPath = Path.of(rootPath);
            return this;
        }

        public Builder includedExtensions(Set<String> extensions) {
            this.includedExtensions = extensions != null ? extensions : Set.of();
            return this;
        }

        public Builder includedExtensions(String... extensions) {
            this.includedExtensions = Set.of(extensions);
            return this;
        }

        public Builder excludedDirectories(Set<String> directories) {
            this.excludedDirectories = directories != null ? directories : Set.of();
            return this;
        }

        public Builder excludedDirectories(String... directories) {
            this.excludedDirectories = Set.of(directories);
            return this;
        }

        public Builder followSymlinks(boolean follow) {
            this.followSymlinks = follow;
            return this;
        }

        public Builder includeHidden(boolean include) {
            this.includeHidden = include;
            return this;
        }

        /**
         * Sets the largest file the source reads.
         *
         * <p>A larger file is still listed by the crawl and refused when it is fetched, so a run
         * counts it failed and a re-crawl keeps whatever version of it was indexed before it
         * grew, rather than taking it as deleted.</p>
         *
         * <p>A file that is not a PDF is also held to {@link DocumentLimits#MAX_DOCUMENT_BYTES}
         * whatever this says; see {@link DocumentLimits}.</p>
         *
         * @param maxBytes The bound, at most {@link DocumentLimits#MAX_PDF_BYTES}, which is also
         *                 the default; {@link #build()} refuses a larger one
         * @return This builder
         */
        public Builder maxFileSize(long maxBytes) {
            this.maxFileSize = maxBytes;
            return this;
        }

        public Builder maxDepth(int depth) {
            this.maxDepth = depth;
            return this;
        }

        /**
         * Declares the encoding the source's files are in.
         *
         * <p>Applies only to a file with no byte-order mark: a mark describes the file it opens
         * and outranks a declaration made for the whole directory. See {@link TextEncoding}.</p>
         *
         * @param charset The encoding, or null to read unmarked files as UTF-8
         * @return This builder
         */
        public Builder charset(Charset charset) {
            this.charset = charset;
            return this;
        }

        public FilesystemSource build() {
            return new FilesystemSource(this);
        }
    }
}
