package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.DataDirectoryScan;
import com.eonmux.excalibur.fsck.check.DataDirectoryScanner;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.FileClassifier;
import com.eonmux.excalibur.fsck.check.FileInspector;
import com.eonmux.excalibur.fsck.check.FileKind;
import com.eonmux.excalibur.fsck.repair.RepairPlan;
import com.eonmux.excalibur.fsck.repair.RepairPlanner;
import com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndexIntegrity;
import com.eonmux.excalibur.retrieval.lexical.native_.DocumentErasureLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Lucene chunk index, {@code lucene-chunks}, which a node configured with the Lucene lexical
 * engine keeps instead of the native store.
 *
 * <p>Two structures share the directory. The index is read whole from its commit point, so it is
 * judged whole; the erasure log beside it is the native store's format and carries a checksum on
 * every line, and a log that is not there owes nothing, so its damage is never moved aside.</p>
 */
@DisplayName("The Lucene chunk index")
class LuceneChunkIndexCheckTest {

    private static final String DIRECTORY = FileClassifier.LUCENE_CHUNK_DIRECTORY;
    private static final String ERASURE_LOG = DIRECTORY + "/" + DocumentErasureLog.FILE;
    private static final String ERASED = "kb:a.txt:chunk:0";

    /** Writes an index through the node's own class: two chunks, one of them erased. */
    private static Path indexedDirectory(Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        ChunkLexicalIndex index = ChunkLexicalIndex.open(dataDir.resolve(DIRECTORY), 4.0);
        try {
            index.add(ERASED, "the certificate chain could not be verified");
            index.add("kb:b.txt:chunk:0", "the TLS handshake failed");
            index.refresh();
            index.erase(Set.of(ERASED));
            index.commit();
        } finally {
            index.close();
        }
        return dataDir;
    }

    private static DataDirectoryScan scan(Path dataDir) throws IOException {
        return new DataDirectoryScanner(dataDir).scan();
    }

