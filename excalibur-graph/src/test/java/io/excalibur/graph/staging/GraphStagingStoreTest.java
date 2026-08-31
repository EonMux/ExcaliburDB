package io.excalibur.graph.staging;

import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.store.MappedEdgeStore;
import io.excalibur.graph.temporal.SupersessionPolicy;
import io.excalibur.graph.temporal.TemporalFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where facts wait between graph builds.
 *
 * <p>The edge store is produced whole and cannot be appended to, so this is the only durable record
 * of what ingestion has found. Two properties matter more than anything else it does: an entity's
 * id never changes, because every artifact built beside the store is keyed by it; and a process
 * killed mid-append costs the facts that were in flight and not the ones before them.</p>
 */
@DisplayName("Graph staging")
class GraphStagingStoreTest {

    private static Provenance from(long document) {
        return Provenance.of(SourceType.DOCUMENT, document, 0.9, 1);
    }

    private static ExtractedTriple fact(String subject, String relation, String object) {
        return ExtractedTriple.alwaysValid(subject, relation, object, from(1));
    }

    @Nested
    @DisplayName("identifiers")
    class Identifiers {

        @Test
        @DisplayName("a label gets one id and keeps it")
        void anIdIsAssignedOnce(@TempDir Path tempDir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir)) {
                int first = staging.entityIdOf("Ada Lovelace");

                assertThat(staging.entityIdOf("Ada Lovelace")).isEqualTo(first);
                assertThat(staging.entityIdOf("Charles Babbage")).isNotEqualTo(first);
                assertThat(staging.entityCount()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("ids survive a restart")
        void idsSurviveARestart(@TempDir Path tempDir) throws IOException {
            int ada;
            int knows;
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir)) {
                ada = staging.entityIdOf("Ada Lovelace");
                knows = staging.relationIdOf("knows");
            }

            try (GraphStagingStore reopened = GraphStagingStore.open(tempDir)) {
                assertThat(reopened.entityIdOf("Ada Lovelace")).isEqualTo(ada);
                assertThat(reopened.relationIdOf("knows")).isEqualTo(knows);
            }
        }

