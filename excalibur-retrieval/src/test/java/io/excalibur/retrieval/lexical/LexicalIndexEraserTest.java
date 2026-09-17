package io.excalibur.retrieval.lexical;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import io.excalibur.graph.dictionary.LabelDictionaryWriter;
import io.excalibur.graph.erasure.EdgeStoreCompactor;
import io.excalibur.graph.erasure.ErasureRequest;
import io.excalibur.graph.erasure.ErasureScope;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.staging.GraphBuilder;
import io.excalibur.graph.store.EdgeStoreWriter;
import io.excalibur.graph.store.MappedEdgeStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What has to stop existing in the two Lucene indexes a graph is served beside.
 *
 * <p>{@code excalibur-graph} rewrites the edge store, the entity dictionary and the entity
 * vectors, and cannot open a Lucene directory without depending on the module that writes one.
 * So the label index still holds the erased person's name and the document index still holds the
 * prose their snippets were copied out of - which is the half of an erasure a search actually
 * answers from.
 *
 * <p>The assertion is bytes on disk rather than a query returning nothing. A tombstoned document
 * is invisible to search and still readable by anyone holding the segment file, and an erasure
 * obligation is about the second of those.
 */
@DisplayName("Lucene index erasure")
class LexicalIndexEraserTest {

    private static final int KNOWS = 0;
    private static final int JANE = 1;
    private static final int CLINIC = 0;

    /** The person's name, as the entity dictionary holds it: the string, uncompressed. */
    private static final String NAME = "Jane Quazzlethorpe";

    /**
     * The same name as the segment files hold it.
     *
     * <p>Lucene compresses the stored copy of a field, so the string a caller put in is not
     * findable in the index by scanning for its bytes; what is findable is the analysed term,
     * lowercased, in the terms dictionary. That term is the byte-level witness this test uses,
     * and it is a sound one: it is only absent once the segment holding it has been rewritten,
     * which is the same rewrite that takes the compressed copy of the prose with it.
     */
    private static final String NAME_TERM = "quazzlethorpe";

    /** Prose copied verbatim out of an ingested document, as the entity document holds it. */
    private static final String SNIPPET =
        "Jane Quazzlethorpe was treated at the Reykjavik clinic for zymurgic fatigue in March";

    /** A term of that prose and of nothing else, as the segment files hold it. */
    private static final String SNIPPET_TERM = "zymurgic";

    /** Somebody else's text, which an erasure about Jane must leave exactly where it is. */
    private static final String BYSTANDER = "Acme Clinic has operated since 1971";

    /** A term of that text and of nothing else. */
    private static final String BYSTANDER_TERM = "operated";

    private static final double RAM_BUFFER_MB = 4.0;

