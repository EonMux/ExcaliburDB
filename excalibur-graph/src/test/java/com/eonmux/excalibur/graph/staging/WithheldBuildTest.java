package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.dictionary.LabelDictionary;
import com.eonmux.excalibur.graph.file.Durability;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.graph.temporal.SupersessionPolicy;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A build that leaves out what staging still holds and is no longer to be published.
 *
 * <p>Staging keeps a fact until a compaction erases it, and an erasure obligation is outstanding
 * from the moment it is recorded. A build in between replays staging as it stands, so it has to
 * be told what to leave out - and its publish has to be refusable, for an obligation recorded
 * while it ran.</p>
 */
@DisplayName("Building around what is withheld")
class WithheldBuildTest {

    private static Provenance document(long id) {
        return Provenance.of(SourceType.DOCUMENT, id, 0.9, 1);
    }

    /** Withholds one entity, by id, the way an entity-scoped erasure covers it. */
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

    /** A fence that withholds {@code withheld} and refuses every swap. */
    private static PublishFence refusing(StagedErasure withheld) {
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
            public void swap(Swap swap) throws PublishRefusedException {
                throw new PublishRefusedException("an erasure was recorded during the build");
            }
        };
    }

    @Test
    @DisplayName("what is withheld is not in the store, and its name is not in the dictionary")
    void withheldFactsAndLabelsAreLeftOut(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("graph");
        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("staging"))) {
            staging.append(ExtractedTriple.alwaysValid("Ada", "knows", "Grace", document(1)));
            staging.append(ExtractedTriple.alwaysValid("Grace", "knows", "Linus", document(2)));
            int ada = staging.entityIdOf("Ada");
            int grace = staging.entityIdOf("Grace");
            int linus = staging.entityIdOf("Linus");

            GraphBuilder.BuildResult result = GraphBuilder.buildInto(staging, target,
                SupersessionPolicy.NONE, entity(ada));
            GraphBuilder.publish(GraphBuilder.scratchFor(target), target);

            assertThat(result.edges()).isEqualTo(1);
            assertThat(result.withheld()).isEqualTo(1);
            assertThat(result.entities())
                .as("ids never move, so the store still addresses every one")
                .isEqualTo(3);
            try (MappedEdgeStore store = MappedEdgeStore.open(target)) {
                assertThat(store.edgeCount()).isEqualTo(1);
                assertThat(store.expand(new long[] { grace }, 1, null, TemporalFilter.ALL))
                    .contains(linus)
                    .doesNotContain(ada);
            }
            try (LabelDictionary names = LabelDictionary.open(target,
                    GraphBuilder.ENTITY_DICTIONARY, "entity:")) {
                assertThat(names.apply(ada)).isEqualTo(LabelInterner.erasedLabel(ada));
                assertThat(names.apply(grace)).isEqualTo("Grace");
            }
            assertThat(staging.entityLabels())
                .as("staging is not erased by a build: that is the compaction's to do")
                .contains("Ada");
            assertThat(staging.stagedCount()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("the labels a build names entities by are staging's, with withheld names erased")
    void labelsAreStagingsWithWithheldNamesErased(@TempDir Path dir) throws IOException {
        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("staging"))) {
            staging.append(ExtractedTriple.alwaysValid("Ada", "knows", "Grace", document(1)));
            int ada = staging.entityIdOf("Ada");

            assertThat(GraphBuilder.entityLabels(staging, entity(ada)))
                .containsExactly(LabelInterner.erasedLabel(ada), "Grace");
            assertThat(GraphBuilder.entityLabels(staging, StagedErasure.NOTHING))
                .containsExactly("Ada", "Grace");
        }
    }

    @Test
    @DisplayName("a build that withholds every fact publishes nothing and leaves no scratch")
    void withholdingEverythingPublishesNothing(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("graph");
        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("staging"))) {
            staging.append(ExtractedTriple.alwaysValid("Ada", "knows", "Grace", document(1)));
            int ada = staging.entityIdOf("Ada");

            GraphBuilder.BuildResult result = GraphBuilder.buildInto(staging, target,
                SupersessionPolicy.NONE, entity(ada));

            assertThat(result.edges()).isZero();
            assertThat(result.withheld()).isEqualTo(1);
            assertThat(GraphBuilder.scratchFor(target))
                .as("an assembly of withheld data is not left lying beside the graph")
                .doesNotExist();
        }
    }

    @Test
    @DisplayName("nothing withheld builds what an unfenced build builds")
    void nothingWithheldIsAnOrdinaryBuild(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("graph");
        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("staging"))) {
            staging.append(ExtractedTriple.alwaysValid("Ada", "knows", "Grace", document(1)));

            GraphBuilder.BuildResult result = GraphBuilder.buildInto(staging, target,
                SupersessionPolicy.NONE, StagedErasure.NOTHING);

            assertThat(result.edges()).isEqualTo(1);
            assertThat(result.withheld()).isZero();
        }
    }

    @Test
    @DisplayName("a refused swap leaves the served graph exactly as it was")
    void aRefusedSwapMovesNothing(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("graph");
        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("staging"))) {
            staging.append(ExtractedTriple.alwaysValid("Ada", "knows", "Grace", document(1)));
            GraphBuilder.build(staging, target);
            staging.append(ExtractedTriple.alwaysValid("Grace", "knows", "Linus", document(2)));
            GraphBuilder.buildInto(staging, target, SupersessionPolicy.NONE,
                StagedErasure.NOTHING);

            AtomicBoolean opened = new AtomicBoolean();
            assertThatThrownBy(() -> GraphBuilder.publish(GraphBuilder.scratchFor(target),
                    target, Durability.DEVICE, published -> {
                        opened.set(true);
                        return null;
                    }, refusing(StagedErasure.NOTHING)))
                .isInstanceOf(PublishRefusedException.class);

            assertThat(opened).isFalse();
            assertThat(GraphBuilder.previousFor(target)).doesNotExist();
            try (MappedEdgeStore store = MappedEdgeStore.open(target)) {
                assertThat(store.edgeCount())
                    .as("the graph that was served before the build still is")
                    .isEqualTo(1);
            }
            assertThat(Files.isDirectory(GraphBuilder.scratchFor(target)))
                .as("the scratch is the caller's to discard")
                .isTrue();
        }
    }

    @Test
    @DisplayName("the unfenced fence withholds nothing and admits every swap")
    void theOpenFence() throws IOException {
        AtomicBoolean swapped = new AtomicBoolean();
        PublishFence.NONE.swap(() -> swapped.set(true));

        assertThat(swapped).isTrue();
        assertThat(PublishFence.NONE.obligations()).isZero();
        assertThat(PublishFence.NONE.withheld()).isSameAs(StagedErasure.NOTHING);
        assertThat(StagedErasure.NOTHING.erasesFact(0, 0, 0, 0, 0, Provenance.UNKNOWN)).isFalse();
        assertThat(StagedErasure.NOTHING.erasesSnippet(0, Provenance.UNKNOWN)).isFalse();
        assertThat(StagedErasure.NOTHING.erasesLabel(0)).isFalse();
    }
}
