package excalibur.ingest.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * Multiple threads may call {@link #crawl()}, {@link #hasChanged(Instant)}, and
 * {@link #fetch(String)} concurrently.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Root path must be an existing, readable directory</li>
 *   <li>File extensions are case-insensitive (e.g., ".TXT" == ".txt")</li>
 *   <li>Maximum file size: 2GB (configurable)</li>
 *   <li>Symlinks are not followed by default (configurable)</li>
 *   <li>Hidden files are excluded by default (configurable)</li>
 *   <li>Directory modification times are used for change detection</li>
 * </ul>
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

    private static final long DEFAULT_MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024;
    private static final int DEFAULT_MAX_DEPTH = 100;
    private static final long MILLIS_PER_SECOND = 1000L;

    private static final Map<String, String> EXTENSION_TO_MIME_TYPE = Map.ofEntries(
        Map.entry(".txt", "text/plain"),
        Map.entry(".md", "text/markdown"),
        Map.entry(".html", "text/html"),
        Map.entry(".htm", "text/html"),
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
    private final Set<String> includedExtensions;
    private final Set<String> excludedDirectories;
    private final boolean followSymlinks;
    private final boolean includeHidden;
    private final long maxFileSize;
    private final int maxDepth;
    private volatile boolean closed = false;

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

        this.includedExtensions = builder.includedExtensions.isEmpty()
            ? Set.of()
            : Set.copyOf(normalizeExtensions(builder.includedExtensions));
        this.excludedDirectories = Set.copyOf(normalizeNames(builder.excludedDirectories));
        this.followSymlinks = builder.followSymlinks;
        this.includeHidden = builder.includeHidden;
        this.maxFileSize = Math.max(1, builder.maxFileSize);
        this.maxDepth = Math.max(1, builder.maxDepth);

        LOG.debug("FilesystemSource created: id={}, root={}, extensions={}, excluded={}",
            sourceId, rootPath, includedExtensions, excludedDirectories);
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

        LinkOption[] linkOptions = followSymlinks
            ? new LinkOption[0]
            : new LinkOption[]{LinkOption.NOFOLLOW_LINKS};

        try {
            return Files.walk(rootPath, maxDepth, followSymlinks ? new FileVisitOption[]{FileVisitOption.FOLLOW_LINKS} : new FileVisitOption[0])
                .filter(path -> !closed)
                .filter(path -> shouldProcessFile(path, linkOptions))
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
    public boolean hasChanged(Instant since) throws SourceException {
        ensureOpen();
        Objects.requireNonNull(since, "since must not be null");

        if (since.isAfter(Instant.now())) {
            LOG.warn("hasChanged called with future timestamp: {}", since);
            return true;
        }

        try {
            BasicFileAttributes rootAttrs = Files.readAttributes(
                rootPath,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
            );

            FileTime lastModified = rootAttrs.lastModifiedTime();
            Instant rootModified = lastModified.toInstant();

            if (rootModified.isAfter(since)) {
                LOG.debug("Source {} has changed (root modified at {} > {})",
                    sourceId, rootModified, since);
                return true;
            }

            if (rootAttrs.isDirectory()) {
                FileTime created = rootAttrs.creationTime();
                if (created.toInstant().isAfter(since)) {
                    LOG.debug("Source {} has changed (root created at {} > {})",
                        sourceId, created, since);
                    return true;
                }
            }

            LOG.debug("Source {} has not changed since {}", sourceId, since);
            return false;

        } catch (IOException e) {
            LOG.warn("Could not check modification time for source {}: {}", sourceId, e.getMessage());
            return true;
        }
    }

    @Override
    public FetchedDocument fetch(String documentId) throws SourceException, DocumentNotFoundException {
        ensureOpen();
        Objects.requireNonNull(documentId, "documentId must not be null");
        if (documentId.isEmpty()) {
            throw new IllegalArgumentException("documentId must not be empty");
        }

        Path filePath = toFilePath(documentId);
        LinkOption[] linkOptions = followSymlinks
            ? new LinkOption[0]
            : new LinkOption[]{LinkOption.NOFOLLOW_LINKS};

        if (!Files.exists(filePath, linkOptions)) {
            throw new DocumentNotFoundException(documentId, sourceId);
        }

        if (!Files.isRegularFile(filePath, linkOptions)) {
            throw new SourceException(
                "Document is not a regular file: " + documentId,
                sourceId,
                false
            );
        }

        try {
            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class, linkOptions);
            long fileSize = attrs.size();

            if (fileSize > maxFileSize) {
                throw new SourceException(
                    "File too large: " + fileSize + " bytes (max: " + maxFileSize + "): " + documentId,
                    sourceId,
                    false
                );
            }

            byte[] content = Files.readAllBytes(filePath);
            String contentType = detectContentType(filePath);
            long lastModifiedMillis = attrs.lastModifiedTime().toMillis();

            LOG.debug("Fetched document: {} ({} bytes, {})", documentId, content.length, contentType);

            return new FetchedDocument(documentId, content, contentType, lastModifiedMillis);

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

        try (Stream<Path> paths = Files.walk(rootPath, maxDepth)) {
            LinkOption[] linkOptions = followSymlinks
                ? new LinkOption[0]
                : new LinkOption[]{LinkOption.NOFOLLOW_LINKS};

            long count = paths
                .filter(path -> shouldProcessFile(path, linkOptions))
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
        }
    }

    @Override
    public long getEstimatedTotalSize() throws SourceException {
        ensureOpen();

        try (Stream<Path> paths = Files.walk(rootPath, maxDepth)) {
            LinkOption[] linkOptions = followSymlinks
                ? new LinkOption[0]
                : new LinkOption[]{LinkOption.NOFOLLOW_LINKS};

            long totalSize = paths
                .filter(path -> shouldProcessFile(path, linkOptions))
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

    private void ensureOpen() throws SourceException {
        if (closed) {
            throw new SourceException("Source is closed: " + sourceId, sourceId, false);
        }
    }

    private boolean shouldProcessFile(Path path, LinkOption[] linkOptions) {
        try {
            if (!Files.isRegularFile(path, linkOptions)) {
                return false;
            }

            if (!followSymlinks && Files.isSymbolicLink(path)) {
                return false;
            }

            if (!includeHidden && isHidden(path)) {
                return false;
            }

            if (isInExcludedDirectory(path)) {
                return false;
            }

            if (!includedExtensions.isEmpty()) {
                String extension = getExtension(path);
                if (!includedExtensions.contains(extension)) {
                    return false;
                }
            }

            long size = Files.size(path);
            if (size > maxFileSize) {
                LOG.debug("Skipping file exceeding max size: {} ({} > {})", path, size, maxFileSize);
                return false;
            }

            return true;

        } catch (IOException e) {
            LOG.debug("Skipping file due to access error: {} - {}", path, e.getMessage());
            return false;
        }
    }

    private boolean isInExcludedDirectory(Path path) {
        Path relative = rootPath.relativize(path);
        for (int i = 0; i < relative.getNameCount(); i++) {
            String name = relative.getName(i).toString();
            if (excludedDirectories.contains(normalizeName(name))) {
                return true;
            }
        }
        return false;
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

        public Builder maxFileSize(long maxBytes) {
            this.maxFileSize = maxBytes;
            return this;
        }

        public Builder maxDepth(int depth) {
            this.maxDepth = depth;
            return this;
        }

        public FilesystemSource build() {
            return new FilesystemSource(this);
        }
    }
}
