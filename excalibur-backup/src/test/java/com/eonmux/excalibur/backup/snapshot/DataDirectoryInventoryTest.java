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
        "segment_0.tfs,                        REBUILDABLE",
        "segment_12.tfs,                       REBUILDABLE",
        "segment_0.tfs.0b7e1f7a-3c1d-4e59-9a4f-2a6f1d0c9e11.tmp, TRANSIENT",
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
        "shared/graph/edges.csr.building,      TRANSIENT",
        "java_pid4242.hprof,                   DIAGNOSTIC",
        "oom.hprof,                            DIAGNOSTIC",
        "chunk-index/java_pid7.hprof,          DIAGNOSTIC",
        "shared/graph/java_pid7.hprof,         DIAGNOSTIC",
        "segment_0.hnsw.hprof,                 DIAGNOSTIC",
        "java_pid4242.hprof.notes,             AUTHORITATIVE"
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
    @DisplayName("a heap dump is never copied, and the reason says where it belongs instead")
    void aHeapDumpIsNeverCopied() {
        Inclusion inclusion = DataDirectoryInventory.classify("java_pid4242.hprof");

        assertThat(inclusion.includedWhen(true)).isFalse();
        assertThat(inclusion.includedWhen(false)).isFalse();
        assertThat(DataDirectoryInventory.reason("java_pid4242.hprof"))
            .contains("heap dump").contains("HeapDumpPath");
    }

    @Test
    @DisplayName("a term sketch is derived state the node rebuilds on open, and says so")
    void aTermSketchIsRebuildable() {
        Inclusion inclusion = DataDirectoryInventory.classify("segment_0.tfs");

        assertThat(inclusion.includedWhen(true)).isTrue();
        assertThat(inclusion.includedWhen(false)).isFalse();
        assertThat(DataDirectoryInventory.reason("segment_0.tfs"))
            .contains("term sketch", "rebuilds", "start-up");
    }

    @Test
    @DisplayName("a compaction's staged mapping is copied with or without derived state")
    void aStagedMappingIsAlwaysCopied() {
        String staged = "segment_0.hnsw.compact.docmap";
        Inclusion inclusion = DataDirectoryInventory.classify(staged);

        assertThat(inclusion.includedWhen(true)).isTrue();
        assertThat(inclusion.includedWhen(false))
            .as("alone, it is the only mapping that matches a graph whose swap completed")
            .isTrue();
        assertThat(DataDirectoryInventory.reason(staged))
            .contains("staged", "installs on open");
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
