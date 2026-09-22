package com.eonmux.excalibur.graph.erasure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.eonmux.excalibur.graph.dictionary.LabelDictionaryWriter;
import com.eonmux.excalibur.graph.staging.GraphBuilder;
import com.eonmux.excalibur.graph.store.EdgeStoreWriter;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.graph.vector.EntityVectors;
import com.eonmux.excalibur.graph.vector.EntityVectorWriter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What has to stop existing in the directory a graph is served out of.
 *
 * <p>Compaction rewrites the edge store. The directory the edge store lives in also holds the
 * person's name, in the entity dictionary and in the label index, and up to two kilobytes of the
 * document body per mention, in the document index, and a vector computed from that text. An
 * erasure that removes the edges and carries the rest across unchanged leaves the erased person
 * findable by searching for themselves, and leaves the prose on disk, while the node reports the
 * obligation discharged.
 */
@DisplayName("Served directory erasure")
class ServedDirectoryErasureTest {

    private static final int KNOWS = 0;
    private static final int JANE = 1;

    /** The person's name, as it appears in the dictionary and in the label index. */
    private static final String NAME = "Jane Doe";

    /** Prose copied verbatim out of an ingested document, as the entity document holds it. */
    private static final String SNIPPET = "Jane Doe was treated at the Reykjavik clinic in March";

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

    /**
     * A graph directory shaped like one the build actually publishes.
     *
     * <p>The two lexical indexes are Lucene directories that this module cannot write, so they
     * stand in here as directories of plain files holding the same text. What matters to the
     * assertion is that the bytes are in the served directory and have to leave it.
     */
    private static MappedEdgeStore servedGraph(Path dir) throws IOException {
        new EdgeStoreWriter()
            .add(0, KNOWS, JANE)
            .add(JANE, KNOWS, 2)
            .build(dir, 3);

        new LabelDictionaryWriter()
            .put(0, "Acme Clinic")
            .put(JANE, NAME)
            .put(2, "Reykjavik")
            .write(dir, GraphBuilder.ENTITY_DICTIONARY);
        new LabelDictionaryWriter().put(KNOWS, "knows")
            .write(dir, GraphBuilder.RELATION_DICTIONARY);

        try (EntityVectorWriter vectors =
                 new EntityVectorWriter(dir.resolve(EntityVectors.FILE_NAME), 4, 3)) {
            vectors.put(new float[] {0.1f, 0.2f, 0.3f, 0.4f});
            vectors.put(new float[] {0.5f, 0.6f, 0.7f, 0.8f});
            vectors.put(null);
            vectors.finish();
        }

        Path documents = Files.createDirectories(dir.resolve("documents"));
        Files.writeString(documents.resolve("_0.cfs"), JANE + " " + SNIPPET);
        Path labels = Files.createDirectories(dir.resolve("labels"));
        Files.writeString(labels.resolve("_0.cfs"), JANE + " " + NAME);

        return MappedEdgeStore.open(dir);
    }

    private static List<ErasureRequest> eraseJane() {
        return List.of(new ErasureRequest(1, new ErasureScope.Entity(JANE), Instant.now()));
    }

    /**
     * Stands in for the Lucene erasers {@code excalibur-retrieval} supplies.
     *
     * <p>Real ones open the copied index, delete the erased documents by id and force a merge so
     * the stored text actually leaves the segment files. This one does the same to the plain
     * files the fixture writes: every line whose leading id is erased is dropped, and the rest is
     * written into the replacement. What it shares with the real thing, and what the test is
     * about, is that it reads the served copy and writes only into the replacement.
     */
    private record TextIndexEraser(String name) implements ServedArtifactEraser {

        @Override
        public java.util.Set<String> names() {
            return java.util.Set.of(name);
        }

        @Override
        public boolean reaches(ErasureScope scope) {
            return scope instanceof ErasureScope.Entity;
        }

