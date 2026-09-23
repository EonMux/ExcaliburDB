package com.eonmux.excalibur.retrieval.lexical;

import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.core.integrity.IntegrityStatus;

import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A published index is refused, and reported, when a file of it no longer matches its footer -
 * including the files Lucene only reads lazily, which opening a reader alone never checks.
 */
@DisplayName("Lexical index integrity")
class LexicalIndexIntegrityTest {

    private static Path write(Path dir) throws IOException {
        try (LexicalIndex index = LexicalIndex.open(dir, 4.0)) {
            for (long id = 0; id < 50; id++) {
                index.put(id, "document number " + id + " about the analytical engine", "k" + id);
            }
            index.refresh();
        }
        return dir;
    }

    /** Every file the index holds that Lucene reads; the lock file carries nothing. */
    private static List<Path> indexFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> !file.getFileName().toString().equals("write.lock"))
                .sorted()
                .toList();
        }
    }

    @Test
    @DisplayName("an index as written verifies intact, and opens")
    void intact(@TempDir Path dir) throws IOException {
        write(dir);

        IntegrityReport report = LexicalIndexIntegrity.verify(dir);

        assertThat(report.status()).isEqualTo(IntegrityStatus.INTACT);
        assertThat(report.file()).isEqualTo(dir);
        try (LexicalIndex index = LexicalIndex.openReadOnly(dir)) {
            assertThat(index.documentCount()).isEqualTo(50);
        }
    }

    @Test
    @DisplayName("a flipped byte in any file is refused as damage and reported corrupt")
    void flippedByteInEveryFile(@TempDir Path dir) throws IOException {
        write(dir);
        List<Path> files = indexFiles(dir);
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            byte[] original = Files.readAllBytes(file);
            byte[] damaged = original.clone();
            damaged[damaged.length / 2] ^= 0x04;
            Files.write(file, damaged);

            assertDamaged(dir, file.getFileName().toString());
            Files.write(file, original);
        }
        assertThat(LexicalIndexIntegrity.verify(dir).status()).isEqualTo(IntegrityStatus.INTACT);
    }

    @Test
    @DisplayName("a file cut short is refused as damage and reported corrupt")
    void truncatedFile(@TempDir Path dir) throws IOException {
        write(dir);
        for (Path file : indexFiles(dir)) {
            byte[] original = Files.readAllBytes(file);
            Files.write(file, Arrays.copyOf(original, original.length - 1));

            assertDamaged(dir, file.getFileName().toString());
            Files.write(file, original);
        }
    }

    @Test
    @DisplayName("a file the commit names that is gone is refused as damage")
    void missingFile(@TempDir Path dir) throws IOException {
        write(dir);
        Path segmentFile = indexFiles(dir).stream()
            .filter(file -> !file.getFileName().toString().startsWith("segments"))
            .findFirst()
            .orElseThrow();
        Files.delete(segmentFile);

        assertDamaged(dir, segmentFile.getFileName().toString());
    }

    @Test
    @DisplayName("an absent directory is missing; a directory with no commit is damage")
    void absentAndEmpty(@TempDir Path dir) throws IOException {
        assertThat(LexicalIndexIntegrity.verify(dir.resolve("labels")).status())
            .isEqualTo(IntegrityStatus.MISSING);

        Path empty = Files.createDirectory(dir.resolve("documents"));
        assertDamaged(empty, "an empty directory");
    }

    @Test
    @DisplayName("damage is told apart from a format this build does not read")
    void classifiesRefusals() {
        assertThat(LexicalIndexIntegrity.isDamage(new CorruptIndexException("x", "r"))).isTrue();
        assertThat(LexicalIndexIntegrity.isDamage(new IndexNotFoundException("x"))).isTrue();
        assertThat(LexicalIndexIntegrity.isDamage(new NoSuchFileException("x"))).isTrue();
        assertThat(LexicalIndexIntegrity.isDamage(new FileNotFoundException("x"))).isTrue();
        assertThat(LexicalIndexIntegrity.isDamage(new EOFException("x"))).isTrue();
        assertThat(LexicalIndexIntegrity.isDamage(
            new IndexFormatTooNewException("x", 99, 1, 2))).isFalse();
        assertThat(LexicalIndexIntegrity.isDamage(new AccessDeniedException("x"))).isFalse();
    }

    private static void assertDamaged(Path dir, String what) {
        assertThatThrownBy(() -> LexicalIndex.openReadOnly(dir).close())
            .as(what)
            .isInstanceOfSatisfying(IOException.class, e ->
                assertThat(LexicalIndexIntegrity.isDamage(e)).as(what + ": " + e).isTrue());
        IntegrityReport report = LexicalIndexIntegrity.verify(dir);
        assertThat(report.status()).as(what + ": " + report).isEqualTo(IntegrityStatus.CORRUPT);
    }
}