    /** Whether any file at or under {@code root} holds {@code text} as bytes. */
    static boolean anyFileContains(Path root, String text) throws IOException {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        if (!Files.exists(root)) {
            return false;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                if (indexOf(Files.readAllBytes(path), needle) >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** A graph directory shaped like one a build publishes, with real Lucene indexes in it. */
    private static MappedEdgeStore servedGraph(Path dir) throws IOException {
        new EdgeStoreWriter()
            .add(CLINIC, KNOWS, JANE)
            .add(JANE, KNOWS, 2)
            .build(dir, 3);

        new LabelDictionaryWriter()
            .put(CLINIC, "Acme Clinic")
            .put(JANE, NAME)
            .put(2, "Reykjavik")
            .write(dir, GraphBuilder.ENTITY_DICTIONARY);
        new LabelDictionaryWriter().put(KNOWS, "knows")
            .write(dir, GraphBuilder.RELATION_DICTIONARY);

        try (LexicalIndex labels = LexicalIndex.open(dir.resolve("labels"), RAM_BUFFER_MB)) {
            labels.put(CLINIC, "Acme Clinic");
            labels.put(JANE, NAME);
            labels.put(2, "Reykjavik");
            labels.commit();
        }
        try (LexicalIndex documents = LexicalIndex.open(dir.resolve("documents"),
                RAM_BUFFER_MB)) {
            documents.put(CLINIC, BYSTANDER);
            documents.put(JANE, SNIPPET);
            documents.put(2, "Reykjavik is the capital of Iceland");
            documents.commit();
        }
        return MappedEdgeStore.open(dir);
    }

    private static List<ErasureRequest> eraseJane() {
        return List.of(new ErasureRequest(1, new ErasureScope.Entity(JANE), Instant.now()));
    }

    @Test
    @DisplayName("an erased entity's name and prose leave the served Lucene indexes")
    void anErasedEntityLeavesBothIndexes(@TempDir Path parent) throws IOException {
        Path dir = parent.resolve("graph");
        MappedEdgeStore store = servedGraph(dir);

        assertThat(anyFileContains(dir, NAME))
            .as("the name starts out in the dictionary, verbatim")
            .isTrue();
        assertThat(anyFileContains(dir, NAME_TERM))
            .as("and in both Lucene indexes, as a term")
            .isTrue();
        assertThat(anyFileContains(dir, SNIPPET_TERM))
            .as("so does the prose")
            .isTrue();

        EdgeStoreCompactor.CompactionResult result = EdgeStoreCompactor.compact(store, dir,
            EdgeStoreCompactor.resolve(store, eraseJane()), eraseJane(),
            List.of(LexicalIndexEraser.labels(RAM_BUFFER_MB),
                LexicalIndexEraser.documents(RAM_BUFFER_MB)));

        assertThat(result.unreached())
            .as("nothing in the directory is beyond an entity-scoped erasure now")
            .isEmpty();
        assertThat(anyFileContains(dir, NAME))
            .as("the name has to stop existing, not stop being searchable")
            .isFalse();
        assertThat(anyFileContains(dir, NAME_TERM))
            .as("in the segment files as much as in the dictionary")
            .isFalse();
        assertThat(anyFileContains(dir, SNIPPET_TERM))
            .as("and so does the document body the snippet was copied out of")
            .isFalse();
        assertThat(anyFileContains(dir, BYSTANDER_TERM))
            .as("nobody else's text moves")
            .isTrue();
    }

    @Test
    @DisplayName("the surviving documents are still searchable after the rewrite")
    void theSurvivorsAreStillSearchable(@TempDir Path parent) throws IOException {
        Path dir = parent.resolve("graph");
        MappedEdgeStore store = servedGraph(dir);

        EdgeStoreCompactor.compact(store, dir,
            EdgeStoreCompactor.resolve(store, eraseJane()), eraseJane(),
            List.of(LexicalIndexEraser.labels(RAM_BUFFER_MB),
                LexicalIndexEraser.documents(RAM_BUFFER_MB)));

        try (LexicalIndex documents = LexicalIndex.open(dir.resolve("documents"),
                RAM_BUFFER_MB)) {
            assertThat(documents.textOf(CLINIC)).isEqualTo(BYSTANDER);
            assertThat(documents.textOf(JANE)).as("and the erased one is simply not there")
                .isNull();
        }
        try (LexicalIndex labels = LexicalIndex.open(dir.resolve("labels"), RAM_BUFFER_MB)) {
            assertThat(labels.documentCount()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("the document index says it cannot answer a withdrawn source on its own")
    void theDocumentIndexCannotAnswerASourceScope(@TempDir Path parent) throws IOException {
        // An entity document is prose copied out of the documents the entity was mentioned in.
        // Withdrawing one of those documents leaves the rest of the entity's text in place, and
        // deleting by entity id would erase prose nobody asked to remove. Only a rebuild from
        // cleaned staging can answer that, so this eraser says so instead of being believed.
        Path dir = parent.resolve("graph");
        MappedEdgeStore store = servedGraph(dir);
        List<ErasureRequest> withdraw = List.of(new ErasureRequest(7,
            new ErasureScope.Source(SourceType.DOCUMENT, 42), Instant.now()));

        EdgeStoreCompactor.CompactionResult result = EdgeStoreCompactor.compact(store, dir,
            EdgeStoreCompactor.resolve(store, withdraw), withdraw,
            List.of(LexicalIndexEraser.labels(RAM_BUFFER_MB),
                LexicalIndexEraser.documents(RAM_BUFFER_MB)));

        assertThat(result.unreached().get(7L))
            .as("the label index holds nobody's prose, so a withdrawn document does not touch it")
            .contains("documents")
            .doesNotContain("labels");
    }

    @Test
    @DisplayName("an eraser never modifies the directory it is reading")
    void theServedCopyIsNotTouched(@TempDir Path parent) throws IOException {
        // The compaction's crash guarantee is that the served directory stays complete until a
        // single atomic swap. An eraser that opened the served index for writing would break it
        // for every other artifact too, so this checks the served copy directly.
        Path dir = parent.resolve("graph");
        Path replacement = parent.resolve("replacement");
        servedGraph(dir).close();

        org.roaringbitmap.RoaringBitmap erased = new org.roaringbitmap.RoaringBitmap();
        erased.add(JANE);
        Files.createDirectories(replacement);
        LexicalIndexEraser.documents(RAM_BUFFER_MB).erase(dir, replacement, erased);

        assertThat(anyFileContains(dir.resolve("documents"), SNIPPET_TERM))
            .as("the served copy is untouched until the swap")
            .isTrue();
        assertThat(anyFileContains(replacement.resolve("documents"), SNIPPET_TERM))
            .as("and the replacement is the one without the prose")
            .isFalse();
    }
}
