package com.eonmux.excalibur.retrieval.build;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphBuilder;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.staging.LabelInterner;
import com.eonmux.excalibur.graph.staging.PublishFence;
import com.eonmux.excalibur.graph.staging.PublishRefusedException;
import com.eonmux.excalibur.graph.staging.StagedErasure;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.graph.temporal.SupersessionPolicy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A whole graph directory built around what an erasure still owes.
 *
 * <p>The edge store is one of five artifacts a build writes, and the one least about a person:
 * the dictionary and the label index hold their name, and the entity documents and the vectors
 * computed from them hold the prose they were mentioned in. A fence that kept a fact out of the
 * store alone would publish all of that.</p>
 */
@DisplayName("Graph directory build around outstanding erasures")
class WithheldDirectoryBuildTest {

    private static final double RAM_BUFFER_MB = 4.0;

    /** A term of the name being erased. */
    private static final String NAME_TERM = "quazzlethorpe";

    /** A term of the prose of the document being withdrawn, and of nothing else. */
    private static final String WITHDRAWN_TERM = "zymurgic";

    /** A term of prose nobody withdrew. */
    private static final String KEPT_TERM = "thaumaturgy";

    private static Provenance document(long id) {
        return Provenance.of(SourceType.DOCUMENT, id, 0.9, 1);
    }

