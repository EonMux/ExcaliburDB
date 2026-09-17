package excalibur.ingest.registry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip tests for the persisted document registry.
 *
 * <p>Document ids are filesystem paths and absolute URLs, so the characters the record format
 * uses as delimiters occur inside them. These tests pin that such ids survive a save and reload
 * unchanged, and that an unreadable registry is not quietly reported as an empty one.</p>
 */
class DocumentRegistryPersistenceTest {

    @Test
    @DisplayName("document ids containing the field delimiter round-trip")
    void pipeInDocumentIdRoundTrips(@TempDir Path tempDir) throws IOException {
        String documentId = "reports/2024|q1|final.txt";

        Path file = tempDir.resolve("registry.dat");
        DocumentRegistry registry = new DocumentRegistry(file);
        registry.register(metadata("src", documentId, documentId + ":chunk:0"));
        registry.persist();

        DocumentRegistry reloaded = new DocumentRegistry(file);

        assertThat(reloaded.size()).isEqualTo(1);
        DocumentMetadata loaded = reloaded.get("src", documentId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getDocumentId()).isEqualTo(documentId);
        assertThat(loaded.getChunkIds()).containsExactly(documentId + ":chunk:0");
    }

    @Test
    @DisplayName("document ids containing the chunk delimiter and a backslash round-trip")
    void commaAndBackslashRoundTrip(@TempDir Path tempDir) throws IOException {
        String documentId = "notes/a,b\\c.md";

        Path file = tempDir.resolve("registry.dat");
        DocumentRegistry registry = new DocumentRegistry(file);
        registry.register(metadata("src", documentId, documentId + ":chunk:0", documentId + ":chunk:1"));
        registry.persist();

        DocumentMetadata loaded = new DocumentRegistry(file).get("src", documentId);

        assertThat(loaded).isNotNull();
        assertThat(loaded.getChunkIds())
            .containsExactly(documentId + ":chunk:0", documentId + ":chunk:1");
    }

    @Test
    @DisplayName("a newline inside a document id cannot forge a second record")
    void newlineCannotForgeRecord(@TempDir Path tempDir) throws IOException {
        String documentId = "evil\nforged|doc|0|0|0|0|forged:chunk:0";

        Path file = tempDir.resolve("registry.dat");
        DocumentRegistry registry = new DocumentRegistry(file);
        registry.register(metadata("src", documentId, "src:chunk:0"));
        registry.persist();

        DocumentRegistry reloaded = new DocumentRegistry(file);

        assertThat(reloaded.size())
            .as("one registered document must reload as exactly one record")
            .isEqualTo(1);
        assertThat(reloaded.get("src", documentId)).isNotNull();
    }

    @Test
    @DisplayName("a registry file that cannot be read fails loudly")
    void unreadableRegistryFails(@TempDir Path tempDir) throws IOException {
        // A directory where a file is expected: readable path, unreadable content.
        Path file = tempDir.resolve("registry.dat");
        Files.createDirectory(file);

        assertThatThrownBy(() -> new DocumentRegistry(file))
            .isInstanceOf(DocumentRegistry.RegistryLoadException.class)
            .hasMessageContaining("registry.dat");
    }

    @Test
    @DisplayName("a malformed record is skipped without discarding the rest of the file")
    void malformedRecordIsSkipped(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("registry.dat");
        DocumentRegistry registry = new DocumentRegistry(file);
        registry.register(metadata("src", "good-doc", "src:good-doc:chunk:0"));
        registry.persist();

        // In among the records rather than after the end marker: a line past the marker is a
        // file that did not end where it says it does, which is truncation's signature and is
        // refused. The count is corrected with it, because what is under test here is a record
        // this build cannot parse, not a file that lost its tail.
        List<String> lines = new java.util.ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        lines.add(lines.size() - 1, "this is not a record");
        lines.set(lines.size() - 1, "\\#end|2");
        Files.write(file, lines, StandardCharsets.UTF_8);

        DocumentRegistry reloaded = new DocumentRegistry(file);

        assertThat(reloaded.size()).isEqualTo(1);
        assertThat(reloaded.get("src", "good-doc")).isNotNull();
    }

    private static DocumentMetadata metadata(String sourceId, String documentId, String... chunkIds) {
        return DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .lastModifiedMillis(1000L)
            .contentHash(4242L)
            .contentLength(128)
            .indexTimeMillis(2000L)
            .chunkIds(List.of(chunkIds))
            .build();
    }