        @Override
        public void erase(Path served, Path replacement, org.roaringbitmap.RoaringBitmap erased)
                throws IOException {
            Path from = served.resolve(name);
            if (!Files.isDirectory(from)) {
                return;
            }
            Path to = Files.createDirectories(replacement.resolve(name));
            try (var entries = Files.list(from)) {
                for (Path entry : entries.toList()) {
                    List<String> kept = Files.readAllLines(entry).stream()
                        .filter(line -> !erased.contains(
                            Integer.parseInt(line.substring(0, line.indexOf(' ')))))
                        .toList();
                    Files.write(to.resolve(entry.getFileName().toString()), kept);
                }
            }
        }
    }

    @Test
    @DisplayName("an erased entity's name, prose and vector leave the served directory")
    void anErasedEntityLeavesNothingBehind(@TempDir Path parent) throws IOException {
        Path dir = parent.resolve("graph");
        MappedEdgeStore store = servedGraph(dir);

        assertThat(anyFileContains(dir, NAME)).as("the name starts out on disk").isTrue();
        assertThat(anyFileContains(dir, SNIPPET)).as("so does the prose").isTrue();

        EdgeStoreCompactor.compact(store, dir,
            EdgeStoreCompactor.resolve(store, eraseJane()), eraseJane(),
            List.of(new TextIndexEraser("documents"), new TextIndexEraser("labels")));

        assertThat(anyFileContains(dir, NAME))
            .as("the name has to stop existing, not stop being linked to")
            .isFalse();
        assertThat(anyFileContains(dir, SNIPPET))
            .as("and so does the document body the snippet was copied out of")
            .isFalse();

        try (EntityVectors vectors = EntityVectors.openIfPresent(dir)) {
            assertThat(vectors).isNotNull();
            assertThat(vectors.vectorOf(JANE))
                .as("a vector computed from the erased prose is derived from it")
                .isNull();
            assertThat(vectors.vectorOf(0))
                .as("and nobody else's vector moves or changes")
                .containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        }
    }

    @Test
    @DisplayName("an artifact nobody can erase is reported, never carried across in silence")
    void anUnreachedArtifactIsReported(@TempDir Path parent) throws IOException {
        // The failure that matters most is not leaving the data - it is leaving the data and
        // saying it is gone. With no eraser for the two Lucene directories the compaction cannot
        // touch them, and it has to say so rather than let the request be marked discharged.
        Path dir = parent.resolve("graph");
        MappedEdgeStore store = servedGraph(dir);

        EdgeStoreCompactor.CompactionResult result = EdgeStoreCompactor.compact(store, dir,
            EdgeStoreCompactor.resolve(store, eraseJane()), eraseJane(), List.of());

        assertThat(result.complete()).isFalse();
        assertThat(result.unreached().get(1L)).containsExactly("documents", "labels");
        assertThat(anyFileContains(dir.resolve(GraphBuilder.ENTITY_DICTIONARY + ".dat"), NAME))
            .as("the dictionary this compaction does reach still loses the name")
            .isFalse();
        assertThat(anyFileContains(dir.resolve("labels"), NAME))
            .as("and the index it cannot reach still holds it, which is what it reported")
            .isTrue();
        assertThat(anyFileContains(dir.resolve("documents"), SNIPPET))
            .as("prose included")
            .isTrue();
    }

