package com.eonmux.excalibur.backup.snapshot;

import com.eonmux.excalibur.fsck.check.FileClassifier;
import com.eonmux.excalibur.fsck.check.FileKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code excalibur-fsck} and {@code excalibur-backup} agree on what every file is.
 *
 * <p>The two tools once kept name rules of their own, and drifted: the document registry's
 * replacement was a leftover to one and state the other copied and restored; a split's children
 * were scratch to one and unrecognised by the other; the fact id floor's replacement was known to
 * neither. Backup now takes every file's kind from fsck's classifier, and this states, over one
 * sample of every name the engine writes, what each tool makes of it - and that the two answers
 * mean the same thing.</p>
 */
@DisplayName("fsck and backup classify every file the same way")
class ClassificationAgreementTest {

    /** One path of every shape a data directory holds, with what each tool must make of it. */
    static Stream<Sample> samples() {
        return Stream.of(
            new Sample("catalog.dat", FileKind.CATALOG, Inclusion.AUTHORITATIVE),
            new Sample("routing.dir", FileKind.ROUTING_DIRECTORY, Inclusion.AUTHORITATIVE),
            new Sample("segment_3.dat", FileKind.SEGMENT_DATA, Inclusion.AUTHORITATIVE),
            new Sample("segment_3.wal", FileKind.WRITE_AHEAD_LOG, Inclusion.AUTHORITATIVE),
            new Sample("segment_3.hnsw", FileKind.HNSW_GRAPH, Inclusion.REQUIRED_DERIVED),
            new Sample("segment_3.hnsw.docmap", FileKind.HNSW_DOCUMENT_MAP,
                Inclusion.REQUIRED_DERIVED),
            new Sample("segment_3.hnsw.compact.docmap", FileKind.HNSW_STAGED_DOCUMENT_MAP,
                Inclusion.REQUIRED_DERIVED),
            new Sample("segment_3.hnsw.compact", FileKind.STAGING_LEFTOVER,
                Inclusion.REQUIRED_DERIVED),
            new Sample("segment_3.hnsw.pqcb", FileKind.PQ_CODEBOOK, Inclusion.REBUILDABLE),
            new Sample("segment_3.lsh/lsh_table_0.dat", FileKind.LSH_TABLE,
                Inclusion.AUTHORITATIVE),
            new Sample("document-registry", FileKind.DOCUMENT_REGISTRY, Inclusion.AUTHORITATIVE),
            new Sample("document-registry.in-flight", FileKind.IN_FLIGHT_JOURNAL,
                Inclusion.AUTHORITATIVE),
            new Sample("source-catalog", FileKind.SOURCE_CATALOG, Inclusion.AUTHORITATIVE),
            new Sample("source-status", FileKind.SOURCE_STATUS, Inclusion.AUTHORITATIVE),
            new Sample("erasure-receipts", FileKind.ERASURE_RECEIPTS, Inclusion.AUTHORITATIVE),
            new Sample("shared/graph-staging/triples.log", FileKind.GRAPH_STAGING_LOG,
                Inclusion.AUTHORITATIVE),
            new Sample("shared/graph-staging/erasure.log", FileKind.GRAPH_STAGING_LOG,
                Inclusion.AUTHORITATIVE),
            new Sample("shared/graph-staging/asserted.last-id", FileKind.GRAPH_STAGING_LOG,
                Inclusion.AUTHORITATIVE),
            new Sample("shared/graph-staging/erasure.interrupted", FileKind.GRAPH_ERASURE_MARKER,
                Inclusion.AUTHORITATIVE),
            new Sample("shared/graph-staging/unpublished.changes",
                FileKind.GRAPH_UNPUBLISHED_MARKER, Inclusion.AUTHORITATIVE),
            new Sample("shared/graph/edges.csr", FileKind.GRAPH_STORE_PART,
                Inclusion.REBUILDABLE),
            new Sample("shared/graph/labels/_0.cfs", FileKind.GRAPH_STORE_PART,
                Inclusion.REBUILDABLE),
            new Sample("shared/graph.previous/edgestore.meta", FileKind.GRAPH_STORE_PART,
                Inclusion.REBUILDABLE),
            new Sample("shared/graph.building/entity_vectors.dat.body", FileKind.GRAPH_STORE_PART,
                Inclusion.TRANSIENT),
            new Sample("shared/graph.building/entity_vectors.dat.assembled",
                FileKind.GRAPH_STORE_PART, Inclusion.TRANSIENT),
            new Sample("shared/graph.building/edgestore.meta.tmp", FileKind.GRAPH_STORE_PART,
                Inclusion.TRANSIENT),
            new Sample("chunk-index/manifest", FileKind.LEXICAL_STORE_PART,
                Inclusion.REBUILDABLE),
            new Sample("chunk-index/seg-0/terms.dict", FileKind.LEXICAL_STORE_PART,
                Inclusion.REBUILDABLE),
            new Sample("chunk-index/seg-0.keymap", FileKind.LEXICAL_KEY_LOOKUP,
                Inclusion.REBUILDABLE),
            new Sample("chunk-index/erasure.log", FileKind.LEXICAL_STORE_PART,
                Inclusion.REBUILDABLE),
            new Sample("lucene-chunks/_0.cfs", FileKind.LUCENE_CHUNK_INDEX_PART,
                Inclusion.REBUILDABLE),
            new Sample("lucene-chunks/segments_2", FileKind.LUCENE_CHUNK_INDEX_PART,
                Inclusion.REBUILDABLE),
            new Sample("lucene-chunks/write.lock", FileKind.LUCENE_CHUNK_INDEX_PART,
                Inclusion.REBUILDABLE),
            new Sample("lucene-chunks/erasure.log", FileKind.LUCENE_CHUNK_ERASURE_LOG,
                Inclusion.REBUILDABLE),
            // The staged names each writer leaves when it does not finish.
            new Sample("routing.dir.tmp", FileKind.STAGING_LEFTOVER, Inclusion.TRANSIENT),
            new Sample("document-registry.3.new", FileKind.STAGING_LEFTOVER, Inclusion.TRANSIENT),
            new Sample("document-registry.new", FileKind.STAGING_LEFTOVER, Inclusion.TRANSIENT),
            new Sample("shared/graph-staging/asserted.last-id.writing",
                FileKind.STAGING_LEFTOVER, Inclusion.TRANSIENT),
            new Sample("shared/graph-staging/triples.log.erasing", FileKind.STAGING_LEFTOVER,
                Inclusion.TRANSIENT),
            new Sample("shared/graph-staging/entities.labels.rewriting",
                FileKind.STAGING_LEFTOVER, Inclusion.TRANSIENT),
            new Sample("segment_3.dat.left", FileKind.STAGING_LEFTOVER, Inclusion.TRANSIENT),
            new Sample("segment_3.dat.right", FileKind.STAGING_LEFTOVER, Inclusion.TRANSIENT),
            new Sample("segment_3.dat.compact", FileKind.STAGING_LEFTOVER, Inclusion.TRANSIENT),
            new Sample("segment_3.hnsw.pqcb.tmp", FileKind.STAGING_LEFTOVER, Inclusion.TRANSIENT),
            new Sample("chunk-index/.compact-seg-0", FileKind.STAGING_LEFTOVER,
                Inclusion.TRANSIENT),
            new Sample("chunk-index/seg-0.tomb.tmp", FileKind.STAGING_LEFTOVER,
                Inclusion.TRANSIENT),
            new Sample("lucene-chunks/_0_Lucene99_0.tmp", FileKind.STAGING_LEFTOVER,
                Inclusion.TRANSIENT),
            new Sample("segment_3.tfs", FileKind.OBSOLETE, Inclusion.OBSOLETE),
            new Sample("segment_3.tfs.0b7e1f7a.tmp", FileKind.OBSOLETE, Inclusion.OBSOLETE),
            new Sample("java_pid4242.hprof", FileKind.DIAGNOSTIC_DUMP, Inclusion.DIAGNOSTIC),
            new Sample("shared/graph/java_pid7.hprof", FileKind.DIAGNOSTIC_DUMP,
                Inclusion.DIAGNOSTIC),
            // Names nothing in this build writes are kept, whatever they look like.
            new Sample("segment_3.wal.old", FileKind.UNRECOGNISED, Inclusion.AUTHORITATIVE),
            new Sample("something-a-future-build-writes", FileKind.UNRECOGNISED,
                Inclusion.AUTHORITATIVE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("each tool makes of a file what the other expects")
    void bothToolsAgree(Sample sample) {
        assertThat(FileClassifier.classify(sample.path())).as("fsck").isEqualTo(sample.kind());
        assertThat(DataDirectoryInventory.classify(sample.path())).as("backup")
            .isEqualTo(sample.inclusion());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("what backup leaves out as scratch is what fsck reports as a leftover")
    void scratchMeansTheSameToBoth(Sample sample) {
        String name = sample.path().substring(sample.path().lastIndexOf('/') + 1);
        boolean leftover = FileClassifier.classify(sample.path()) == FileKind.STAGING_LEFTOVER
            || (FileClassifier.classify(sample.path()) == FileKind.GRAPH_STORE_PART
                && FileKind.isLeftover(name));
        boolean stagedGraph = FileKind.SEGMENT_STAGED_GRAPH_NAME.matcher(name).matches();

        assertThat(DataDirectoryInventory.classify(sample.path()) == Inclusion.TRANSIENT)
            .as("the one leftover backup keeps is a compaction's staged graph, which it must "
                + "copy with the staged mapping it decides the meaning of")
            .isEqualTo(leftover && !stagedGraph);
    }

    @Test
    @DisplayName("every kind fsck knows appears in the samples, so a new one needs a row here")
    void everyKindIsSampled() {
        Set<FileKind> sampled = samples().map(Sample::kind)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(FileKind.class)));

        assertThat(sampled).containsAll(Arrays.stream(FileKind.values())
            .filter(kind -> kind != FileKind.NODE_LOCK).toList());
    }

    @Test
    @DisplayName("the lock is never inventoried, so backup refuses to classify it")
    void theLockIsNotInventoried() {
        assertThat(FileClassifier.classify(".excalibur-lock")).isEqualTo(FileKind.NODE_LOCK);
        assertThatThrownBy(() -> DataDirectoryInventory.classify(".excalibur-lock"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("never inventoried");
    }

    /**
     * One file and what each tool must make of it.
     *
     * @param path      Its path relative to the data directory
     * @param kind      What fsck classifies it as
     * @param inclusion What backup does with it
     */
    record Sample(String path, FileKind kind, Inclusion inclusion) {

        @Override
        public String toString() {
            return path;
        }
    }
}