    /**
     * The registry is the node's record of which chunks belong to which document. Losing it
     * loses erasure and reindexing for every document it named, so the window in which the file
     * is neither the old registry nor the new one must not exist. Writing in place opens exactly
     * that window: a crash between the truncate and the last line leaves a file that parses
     * cleanly and is missing documents the node is still serving.
     *
     * <p>A reader that opened the file before the save is what makes the difference observable
     * without crashing anything. An in-place rewrite is seen through that handle, because it is
     * the same file being overwritten. A replacement published by rename is not: the handle
     * still refers to the registry as it was, whole, which is the property that makes a crash
     * during the save survivable.</p>
     */
    @Test
    @DisplayName("a save replaces the registry rather than rewriting it in place")
    void saveReplacesRatherThanRewrites(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("registry.dat");
        DocumentRegistry registry = new DocumentRegistry(file);
        registry.register(metadata("src", "a.md", "a.md:chunk:0"));
        registry.persist();
        byte[] before = Files.readAllBytes(file);

        try (java.io.InputStream openedBeforeTheSave = Files.newInputStream(file)) {
            DocumentRegistry second = new DocumentRegistry(file);
            second.register(metadata("src", "a.md", "a.md:chunk:0"));
            second.register(metadata("src", "b.md", "b.md:chunk:0"));
            second.persist();

            assertThat(openedBeforeTheSave.readAllBytes())
                .as("the save publishes a new file; it does not overwrite the one a reader "
                    + "already has open, which is what a half-finished save would corrupt")
                .isEqualTo(before);
        }

        assertThat(new DocumentRegistry(file).size())
            .as("and the published registry is the new one")
            .isEqualTo(2);
    }

    /**
     * A temporary file left behind is read as a registry by nothing, but it accumulates once per
     * save for the life of the node.
     */
    @Test
    @DisplayName("a save leaves no scratch file behind")
    void saveLeavesNoTemporaryFile(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("registry.dat");
        DocumentRegistry registry = new DocumentRegistry(file);
        registry.register(metadata("src", "a.md", "a.md:chunk:0"));
        registry.persist();

        try (var entries = Files.list(tempDir)) {
            assertThat(entries.toList())
                .as("only the registry itself survives a save")
                .containsExactly(file);
        }
    }

    /**
     * Four callers persist this registry - an ingest run, an erasure, a reindex and shutdown -
     * and the inline-document route now reaches it from HTTP threads, so two saves genuinely
     * overlap. Sharing one replacement path between them means the second writer's open handle
     * ends up pointing at the file the first has just published, and its remaining writes land
     * in the live registry: the truncation the write-and-rename was added to eliminate.
     */
    @Test
    @DisplayName("concurrent saves never corrupt the published registry")
    void concurrentSavesDoNotCorrupt(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("registry.dat");
        DocumentRegistry registry = new DocumentRegistry(file);
        for (int i = 0; i < 400; i++) {
            registry.register(metadata("src", "doc" + i, "doc" + i + ":chunk:0"));
        }

        int savers = 8;
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        java.util.List<Throwable> failures =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int t = 0; t < savers; t++) {
            final int id = t;
            Thread thread = new Thread(() -> {
                try {
                    go.await();
                    for (int i = 0; i < 20; i++) {
                        // A real registration, which is what marks the registry modified and is
                        // what an ingest run does just before it saves.
                        registry.register(metadata("src", "extra-" + id + "-" + i,
                            "extra-" + id + "-" + i + ":chunk:0"));
                        registry.persist();
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
            thread.start();
            threads.add(thread);
        }
        go.countDown();
        for (Thread thread : threads) {
            thread.join(60_000);
        }

        assertThat(failures).as("a save must not fail because another was running").isEmpty();
        assertThat(new DocumentRegistry(file).size())
            .as("the published registry holds every record, not an interleaving of two writers")
            .isEqualTo(400 + savers * 20);
        try (var entries = Files.list(tempDir)) {
            assertThat(entries.toList()).containsExactly(file);
        }
    }

    /**
     * The modified flag is cleared after the write. A registration that lands while a save is
     * iterating is not in that file, and clearing the flag over it means the registration's own
     * later save sees nothing to do and returns. Its documents are then searchable and absent
     * from the on-disk registry, so after a restart they can be neither erased nor reindexed.
     */
    @Test
    @DisplayName("a registration racing a save is not lost by the save clearing the flag")
    void aRegistrationDuringASaveIsNotLost(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("registry.dat");
        DocumentRegistry registry = new DocumentRegistry(file);
        registry.register(metadata("src", "first.md", "first.md:chunk:0"));

        java.util.concurrent.CountDownLatch registered = new java.util.concurrent.CountDownLatch(1);
        Thread writer = new Thread(() -> {
            registry.register(metadata("src", "second.md", "second.md:chunk:0"));
            registered.countDown();
        });
        writer.start();
        registered.await(30, java.util.concurrent.TimeUnit.SECONDS);
        writer.join(30_000);

        registry.persist();
        registry.persist();

        assertThat(new DocumentRegistry(file).size()).isEqualTo(2);
    }
}