    @Test
    @DisplayName("a request the compaction cannot fully reach is left outstanding")
    void anUnreachedRequestIsNotDischarged(@TempDir Path parent) throws IOException {
        Path dir = parent.resolve("graph");
        servedGraph(dir).close();

        try (ErasureLog log = ErasureLog.open(parent.resolve("requests.log"))) {
            GraphErasureCoordinator coordinator = new GraphErasureCoordinator(log,
                (stats, now) -> true, Clock.systemUTC(), null, List.of());
            coordinator.submit(new ErasureScope.Entity(JANE));

            GraphErasureCoordinator.CompactionOutcome outcome = coordinator.compactNow(dir);

            assertThat(outcome.requestsSatisfied()).isZero();
            assertThat(outcome.requestsWithheld()).isEqualTo(1);
            assertThat(outcome.reason()).contains("documents", "labels");
            assertThat(log.outstandingCount())
                .as("the obligation stays owed until something can actually meet it")
                .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("an entity with no edges still loses its name and its vector")
    void anEdgelessEntityIsStillErased(@TempDir Path parent) throws IOException {
        // "Nothing tombstoned" used to end the compaction before it began. An entity nothing was
        // ever said about is still named in the dictionary and still has a vector, and is
        // exactly the sort of entity a person asks to have removed.
        Path dir = parent.resolve("graph");
        new EdgeStoreWriter().add(0, KNOWS, 2).build(dir, 3);
        new LabelDictionaryWriter().put(0, "Acme Clinic").put(JANE, NAME).put(2, "Reykjavik")
            .write(dir, GraphBuilder.ENTITY_DICTIONARY);

        try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
            EdgeStoreCompactor.CompactionResult result = EdgeStoreCompactor.compact(store, dir,
                EdgeStoreCompactor.resolve(store, eraseJane()), eraseJane(), List.of());

            assertThat(result.rewritten()).isTrue();
            assertThat(result.edgesRemoved()).isZero();
        }
        assertThat(anyFileContains(dir, NAME)).isFalse();
        assertThat(anyFileContains(dir, "Reykjavik"))
            .as("and nobody else loses theirs")
            .isTrue();
    }

    @Test
    @DisplayName("the served directory stays complete and readable until the swap")
    void theOriginalSurvivesAFailedCompaction(@TempDir Path parent) throws IOException {
        // The class promises a crash leaves either the old graph or the new one. An eraser that
        // reads the served copy and writes into the replacement keeps that; one that edited the
        // original in place would destroy it.
        Path dir = parent.resolve("graph");
        MappedEdgeStore store = servedGraph(dir);

        assertThatThrownBy(() -> EdgeStoreCompactor.compact(store, dir,
            EdgeStoreCompactor.resolve(store, eraseJane()), eraseJane(),
            List.of(new Failing()))).isInstanceOf(IOException.class);

        store.close();
        assertThat(anyFileContains(dir, NAME))
            .as("nothing was erased, and nothing was lost either")
            .isTrue();
        try (MappedEdgeStore reopened = MappedEdgeStore.open(dir)) {
            assertThat(reopened.edgeCount()).isEqualTo(2);
        }
        assertThat(dir.resolveSibling("graph.compacting")).doesNotExist();
    }

    @Test
    @DisplayName("two erasers claiming one artifact is refused rather than silently resolved")
    void overlappingErasersAreRefused(@TempDir Path parent) throws IOException {
        Path dir = parent.resolve("graph");
        try (MappedEdgeStore store = servedGraph(dir)) {
            assertThatThrownBy(() -> EdgeStoreCompactor.compact(store, dir,
                EdgeStoreCompactor.resolve(store, eraseJane()), eraseJane(),
                List.of(new TextIndexEraser("documents"), new TextIndexEraser("documents"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documents");
        }
    }

    @Test
    @DisplayName("a withdrawn document is reported as still encoded in the entity vectors")
    void aSourceScopeCannotReachTheVectors(@TempDir Path parent) throws IOException {
        // A vector is computed from every snippet recorded for the entity, so withdrawing one
        // of the documents those snippets came from leaves a vector still encoding it.
        // Recomputing needs the embedder and the cleaned staging, neither of which a compaction
        // has, so the honest answer is to say so and leave the request owed.
        Path dir = parent.resolve("graph");
        MappedEdgeStore store = servedGraph(dir);
        List<ErasureRequest> withdraw = List.of(new ErasureRequest(7,
            new ErasureScope.Source(com.eonmux.excalibur.graph.provenance.SourceType.DOCUMENT, 42),
            Instant.now()));

        EdgeStoreCompactor.CompactionResult result = EdgeStoreCompactor.compact(store, dir,
            EdgeStoreCompactor.resolve(store, withdraw), withdraw,
            List.of(new TextIndexEraser("documents"), new TextIndexEraser("labels")));

        assertThat(result.unreached().get(7L))
            .as("the vectors and both lexical indexes answer an entity scope and not this one")
            .contains(EntityVectors.FILE_NAME, "documents", "labels")
            .doesNotContain(GraphBuilder.ENTITY_DICTIONARY + ".dat");
    }

    @Test
    @DisplayName("an entity id no graph could hold names nobody rather than somebody else")
    void anUnstorableEntityIdNamesNobody() {
        // Entity ids are 32-bit wherever they are stored. Narrowing a larger one would erase
        // whoever happened to hold the truncated id.
        List<ErasureRequest> request = List.of(new ErasureRequest(1,
            new ErasureScope.Entity(Integer.MAX_VALUE + 1L), Instant.now()));

        assertThat(EdgeStoreCompactor.erasedEntities(request).isEmpty()).isTrue();
        assertThat(EdgeStoreCompactor.erasedEntities(null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("an artifact an eraser could not produce is carried across rather than dropped")
    void anArtifactTheEraserCouldNotProduceIsKept(@TempDir Path parent) throws IOException {
        // The dictionary is published as two renames, and a crash between them leaves one file
        // without the other. The eraser cannot open half a dictionary, so it writes nothing -
        // and treating the names as rebuilt anyway would have the carry-over skip the half that
        // is there, deleting it with the retired copy. A compaction may not lose data.
        Path dir = parent.resolve("graph");
        MappedEdgeStore store = servedGraph(dir);
        Files.delete(dir.resolve(GraphBuilder.ENTITY_DICTIONARY + ".idx"));

        EdgeStoreCompactor.compact(store, dir, EdgeStoreCompactor.resolve(store, eraseJane()),
            eraseJane(), List.of(new TextIndexEraser("documents"),
                new TextIndexEraser("labels")));

        assertThat(dir.resolve(GraphBuilder.ENTITY_DICTIONARY + ".dat"))
            .as("the orphaned half survives for an operator to recover from")
            .exists();
    }

    @Test
    @DisplayName("a caller with more to work with can take over one of the built-in erasers")
    void aSuppliedEraserReplacesABuiltIn(@TempDir Path parent) throws IOException {
        // The built-in vector eraser can clear an erased entity's slot and no more, so it says
        // it cannot answer a source scope. A caller holding the embedder and the erased staging
        // can rebuild the file outright, and takes the name over to say so - otherwise the
        // request could be reported unreachable for ever with nothing able to change that.
        Path dir = parent.resolve("graph");
        MappedEdgeStore store = servedGraph(dir);
        List<ErasureRequest> withdraw = List.of(new ErasureRequest(7,
            new ErasureScope.Source(com.eonmux.excalibur.graph.provenance.SourceType.DOCUMENT, 42),
            Instant.now()));

        EdgeStoreCompactor.CompactionResult result = EdgeStoreCompactor.compact(store, dir,
            EdgeStoreCompactor.resolve(store, withdraw), withdraw,
            List.of(new Rebuilding(EntityVectors.FILE_NAME), new Rebuilding("documents"),
                new Rebuilding("labels")));

        assertThat(result.unreached())
            .as("every artifact is now claimed by something that answers this scope")
            .isEmpty();
    }

    @Test
    @DisplayName("an eraser is told which requests the compaction is discharging")
    void anEraserSeesTheBatch(@TempDir Path parent) throws IOException {
        // An artifact whose content is derived from the withdrawn prose answers an entity scope
        // by deleting one entry and a source scope only by rebuilding from cleaned staging. The
        // erased ids cannot tell those apart - a source scope names none - so an eraser that has
        // both strategies has to be told what it is discharging or it must always take the
        // expensive one.
        Path dir = parent.resolve("graph");
        MappedEdgeStore store = servedGraph(dir);
        Recording recording = new Recording();
        List<ErasureRequest> withdraw = List.of(new ErasureRequest(7,
            new ErasureScope.Source(com.eonmux.excalibur.graph.provenance.SourceType.DOCUMENT, 42),
            Instant.now()));

        EdgeStoreCompactor.compact(store, dir,
            EdgeStoreCompactor.resolve(store, withdraw), withdraw, List.of(recording));

        assertThat(recording.scopes)
            .as("the scope, not merely the entity ids the scope happens to name")
            .containsExactly(withdraw.get(0).scope());
    }

    @Test
    @DisplayName("a withdrawn document reaches the erasers even when it matched no edge")
    void aProvenanceScopeRunsTheErasersWithoutEntityIds(@TempDir Path parent) throws IOException {
        // The prose a document contributed to an entity's document is in the served index
        // whether or not that document also produced an edge. Deciding beforehand that a request
        // naming no entity has nothing to do is what stopped the one eraser able to remove it
        // from ever being asked.
        Path dir = parent.resolve("graph");
        MappedEdgeStore store = servedGraph(dir);
        Recording recording = new Recording();
        List<ErasureRequest> withdraw = List.of(new ErasureRequest(7,
            new ErasureScope.Source(com.eonmux.excalibur.graph.provenance.SourceType.CONVERSATION, 99),
            Instant.now()));

        EdgeStoreCompactor.CompactionResult result = EdgeStoreCompactor.compact(store, dir,
            EdgeStoreCompactor.resolve(store, withdraw), withdraw, List.of(recording));

        assertThat(result.rewritten())
            .as("no edge carries that provenance, and the derived prose still has to go")
            .isTrue();
        assertThat(recording.scopes).hasSize(1);
    }

    /** An eraser that records the scopes it was handed and produces its artifact regardless. */
    private static final class Recording implements ServedArtifactEraser {

        private final List<ErasureScope> scopes = new java.util.ArrayList<>();

        @Override
        public java.util.Set<String> names() {
            return java.util.Set.of("documents");
        }

        @Override
        public boolean reaches(ErasureScope scope) {
            return true;
        }

        @Override
        public void erase(Path served, Path replacement, org.roaringbitmap.RoaringBitmap erased)
                throws IOException {
            throw new IOException("the batch-aware overload should have been called");
        }

        @Override
        public void erase(Path served, Path replacement, org.roaringbitmap.RoaringBitmap erased,
                          java.util.Collection<ErasureRequest> requests) throws IOException {
            requests.forEach(request -> scopes.add(request.scope()));
            Files.createDirectories(replacement.resolve("documents"));
            Files.writeString(replacement.resolve("documents").resolve("_0.cfs"), "rebuilt");
        }
    }

    /** Stands in for an eraser that rebuilds its artifact from post-erasure staging. */
    private record Rebuilding(String name) implements ServedArtifactEraser {

        @Override
        public java.util.Set<String> names() {
            return java.util.Set.of(name);
        }

        @Override
        public boolean reaches(ErasureScope scope) {
            return true;
        }

        @Override
        public void erase(Path served, Path replacement, org.roaringbitmap.RoaringBitmap erased)
                throws IOException {
            Files.createDirectories(replacement);
            Files.writeString(replacement.resolve(name), "rebuilt from staging");
        }
    }

    /** An eraser that fails, so the compaction cannot reach the swap. */
    private record Failing() implements ServedArtifactEraser {

        @Override
        public java.util.Set<String> names() {
            return java.util.Set.of("documents", "labels");
        }

        @Override
        public boolean reaches(ErasureScope scope) {
            return true;
        }

        @Override
        public void erase(Path served, Path replacement, org.roaringbitmap.RoaringBitmap erased)
                throws IOException {
            throw new IOException("the index could not be rewritten");
        }
    }
}