    private static FileCheck verdictOn(DataDirectoryScan scan, String relative) {
        return scan.files().stream()
            .filter(file -> file.relative().equals(relative))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no verdict on " + relative + " in "
                + scan.files().stream().map(FileCheck::relative).toList()));
    }

    private static List<String> reported(DataDirectoryScan scan) {
        return scan.files().stream().map(FileCheck::relative)
            .filter(relative -> relative.startsWith(DIRECTORY)).toList();
    }

    /** The largest file of the index other than its commit point and lock. */
    private static Path largestIndexFile(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files
                .filter(file -> !file.getFileName().toString().startsWith("segments"))
                .filter(file -> !file.getFileName().toString().equals("write.lock"))
                .filter(file -> !file.getFileName().toString().equals(DocumentErasureLog.FILE))
                .max((a, b) -> Long.compare(a.toFile().length(), b.toFile().length()))
                .orElseThrow();
        }
    }

    @Test
    @DisplayName("names every file in the directory for what it is")
    void classifiesTheDirectory() {
        assertThat(FileClassifier.classify(ERASURE_LOG)).isEqualTo(FileKind.LUCENE_CHUNK_ERASURE_LOG);
        assertThat(FileClassifier.classify(DIRECTORY + "/_0.cfs"))
            .isEqualTo(FileKind.LUCENE_CHUNK_INDEX_PART);
        assertThat(FileClassifier.classify(DIRECTORY + "/segments_2"))
            .isEqualTo(FileKind.LUCENE_CHUNK_INDEX_PART);
        assertThat(FileClassifier.classify(DIRECTORY + "/write.lock"))
            .isEqualTo(FileKind.LUCENE_CHUNK_INDEX_PART);
        assertThat(FileClassifier.classify("shared/" + DIRECTORY + "/_0.cfs"))
            .as("only the directory at the top of the data directory is the index")
            .isNotEqualTo(FileKind.LUCENE_CHUNK_INDEX_PART);
    }

    @Test
    @DisplayName("an index the node wrote is proven whole, and its erasure log line by line")
    void aWrittenIndexIsIntact(@TempDir Path tempDir) throws IOException {
        Path dataDir = indexedDirectory(tempDir);

        DataDirectoryScan scan = scan(dataDir);

        FileCheck index = verdictOn(scan, DIRECTORY);
        assertThat(index.status()).isEqualTo(CheckStatus.INTACT);
        assertThat(index.format()).isEqualTo(LexicalIndexIntegrity.FORMAT);
        FileCheck log = verdictOn(scan, ERASURE_LOG);
        assertThat(log.status()).isEqualTo(CheckStatus.INTACT);
        assertThat(log.detail()).contains("1 promise(s) outstanding");
        assertThat(reported(scan))
            .as("the index's verdict speaks for every file but the log, so none is listed "
                + "again as unchecked")
            .containsExactlyInAnyOrder(DIRECTORY, ERASURE_LOG);
        assertThat(scan.files()).noneMatch(FileCheck::isDamage);
    }

    @Test
    @DisplayName("a damaged erasure log line is corrupt and put to an operator, never moved")
    void aDamagedErasureLogIsLeftToAnOperator(@TempDir Path tempDir) throws IOException {
        Path dataDir = indexedDirectory(tempDir);
        Path log = dataDir.resolve(ERASURE_LOG);
        String text = Files.readString(log, StandardCharsets.UTF_8);
        assertThat(text).contains(ERASED);
        Files.writeString(log, text.replace(ERASED, "kb:z.txt:chunk:0"), StandardCharsets.UTF_8);

        DataDirectoryScan scan = scan(dataDir);
        RepairPlan plan = RepairPlanner.plan(scan);

        FileCheck verdict = verdictOn(scan, ERASURE_LOG);
        assertThat(verdict.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(verdict.isDamage()).isTrue();
        assertThat(verdict.needsOperator()).isTrue();
        assertThat(plan.actions()).noneMatch(action -> action.relative().startsWith(DIRECTORY));
        assertThat(plan.needsOperator()).anyMatch(note -> note.startsWith(ERASURE_LOG)
            && note.contains("every erasure it still holds would lapse"));
    }

    @Test
    @DisplayName("a damaged index is corrupt, and is not moved aside with the text it may hold")
    void aDamagedIndexIsLeftToAnOperator(@TempDir Path tempDir) throws IOException {
        Path dataDir = indexedDirectory(tempDir);
        Path file = largestIndexFile(dataDir.resolve(DIRECTORY));
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 0x20;
        Files.write(file, bytes);

        DataDirectoryScan scan = scan(dataDir);
        RepairPlan plan = RepairPlanner.plan(scan);

        FileCheck index = verdictOn(scan, DIRECTORY);
        assertThat(index.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(index.isDamage()).isTrue();
        assertThat(verdictOn(scan, ERASURE_LOG).status())
            .as("the log is judged on its own, whatever became of the index")
            .isEqualTo(CheckStatus.INTACT);
        assertThat(plan.actions()).noneMatch(action -> action.relative().startsWith(DIRECTORY));
        assertThat(plan.needsOperator()).anyMatch(note -> note.startsWith(DIRECTORY + " ")
            && note.contains("reindexing"));
    }

    @Test
    @DisplayName("a directory the node created and never committed holds no index to judge")
    void anUncommittedDirectoryIsNotDamage(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        Path directory = Files.createDirectories(dataDir.resolve(DIRECTORY));
        Files.createFile(directory.resolve("write.lock"));

        DataDirectoryScan scan = scan(dataDir);

        assertThat(FileInspector.luceneChunkIndex(dataDir)).isEmpty();
        assertThat(verdictOn(scan, DIRECTORY + "/write.lock").status())
            .isEqualTo(CheckStatus.UNCHECKED);
        assertThat(scan.files()).noneMatch(FileCheck::isDamage);
    }

    @Test
    @DisplayName("a directory with index files and no commit point is damage")
    void filesWithoutACommitAreCorrupt(@TempDir Path tempDir) throws IOException {
        Path dataDir = indexedDirectory(tempDir);
        try (Stream<Path> files = Files.list(dataDir.resolve(DIRECTORY))) {
            for (Path commit : files.filter(file -> file.getFileName().toString()
                    .startsWith("segments")).toList()) {
                Files.delete(commit);
            }
        }

        DataDirectoryScan scan = scan(dataDir);

        assertThat(verdictOn(scan, DIRECTORY).status()).isEqualTo(CheckStatus.CORRUPT);
    }
}