    /** Whether any file under {@code root} holds {@code text} as bytes, in any ASCII case. */
    private static boolean anyFileContains(Path root, String text) throws IOException {
        byte[] needle = text.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        if (!Files.exists(root)) {
            return false;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                byte[] haystack = Files.readAllBytes(path);
                outer:
                for (int i = 0; i + needle.length <= haystack.length; i++) {
                    for (int j = 0; j < needle.length; j++) {
                        if (Character.toLowerCase(haystack[i + j]) != needle[j]) {
                            continue outer;
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** Stages a person, a withdrawn document's prose and somebody else's. */
    private static void stage(GraphStagingStore staging) throws IOException {
        staging.append(ExtractedTriple.alwaysValid(
            "Alice Quazzlethorpe", "knows", "Bob Ordinary", document(42)));
        staging.append(ExtractedTriple.alwaysValid(
            "Bob Ordinary", "knows", "Carol Plain", document(77)));
        staging.appendEntityText(staging.entityIdOf("Alice Quazzlethorpe"),
            "Alice Quazzlethorpe was treated for zymurgic fatigue", document(42));
        staging.appendEntityText(staging.entityIdOf("Bob Ordinary"),
            "Bob Ordinary lectures on thaumaturgy", document(77));
        staging.flush();
    }

    /** A fence over {@code withheld} that admits the swap, counting what it withheld. */
    private static PublishFence admitting(StagedErasure withheld) {
        return new PublishFence() {
            @Override
            public StagedErasure withheld() {
                return withheld;
            }

            @Override
            public int obligations() {
                return 1;
            }

            @Override
            public void swap(Swap swap) throws IOException {
                swap.run();
            }
        };
    }

    /** Withholds one entity's facts, prose and name. */
    private static StagedErasure entity(int id) {
        return new StagedErasure() {
            @Override
            public boolean erasesFact(int subject, int relation, int object, int validFrom,
                                      int validTo, Provenance provenance) {
                return subject == id || object == id;
            }

            @Override
            public boolean erasesSnippet(int entityId, Provenance provenance) {
                return entityId == id;
            }

            @Override
            public boolean erasesLabel(int entityId) {
                return entityId == id;
            }
        };
    }

    /** Withholds one document's facts and prose, and no name. */
    private static StagedErasure source(long sourceId) {
        return new StagedErasure() {
            @Override
            public boolean erasesFact(int subject, int relation, int object, int validFrom,
                                      int validTo, Provenance provenance) {
                return provenance.sourceId() == sourceId;
            }

            @Override
            public boolean erasesSnippet(int entityId, Provenance provenance) {
                return provenance.sourceId() == sourceId;
            }

            @Override
            public boolean erasesLabel(int entityId) {
                return false;
            }
        };
    }

    private static GraphDirectoryBuilder.Published<Long> build(GraphStagingStore staging,
                                                              Path target, PublishFence fence,
                                                              GraphDirectoryBuilder.EntityEmbedder
                                                                  embedder)
            throws IOException {
        return GraphDirectoryBuilder.build(staging, target, SupersessionPolicy.NONE,
            RAM_BUFFER_MB, embedder, 4, published -> {
                try (MappedEdgeStore store = MappedEdgeStore.open(published)) {
                    return store.edgeCount();
                }
            }, fence);
    }

    @Test
    @DisplayName("a person owed an erasure is in no artifact of the published directory")
    void anErasedPersonIsInNoArtifact(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("graph");
        List<String> embedded = new ArrayList<>();
        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("staging"))) {
            stage(staging);
            int alice = staging.entityIdOf("Alice Quazzlethorpe");

            GraphDirectoryBuilder.Published<Long> built =
                build(staging, target, admitting(entity(alice)), texts -> {
                    embedded.addAll(texts);
                    return texts.stream().map(text -> new float[] { 1, 0, 0, 0 }).toList();
                });

            assertThat(built.result().edges()).isEqualTo(1);
            assertThat(built.result().withheld()).isEqualTo(1);
            assertThat(embedded.get(alice))
                .as("the vector is computed from what is published, not from staging")
                .isEqualTo(LabelInterner.erasedLabel(alice));
            assertThat(GraphDirectoryBuilder.entityDocuments(staging, entity(alice)))
                .doesNotContain("Alice Quazzlethorpe was treated for zymurgic fatigue");
        }

        assertThat(anyFileContains(target, NAME_TERM))
            .as("the name, in the dictionary and the label index")
            .isFalse();
        assertThat(anyFileContains(target, WITHDRAWN_TERM))
            .as("the prose, in the entity documents")
            .isFalse();
        assertThat(anyFileContains(target, KEPT_TERM)).isTrue();
    }

    @Test
    @DisplayName("a withdrawn document's prose is left out and the names it mentioned are kept")
    void aWithdrawnDocumentsProseIsLeftOut(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("graph");
        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("staging"))) {
            stage(staging);

            assertThat(build(staging, target, admitting(source(42)), null).result().edges())
                .isEqualTo(1);
        }

        assertThat(anyFileContains(target, WITHDRAWN_TERM)).isFalse();
        assertThat(anyFileContains(target, NAME_TERM))
            .as("the person the document mentioned did not ask to be forgotten")
            .isTrue();
        assertThat(anyFileContains(target, KEPT_TERM)).isTrue();
    }

    @Test
    @DisplayName("a refused publish leaves the served graph and no scratch behind")
    void aRefusedPublishLeavesNothing(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("graph");
        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("staging"))) {
            staging.append(ExtractedTriple.alwaysValid("Dan", "knows", "Eve", document(1)));
            build(staging, target, PublishFence.NONE, null);
            stage(staging);

            PublishFence refusing = new PublishFence() {
                @Override
                public StagedErasure withheld() {
                    return StagedErasure.NOTHING;
                }

                @Override
                public int obligations() {
                    return 0;
                }

                @Override
                public void swap(Swap swap) throws PublishRefusedException {
                    throw new PublishRefusedException("overtaken");
                }
            };

            assertThatThrownBy(() -> build(staging, target, refusing, null))
                .isInstanceOf(PublishRefusedException.class);
        }

        assertThat(GraphBuilder.scratchFor(target))
            .as("an assembly that may hold what the refusing erasure covers is not kept")
            .doesNotExist();
        assertThat(anyFileContains(target, NAME_TERM))
            .as("the graph served before the build still is")
            .isFalse();
        try (MappedEdgeStore store = MappedEdgeStore.open(target)) {
            assertThat(store.edgeCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a build that withholds every fact publishes nothing and says how many")
    void withholdingEverythingPublishesNothing(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("graph");
        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("staging"))) {
            staging.append(ExtractedTriple.alwaysValid(
                "Alice Quazzlethorpe", "knows", "Bob Ordinary", document(42)));

            GraphDirectoryBuilder.Published<Long> built =
                build(staging, target, admitting(source(42)), null);

            assertThat(built.result().published()).isFalse();
            assertThat(built.result().withheld()).isEqualTo(1);
            assertThat(built.opened()).isNull();
        }
        assertThat(target).doesNotExist();
        assertThat(GraphBuilder.scratchFor(target)).doesNotExist();
    }
}