        @Test
        @DisplayName("entities and relations are numbered independently")
        void entitiesAndRelationsAreSeparate(@TempDir Path tempDir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir)) {
                staging.append(fact("Ada", "knows", "Babbage"));

                assertThat(staging.entityCount()).isEqualTo(2);
                assertThat(staging.relationCount()).isEqualTo(1);
                assertThat(staging.entityLabels()).containsExactly("Ada", "Babbage");
                assertThat(staging.relationLabels()).containsExactly("knows");
            }
        }

        @Test
        @DisplayName("concurrent interning of one label yields one id")
        void concurrentInterningIsConsistent(@TempDir Path tempDir) throws Exception {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir)) {
                int threads = 8;
                CountDownLatch start = new CountDownLatch(1);
                List<Integer> ids = java.util.Collections.synchronizedList(new ArrayList<>());
                AtomicReference<Throwable> failure = new AtomicReference<>();

                ExecutorService pool = Executors.newFixedThreadPool(threads);
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            ids.add(staging.entityIdOf("Ada Lovelace"));
                        } catch (Exception e) {
                            failure.set(e);
                        }
                    });
                }
                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

                assertThat(failure.get()).isNull();
                // Two threads both missing the map must not both be given an id: the id a record
                // gets is its position in the file, and two records for one label would put the
                // same label at two ids.
                assertThat(ids).hasSize(threads).containsOnly(ids.get(0));
                assertThat(staging.entityCount()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a blank label is refused")
        void aBlankLabelIsRefused(@TempDir Path tempDir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir)) {
                assertThatThrownBy(() -> staging.entityIdOf(" "))
                    .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> staging.entityIdOf(null))
                    .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Nested
    @DisplayName("facts")
    class Facts {

        @Test
        @DisplayName("a staged fact replays with everything it was given")
        void aFactRoundTrips(@TempDir Path tempDir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir)) {
                staging.append(new ExtractedTriple("Ada", "employer", "Acme",
                    100, 200, Provenance.of(SourceType.CONVERSATION, 77, 0.8, 3)));

                List<String> seen = new ArrayList<>();
                staging.forEach((subject, relation, object, validFrom, validTo, provenance) -> {
                    assertThat(validFrom).isEqualTo(100);
                    assertThat(validTo).isEqualTo(200);
                    assertThat(provenance.sourceType()).isEqualTo(SourceType.CONVERSATION);
                    assertThat(provenance.sourceId()).isEqualTo(77);
                    assertThat(provenance.confidence()).isEqualTo(80);
                    assertThat(provenance.extractorVersion()).isEqualTo(3);
                    seen.add(subject + "-" + relation + "->" + object);
                });

                assertThat(seen).hasSize(1);
            }
        }

        @Test
        @DisplayName("an open-ended fact keeps its sentinel")
        void anOpenFactKeepsItsSentinel(@TempDir Path tempDir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir)) {
                staging.append(fact("Ada", "knows", "Babbage"));

                staging.forEach((s, r, o, validFrom, validTo, provenance) ->
                    assertThat(validTo).isEqualTo(TemporalFilter.OPEN));
            }
        }

        @Test
        @DisplayName("facts survive a restart")
        void factsSurviveARestart(@TempDir Path tempDir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir)) {
                staging.append(fact("Ada", "knows", "Babbage"));
                staging.append(fact("Babbage", "designed", "Engine"));
            }

            try (GraphStagingStore reopened = GraphStagingStore.open(tempDir)) {
                assertThat(reopened.stagedCount()).isEqualTo(2);

                List<String> replayed = new ArrayList<>();
                reopened.forEach((s, r, o, from, to, provenance) -> replayed.add(s + "->" + o));
                assertThat(replayed).hasSize(2);
            }
        }

        @Test
        @DisplayName("a store that has been closed refuses further facts")
        void aClosedStoreRefuses(@TempDir Path tempDir) throws IOException {
            GraphStagingStore staging = GraphStagingStore.open(tempDir);
            staging.close();

            assertThatThrownBy(() -> staging.append(fact("Ada", "knows", "Babbage")))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("entity text")
    class EntityText {

        @Test
        @DisplayName("snippets replay against the entity they were recorded for")
        void snippetsRoundTrip(@TempDir Path tempDir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir)) {
                int ada = staging.entityIdOf("Ada Lovelace");
                staging.appendEntityText(ada, "a mathematician");
                staging.appendEntityText(ada, "who wrote Note G");

                List<String> seen = new ArrayList<>();
                long count = staging.forEachEntityText((entityId, snippet) -> {
                    assertThat(entityId).isEqualTo(ada);
                    seen.add(snippet);
                });

                assertThat(count).isEqualTo(2);
                assertThat(seen).containsExactly("a mathematician", "who wrote Note G");
            }
        }

        @Test
        @DisplayName("an over-long snippet is cut without splitting a character")
        void anOverLongSnippetIsTruncated(@TempDir Path tempDir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir)) {
                // Multi-byte throughout, so a naive cut would land inside a character and the
                // snippet would come back with a replacement character in it.
                String long_ = "é".repeat(GraphStagingStore.MAX_SNIPPET_BYTES);
                staging.appendEntityText(staging.entityIdOf("Ada"), long_);

                List<String> seen = new ArrayList<>();
                staging.forEachEntityText((entityId, snippet) -> seen.add(snippet));

                assertThat(seen).hasSize(1);
                assertThat(seen.get(0)).doesNotContain("�");
                assertThat(seen.get(0).getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(GraphStagingStore.MAX_SNIPPET_BYTES);
            }
        }

        @Test
        @DisplayName("a blank snippet is not recorded")
        void aBlankSnippetIsSkipped(@TempDir Path tempDir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir)) {
                staging.appendEntityText(staging.entityIdOf("Ada"), "  ");
                staging.appendEntityText(staging.entityIdOf("Ada"), null);

                assertThat(staging.forEachEntityText((id, snippet) -> { })).isZero();
            }
        }

        @Test
        @DisplayName("a negative entity id is refused")
        void aNegativeEntityIdIsRefused(@TempDir Path tempDir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir)) {
                assertThatThrownBy(() -> staging.appendEntityText(-1, "text"))
                    .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Nested
    @DisplayName("building")
    class Building {

        @Test
        @DisplayName("staged facts become an openable edge store")
        void stagedFactsBecomeAStore(@TempDir Path tempDir) throws IOException {
            Path target = tempDir.resolve("graph");

            try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"))) {
                staging.append(fact("Ada", "knows", "Babbage"));
                staging.append(fact("Babbage", "designed", "Engine"));

                GraphBuilder.BuildResult result = GraphBuilder.build(staging, target);

                assertThat(result.edges()).isEqualTo(2);
                assertThat(result.entities()).isEqualTo(3);
            }

            try (MappedEdgeStore store = MappedEdgeStore.open(target)) {
                assertThat(store.edgeCount()).isEqualTo(2);
                assertThat(store.entityCount()).isEqualTo(3);
            }
        }

        @Test
        @DisplayName("a supersession policy is resolved from relation labels")
        void supersessionIsResolvedByLabel(@TempDir Path tempDir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir)) {
                int employer = staging.relationIdOf("employer");
                int authored = staging.relationIdOf("authored");

                SupersessionPolicy policy =
                    GraphBuilder.singleValued(staging, List.of("Employer"));

                // Matched case-insensitively, because the configuration is written by a person
                // and the extractor lower-cases what it produces.
                assertThat(policy.isSingleValued(employer)).isTrue();
                assertThat(policy.isSingleValued(authored)).isFalse();
            }
        }

        @Test
        @DisplayName("no configured relations supersede nothing")
        void noConfiguredRelationsSupersedeNothing(@TempDir Path tempDir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(tempDir)) {
                int employer = staging.relationIdOf("employer");

                assertThat(GraphBuilder.singleValued(staging, List.of()).isSingleValued(employer))
                    .isFalse();
                assertThat(GraphBuilder.singleValued(staging, null).isSingleValued(employer))
                    .isFalse();
            }
        }

        @Test
        @DisplayName("a build from nothing writes nothing")
        void anEmptyBuildWritesNothing(@TempDir Path tempDir) throws IOException {
            Path target = tempDir.resolve("graph");

            try (GraphStagingStore staging = GraphStagingStore.open(tempDir.resolve("staging"))) {
                GraphBuilder.BuildResult result = GraphBuilder.build(staging, target);

                assertThat(result.edges()).isZero();
                assertThat(Files.exists(target)).isFalse();
            }
        }
    }
}
