package com.eonmux.excalibur.backup.snapshot;

import com.eonmux.excalibur.backup.testing.DataDirectoryFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which files a snapshot is made of, and why each one is there or is not.
 *
 * <p>This is the decision the rest of the tool rests on, so it is stated as data and tested
 * as data rather than left implied by whatever the copier happens to walk.
 */
@DisplayName("Classifying the files in a data directory")
class DataDirectoryInventoryTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({
        "catalog.dat,                          AUTHORITATIVE",
        "routing.dir,                          AUTHORITATIVE",
        "document-registry,                    AUTHORITATIVE",
        "source-catalog,                       AUTHORITATIVE",
        "segment_0.dat,                        AUTHORITATIVE",
        "segment_12.wal,                       AUTHORITATIVE",
        "shared/graph-staging/triples.log,     AUTHORITATIVE",
        "shared/graph-staging/erasure.log,     AUTHORITATIVE",
        "tenant-a/graph-staging/entity_text.log, AUTHORITATIVE",
        "segment_0.hnsw,                       REQUIRED_DERIVED",
        "segment_0.hnsw.docmap,                REQUIRED_DERIVED",
        "segment_0.hnsw.compact,               REQUIRED_DERIVED",
        "segment_0.hnsw.compact.docmap,        REQUIRED_DERIVED",
        "chunk-index/manifest,                 REBUILDABLE",
        "chunk-index/seg-0/terms.dict,         REBUILDABLE",
        "shared/graph/edges.csr,               REBUILDABLE",
        "segment_0.pqcb,                       REBUILDABLE",
        "routing.dir.tmp,                      TRANSIENT",
        "source-catalog.tmp,                   TRANSIENT",
        "segment_0.dat.compact,                TRANSIENT",
        "segment_0.wal.old,                    TRANSIENT",
        "segment_0.dat.left,                   TRANSIENT",
        "segment_0.dat.right,                  TRANSIENT",
        "segment_0.pqcb.tmp,                   TRANSIENT",
        "chunk-index/.compact-seg-0,           TRANSIENT",
        "chunk-index/seg-0.tomb.tmp,           TRANSIENT",
        "shared/graph-staging/triples.log.erasing, TRANSIENT",
        "shared/graph/edges.csr.building,      TRANSIENT"
    })
    void classifies(String relativePath, Inclusion expected) {
        assertThat(DataDirectoryInventory.classify(relativePath.trim())).isEqualTo(expected);
    }

    @Test
    @DisplayName("an unrecognised file is kept rather than silently dropped")
    void unknownFilesAreKept() {
        assertThat(DataDirectoryInventory.classify("something-a-future-build-writes"))
            .isEqualTo(Inclusion.AUTHORITATIVE);
    }

    @Test
    @DisplayName("walks a real tree and classifies every file in it")
    void walksTheTree() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));

        Map<String, Inclusion> found = DataDirectoryInventory.of(dataDir).stream()
            .collect(Collectors.toMap(ClassifiedFile::path, ClassifiedFile::inclusion));

        assertThat(found).containsEntry("catalog.dat", Inclusion.AUTHORITATIVE);
        assertThat(found).containsEntry("segment_0.hnsw", Inclusion.REQUIRED_DERIVED);
        assertThat(found).containsEntry("chunk-index/manifest", Inclusion.REBUILDABLE);
        assertThat(found).containsEntry("routing.dir.tmp", Inclusion.TRANSIENT);
        assertThat(found.values()).doesNotContainNull();
    }

    @Test
    @DisplayName("every classification carries the reason it was made")
    void everyClassificationIsExplained() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));

        assertThat(DataDirectoryInventory.of(dataDir))
            .allSatisfy(file -> assertThat(file.reason()).isNotBlank());
    }

    @Test
    @DisplayName("paths are reported with forward slashes whatever the platform uses")
    void pathsArePortable() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));

        assertThat(DataDirectoryInventory.of(dataDir).stream().map(ClassifiedFile::path))
            .contains("shared/graph-staging/triples.log")
            .allSatisfy(path -> assertThat(path).doesNotContain("\\"));
    }

    @Test
    @DisplayName("an included set is what a snapshot copies")
    void includedSetRespectsTheDerivedSwitch() throws IOException {
        Path dataDir = DataDirectoryFixture.syntheticDirectory(tempDir.resolve("data"));
        var all = DataDirectoryInventory.of(dataDir);

        var withDerived = all.stream().filter(f -> f.inclusion().includedWhen(true))
            .map(ClassifiedFile::path).collect(Collectors.toSet());
        var withoutDerived = all.stream().filter(f -> f.inclusion().includedWhen(false))
            .map(ClassifiedFile::path).collect(Collectors.toSet());

        assertThat(withDerived).contains("chunk-index/manifest", "segment_0.hnsw");
        assertThat(withoutDerived).contains("segment_0.hnsw").doesNotContain(
            "chunk-index/manifest", "chunk-index/seg-0/terms.dict", "shared/graph/edges.csr");
        assertThat(withDerived).doesNotContain("routing.dir.tmp");
    }
}
