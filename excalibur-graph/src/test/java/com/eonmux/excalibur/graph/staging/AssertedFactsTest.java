package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.erasure.ScopedStagedErasure;
import com.eonmux.excalibur.graph.file.LogCorruptionException;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Facts a person states through the API, staged beside the ones extraction found.
 *
 * <p>Each assertion is a source of its own, named by the fact id it is given, so that it can be
 * retracted alone; the properties that matter are that an id is never given to a second fact,
 * and that an assertion is otherwise an ordinary staged fact every erasure reaches.</p>
 */
@DisplayName("Asserted facts")
class AssertedFactsTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static long microsOf(Instant instant) {
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }

    @Nested
    @DisplayName("staging")
    class Staging {

        @Test
        @DisplayName("an assertion is staged as a user-asserted fact whose source is its id")
        void stagedWithItsOwnSource(@TempDir Path dir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(dir, clockAt(NOW))) {
                long id = staging.assertFacts(List.of(new AssertedFact(
                    "Ada Lovelace", "Worked With", "Charles Babbage", 100, 200, 80))).get(0);

                List<Provenance> seen = new ArrayList<>();
                List<int[]> intervals = new ArrayList<>();
                staging.forEach((subject, relation, object, validFrom, validTo, provenance) -> {
                    seen.add(provenance);
                    intervals.add(new int[] {validFrom, validTo});
                });

                assertThat(seen).containsExactly(new Provenance(SourceType.USER_ASSERTED, id, 80,
                    GraphStagingStore.ASSERTED_EXTRACTOR_VERSION));
                assertThat(intervals.get(0)).containsExactly(100, 200);
                assertThat(staging.relationLabels())
                    .as("cleaned as the extractor cleans a relation")
                    .containsExactly("worked_with");
                assertThat(staging.findRelation("worked_with")).isZero();
            }
        }

        @Test
        @DisplayName("ids are distinct and rise, starting from the time they are given")
        void idsRise(@TempDir Path dir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(dir, clockAt(NOW))) {
                List<Long> ids = staging.assertFacts(List.of(
                    AssertedFact.always("A", "knows", "B"),
                    AssertedFact.always("B", "knows", "C"),
                    AssertedFact.always("A", "knows", "B")));

                assertThat(ids).containsExactly(microsOf(NOW), microsOf(NOW) + 1,
                    microsOf(NOW) + 2);
                assertThat(staging.assertions()).extracting(StagedAssertion::factId)
                    .containsExactlyElementsOf(ids);
            }
        }

        @Test
        @DisplayName("ids keep rising across a restart even when the clock has gone back")
        void idsSurviveARestartAndAClockThatWentBack(@TempDir Path dir) throws IOException {
            long first;
            try (GraphStagingStore staging = GraphStagingStore.open(dir, clockAt(NOW))) {
                first = staging.assertFacts(List.of(AssertedFact.always("A", "knows", "B")))
                    .get(0);
            }
            try (GraphStagingStore reopened =
                     GraphStagingStore.open(dir, clockAt(NOW.minusSeconds(3_600)))) {
                long next = reopened.assertFacts(List.of(AssertedFact.always("A", "knows", "C")))
                    .get(0);
                assertThat(next).isEqualTo(first + 1);
            }
        }

        @Test
        @DisplayName("only assertions are listed, not extracted facts beside them")
        void onlyAssertionsAreListed(@TempDir Path dir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(dir, clockAt(NOW))) {
                staging.append(ExtractedTriple.alwaysValid("A", "knows", "B",
                    Provenance.of(SourceType.DOCUMENT, 7, 0.9, 1)));
                long id = staging.assertFacts(List.of(
                    new AssertedFact("A", "knows", "C", TemporalFilter.UNDATED,
                        TemporalFilter.OPEN, 55))).get(0);

                assertThat(staging.assertions()).containsExactly(new StagedAssertion(id,
                    staging.findEntity("A"), staging.findRelation("knows"),
                    staging.findEntity("C"), TemporalFilter.UNDATED, TemporalFilter.OPEN, 55));
            }
        }

        @Test
        @DisplayName("a store with nothing staged has no assertions")
        void emptyStore(@TempDir Path dir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                assertThat(staging.assertions()).isEmpty();
                assertThat(staging.assertFacts(List.of())).isEmpty();
            }
        }

        @Test
        @DisplayName("looking a label up does not give it an id")
        void findDoesNotIntern(@TempDir Path dir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                assertThat(staging.findEntity("Nobody")).isEqualTo(-1);
                assertThat(staging.findRelation("nothing")).isEqualTo(-1);
                assertThat(staging.findEntity(null)).isEqualTo(-1);
                assertThat(staging.entityCount()).isZero();
                assertThat(staging.relationCount()).isZero();
            }
        }
    }

    @Nested
    @DisplayName("retraction and erasure")
    class Retraction {

        @Test
        @DisplayName("a source erasure naming a fact id removes that assertion and nothing else")
        void retractingOneFact(@TempDir Path dir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(dir, clockAt(NOW))) {
                staging.append(ExtractedTriple.alwaysValid("A", "knows", "B",
                    Provenance.of(SourceType.DOCUMENT, 0, 0.9, 1)));
                List<Long> ids = staging.assertFacts(List.of(
                    AssertedFact.always("A", "knows", "B"),
                    AssertedFact.always("A", "knows", "C")));

                GraphStagingStore.EraseResult result = staging.erase(ScopedStagedErasure.of(
                    new ErasureScope.Source(SourceType.USER_ASSERTED, ids.get(0))));

                assertThat(result.factsRemoved()).isEqualTo(1);
                assertThat(staging.assertions()).extracting(StagedAssertion::factId)
                    .containsExactly(ids.get(1));
                assertThat(staging.stagedCount()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("an entity erasure removes asserted facts about the entity as well")
        void entityErasureCoversAssertions(@TempDir Path dir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(dir, clockAt(NOW))) {
                staging.assertFacts(List.of(
                    AssertedFact.always("Ada", "knows", "Babbage"),
                    AssertedFact.always("Babbage", "knows", "Ada"),
                    AssertedFact.always("Babbage", "knows", "Menabrea")));
                int ada = staging.findEntity("Ada");

                staging.erase(ScopedStagedErasure.of(new ErasureScope.Entity(ada)));

                assertThat(staging.assertions()).hasSize(1);
                assertThat(staging.findEntity("Ada")).isEqualTo(-1);
                assertThat(staging.entityLabels().get(ada))
                    .isEqualTo(LabelInterner.erasedLabel(ada));
            }
        }

        @Test
        @DisplayName("an id is not handed out again after its fact was erased, the store "
            + "restarted and the clock set back")
        void erasedIdsStaySpent(@TempDir Path dir) throws IOException {
            long newest;
            try (GraphStagingStore staging = GraphStagingStore.open(dir, clockAt(NOW))) {
                staging.assertFacts(List.of(AssertedFact.always("A", "knows", "B")));
                newest = staging.assertFacts(List.of(AssertedFact.always("A", "knows", "C")))
                    .get(0);
                staging.erase(ScopedStagedErasure.of(
                    new ErasureScope.Source(SourceType.USER_ASSERTED, newest)));
                assertThat(staging.assertions()).extracting(StagedAssertion::factId)
                    .doesNotContain(newest);
            }
            try (GraphStagingStore reopened =
                     GraphStagingStore.open(dir, clockAt(NOW.minusSeconds(3_600)))) {
                assertThat(reopened.assertFacts(List.of(AssertedFact.always("A", "knows", "D"))))
                    .containsExactly(newest + 1);
            }
        }
    }

    @Nested
    @DisplayName("the record of the last id")
    class Floor {

        @Test
        @DisplayName("is absent until an id is handed out, and then holds the last one")
        void recordsTheLastId(@TempDir Path dir) throws IOException {
            Path file = dir.resolve(FactIdFloor.FILE_NAME);
            assertThat(FactIdFloor.read(file)).isZero();
            try (GraphStagingStore staging = GraphStagingStore.open(dir, clockAt(NOW))) {
                List<Long> ids = staging.assertFacts(List.of(
                    AssertedFact.always("A", "knows", "B"), AssertedFact.always("B", "knows", "C")));
                assertThat(FactIdFloor.read(file)).isEqualTo(ids.get(1));
            }
            assertThat(dir.resolve(FactIdFloor.REWRITE_NAME)).doesNotExist();
        }

        @Test
        @DisplayName("a damaged record refuses assertions rather than risk reusing an id")
        void damageIsRefused(@TempDir Path dir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(dir, clockAt(NOW))) {
                staging.assertFacts(List.of(AssertedFact.always("A", "knows", "B")));
            }
            Path file = dir.resolve(FactIdFloor.FILE_NAME);
            byte[] bytes = Files.readAllBytes(file);
            bytes[6] ^= 0x01;
            Files.write(file, bytes);

            assertThatThrownBy(() -> FactIdFloor.read(file))
                .isInstanceOf(LogCorruptionException.class)
                .hasMessageContaining("checksum");
            try (GraphStagingStore staging = GraphStagingStore.open(dir, clockAt(NOW))) {
                assertThatThrownBy(() -> staging.assertFacts(
                        List.of(AssertedFact.always("A", "knows", "C"))))
                    .isInstanceOf(LogCorruptionException.class);
                assertThat(staging.assertions()).hasSize(1);
            }

            Files.write(file, new byte[3]);
            assertThatThrownBy(() -> FactIdFloor.read(file)).hasMessageContaining("bytes long");
            Files.write(file, new byte[16]);
            assertThatThrownBy(() -> FactIdFloor.read(file)).hasMessageContaining("marker");
        }

        @Test
        @DisplayName("a write a crash cut short is removed when the store next opens")
        void unfinishedWriteIsRemoved(@TempDir Path dir) throws IOException {
            Files.write(dir.resolve(FactIdFloor.REWRITE_NAME), new byte[5]);

            GraphStagingStore.open(dir).close();

            assertThat(dir.resolve(FactIdFloor.REWRITE_NAME)).doesNotExist();
        }
    }

    @Nested
    @DisplayName("what an assertion may say")
    class Validation {

        @Test
        @DisplayName("labels are cleaned as extracted ones are")
        void labelsAreCleaned() {
            AssertedFact fact = new AssertedFact("  Ada  ", " Born In ", "London ", 1, 2, 100);

            assertThat(fact.subject()).isEqualTo("Ada");
            assertThat(fact.relation()).isEqualTo("born_in");
            assertThat(fact.object()).isEqualTo("London");
        }

        @Test
        @DisplayName("a blank, missing or overlong label is refused")
        void badLabelsAreRefused() {
            String tooLong = "x".repeat(FactLabels.MAX_LABEL_CHARS + 1);
            assertThatThrownBy(() -> AssertedFact.always(" ", "knows", "B"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("subject");
            assertThatThrownBy(() -> AssertedFact.always("A", null, "B"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("relation");
            assertThatThrownBy(() -> AssertedFact.always("A", "knows", tooLong))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("object");
            assertThat(AssertedFact.always("x".repeat(FactLabels.MAX_LABEL_CHARS), "knows", "B")
                .subject()).hasSize(FactLabels.MAX_LABEL_CHARS);
        }

        @Test
        @DisplayName("an erasure's placeholder cannot be used as a name")
        void erasedPlaceholderIsRefused() {
            assertThatThrownBy(() -> AssertedFact.always(LabelInterner.erasedLabel(3), "knows",
                "B")).isInstanceOf(IllegalArgumentException.class);
            assertThat(FactLabels.entity("(erased 12)")).isNull();
            assertThat(FactLabels.entity("(erased)")).isEqualTo("(erased)");
            assertThat(FactLabels.entity("(erased x1)")).isEqualTo("(erased x1)");
        }

        @Test
        @DisplayName("an empty or reversed interval and an out-of-range confidence are refused")
        void intervalAndConfidence() {
            assertThatThrownBy(() -> new AssertedFact("A", "r", "B", 5, 5, 50))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AssertedFact("A", "r", "B", 6, 5, 50))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AssertedFact("A", "r", "B", TemporalFilter.OPEN,
                TemporalFilter.OPEN, 50)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AssertedFact("A", "r", "B", TemporalFilter.UNDATED,
                TemporalFilter.UNDATED, 50)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AssertedFact("A", "r", "B", 1, 2, 101))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AssertedFact("A", "r", "B", 1, 2, -1))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the erased-label shape is recognised exactly")
        void erasedLabelShape() {
            assertThat(LabelInterner.isErasedLabel(LabelInterner.erasedLabel(0))).isTrue();
            assertThat(LabelInterner.isErasedLabel("(erased 42)")).isTrue();
            assertThat(LabelInterner.isErasedLabel("(erased )")).isFalse();
            assertThat(LabelInterner.isErasedLabel("(erased 4a)")).isFalse();
            assertThat(LabelInterner.isErasedLabel("erased 4")).isFalse();
            assertThat(LabelInterner.isErasedLabel("(erased 4")).isFalse();
            assertThat(LabelInterner.isErasedLabel(null)).isFalse();
        }
    }
}
