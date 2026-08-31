package excalibur.ingest.source;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class FilesystemSourceTest {

    @TempDir
    Path tempDir;

    private FilesystemSource source;
    private Path testRoot;

    @BeforeEach
    void setUp() throws IOException {
        testRoot = tempDir.resolve("test-docs");
        Files.createDirectories(testRoot);

        Files.writeString(testRoot.resolve("file1.txt"), "Content of file 1");
        Files.writeString(testRoot.resolve("file2.md"), "# Markdown file");

        Path subDir = testRoot.resolve("subdir");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("file3.txt"), "Content of file 3");
        Files.writeString(subDir.resolve("file4.json"), "{\"key\": \"value\"}");

        Path deepDir = subDir.resolve("deep");
        Files.createDirectories(deepDir);
        Files.writeString(deepDir.resolve("file5.txt"), "Deep content");

        source = FilesystemSource.builder()
            .sourceId("test-source")
            .rootPath(testRoot)
            .build();
    }

    @AfterEach
    void tearDown() {
        if (source != null) {
            source.close();
        }
    }

    @Test
    void getSourceId_returnsConfiguredId() {
        assertThat(source.getSourceId()).isEqualTo("test-source");
    }

    @Test
    void getSourceType_returnsFilesystem() {
        assertThat(source.getSourceType()).isEqualTo("filesystem");
    }

    @Test
    void getDescription_containsPath() {
        assertThat(source.getDescription()).contains("filesystem").contains(testRoot.toString());
    }

    @Test
    void crawl_returnsAllFiles() throws SourceException {
        try (Stream<String> ids = source.crawl()) {
            List<String> documentIds = ids.toList();

            assertThat(documentIds).contains(
                "file1.txt",
                "file2.md",
                "subdir/file3.txt",
                "subdir/file4.json",
                "subdir/deep/file5.txt"
            );
        }
    }

    @Test
    void crawl_withExtensionFilter_returnsOnlyMatchingFiles() throws SourceException {
        source.close();
        source = FilesystemSource.builder()
            .sourceId("test-source")
            .rootPath(testRoot)
            .includedExtensions(".txt", ".md")
            .build();

        try (Stream<String> ids = source.crawl()) {
            List<String> documentIds = ids.toList();

            assertThat(documentIds).containsExactlyInAnyOrder(
                "file1.txt",
                "file2.md",
                "subdir/file3.txt",
                "subdir/deep/file5.txt"
            );
            assertThat(documentIds).doesNotContain("subdir/file4.json");
        }
    }

    @Test
    void crawl_excludesHiddenFiles() throws IOException, SourceException {
        Files.writeString(testRoot.resolve(".hidden.txt"), "Hidden content");

        try (Stream<String> ids = source.crawl()) {
            List<String> documentIds = ids.toList();

            assertThat(documentIds).doesNotContain(".hidden.txt");
        }
    }

    @Test
    void crawl_withIncludeHidden_includesHiddenFiles() throws IOException, SourceException {
        Files.writeString(testRoot.resolve(".hidden.txt"), "Hidden content");

        source.close();
        source = FilesystemSource.builder()
            .sourceId("test-source")
            .rootPath(testRoot)
            .includeHidden(true)
            .build();

        try (Stream<String> ids = source.crawl()) {
            List<String> documentIds = ids.toList();

            assertThat(documentIds).contains(".hidden.txt");
        }
    }

    @Test
    void crawl_excludesConfiguredDirectories() throws SourceException {
        source.close();
        source = FilesystemSource.builder()
            .sourceId("test-source")
            .rootPath(testRoot)
            .excludedDirectories("subdir")
            .build();

        try (Stream<String> ids = source.crawl()) {
            List<String> documentIds = ids.toList();

            assertThat(documentIds).containsExactlyInAnyOrder("file1.txt", "file2.md");
        }
    }

    @Test
    void crawl_withMaxDepth_limitsRecursion() throws IOException, SourceException {
        source.close();
        source = FilesystemSource.builder()
            .sourceId("test-source")
            .rootPath(testRoot)
            .maxDepth(1)
            .build();

        try (Stream<String> ids = source.crawl()) {
            List<String> documentIds = ids.toList();

            assertThat(documentIds).containsExactlyInAnyOrder("file1.txt", "file2.md");
            assertThat(documentIds).doesNotContain("subdir/file3.txt");
        }
    }

    @Test
    void hasChanged_whenRecentlyModified_returnsTrue() throws SourceException {
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);

        boolean changed = source.hasChanged(past);

        assertThat(changed).isTrue();
    }

    @Test
    void hasChanged_whenFutureTimestamp_returnsTrue() throws SourceException {
        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);

        boolean changed = source.hasChanged(future);

        assertThat(changed).isTrue();
    }

    @Test
    void hasChanged_withNullTimestamp_throwsException() {
        assertThatThrownBy(() -> source.hasChanged(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fetch_existingFile_returnsDocument() throws SourceException, DocumentNotFoundException {
        FetchedDocument doc = source.fetch("file1.txt");

        assertThat(doc.getDocumentId()).isEqualTo("file1.txt");
        assertThat(doc.getContent()).isEqualTo("Content of file 1".getBytes());
        assertThat(doc.getContentType()).isEqualTo("text/plain");
        assertThat(doc.hasKnownLastModified()).isTrue();
    }

    @Test
    void fetch_nestedFile_returnsDocument() throws SourceException, DocumentNotFoundException {
        FetchedDocument doc = source.fetch("subdir/file4.json");

        assertThat(doc.getDocumentId()).isEqualTo("subdir/file4.json");
        assertThat(doc.getContent()).isEqualTo("{\"key\": \"value\"}".getBytes());
        assertThat(doc.getContentType()).isEqualTo("application/json");
    }

    @Test
    void fetch_nonExistentFile_throwsDocumentNotFoundException() {
        assertThatThrownBy(() -> source.fetch("nonexistent.txt"))
            .isInstanceOf(DocumentNotFoundException.class)
            .hasMessageContaining("nonexistent.txt");
    }

    @Test
    void fetch_pathTraversal_throwsException() {
        assertThatThrownBy(() -> source.fetch("../outside.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("path traversal");
    }

    @Test
    void fetch_nullDocumentId_throwsException() {
        assertThatThrownBy(() -> source.fetch(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fetch_emptyDocumentId_throwsException() {
        assertThatThrownBy(() -> source.fetch(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fetch_directory_throwsException() {
        assertThatThrownBy(() -> source.fetch("subdir"))
            .isInstanceOf(SourceException.class)
            .hasMessageContaining("not a regular file");
    }

    @Test
    void getDocumentCount_returnsCorrectCount() throws SourceException {
        long count = source.getDocumentCount();

        assertThat(count).isEqualTo(5);
    }

    @Test
    void getEstimatedTotalSize_returnsPositiveValue() throws SourceException {
        long size = source.getEstimatedTotalSize();

        assertThat(size).isGreaterThan(0);
    }

    @Test
    void close_thenCrawl_throwsException() throws SourceException {
        source.close();

        assertThatThrownBy(() -> source.crawl())
            .isInstanceOf(SourceException.class)
            .hasMessageContaining("closed");
    }

    @Test
    void close_thenFetch_throwsException() {
        source.close();

        assertThatThrownBy(() -> source.fetch("file1.txt"))
            .isInstanceOf(SourceException.class)
            .hasMessageContaining("closed");
    }

    @Test
    void builder_withNullSourceId_throwsException() {
        assertThatThrownBy(() -> FilesystemSource.builder()
            .sourceId(null)
            .rootPath(testRoot)
            .build())
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_withEmptySourceId_throwsException() {
        assertThatThrownBy(() -> FilesystemSource.builder()
            .sourceId("")
            .rootPath(testRoot)
            .build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_withTooLongSourceId_throwsException() {
        String longId = "x".repeat(257);

        assertThatThrownBy(() -> FilesystemSource.builder()
            .sourceId(longId)
            .rootPath(testRoot)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("256");
    }

    @Test
    void builder_withNullRootPath_throwsException() {
        assertThatThrownBy(() -> FilesystemSource.builder()
            .sourceId("test")
            .rootPath((Path) null)
            .build())
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_withNonExistentRootPath_throwsException() {
        assertThatThrownBy(() -> FilesystemSource.builder()
            .sourceId("test")
            .rootPath("/nonexistent/path/12345")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("existing directory");
    }

    @Test
    void builder_withFileAsRootPath_throwsException() throws IOException {
        Path file = tempDir.resolve("notadir.txt");
        Files.writeString(file, "content");

        assertThatThrownBy(() -> FilesystemSource.builder()
            .sourceId("test")
            .rootPath(file)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("existing directory");
    }

    @Test
    void builder_withStringRootPath_works() {
        FilesystemSource stringSource = FilesystemSource.builder()
            .sourceId("string-test")
            .rootPath(testRoot.toString())
            .build();

        assertThat(stringSource.getRootPath()).isEqualTo(testRoot.toAbsolutePath().normalize());
        stringSource.close();
    }

    @Test
    void fetch_largeFile_respectsMaxSize() throws IOException {
        Path largeFile = testRoot.resolve("large.txt");
        Files.writeString(largeFile, "x".repeat(1000));

        source.close();
        source = FilesystemSource.builder()
            .sourceId("test-source")
            .rootPath(testRoot)
            .maxFileSize(100)
            .build();

        assertThatThrownBy(() -> source.fetch("large.txt"))
            .isInstanceOf(SourceException.class)
            .hasMessageContaining("too large");
    }

    @Test
    void isTextContent_detectsTextFiles() throws SourceException, DocumentNotFoundException {
        FetchedDocument txtDoc = source.fetch("file1.txt");
        assertThat(txtDoc.isTextContent()).isTrue();

        FetchedDocument jsonDoc = source.fetch("subdir/file4.json");
        assertThat(jsonDoc.isTextContent()).isTrue();
    }

    @Test
    void contentIsDefensivelyCopied() throws SourceException, DocumentNotFoundException {
        FetchedDocument doc = source.fetch("file1.txt");

        byte[] content1 = doc.getContent();
        byte[] content2 = doc.getContent();

        assertThat(content1).isNotSameAs(content2);
        assertThat(content1).isEqualTo(content2);
    }

    @Test
    void extensionMatching_isCaseInsensitive() throws IOException, SourceException {
        Files.writeString(testRoot.resolve("UPPER.TXT"), "Uppercase extension");

        source.close();
        source = FilesystemSource.builder()
            .sourceId("test-source")
            .rootPath(testRoot)
            .includedExtensions(".txt")
            .build();

        try (Stream<String> ids = source.crawl()) {
            List<String> documentIds = ids.toList();

            assertThat(documentIds).contains("UPPER.TXT");
        }
    }

    @Test
    void doubleDotInAFileNameIsCrawledAndFetched() throws IOException, SourceException {
        // A substring test for ".." also rejects ordinary names. The crawl lists such a file and
        // the fetch then throws IllegalArgumentException, which is not part of fetch's contract,
        // so the document is skipped as an unexpected error: never indexed, never reported.
        Files.writeString(testRoot.resolve("notes..2024.md"), "Double dot content");

        try (Stream<String> ids = source.crawl()) {
            assertThat(ids.toList()).contains("notes..2024.md");
        }

        assertThat(source.fetch("notes..2024.md").getText()).isEqualTo("Double dot content");
    }

    @Test
    void realTraversalIsStillRejected() {
        assertThatThrownBy(() -> source.fetch("../outside.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("traversal");
        assertThatThrownBy(() -> source.fetch("subdir/../../outside.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("traversal");
    }
}
