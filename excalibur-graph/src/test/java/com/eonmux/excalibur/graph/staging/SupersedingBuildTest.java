package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.graph.temporal.SupersessionPolicy;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Staged facts to a served store, for a relation that holds one value at a time.
 *
 * <p>The facts here are shaped the way production stages them: no stated interval, and a day
 * each was stated on - the document's own date, or the day it was staged. Before those days were
 * recorded, every such fact was valid over all time and a single-valued relation ended up holding
 * every value it had ever been given, with no question able to tell them apart.</p>
 */
@DisplayName("Building with supersession")
class SupersedingBuildTest {

    private static int day(int year, int month, int dayOfMonth) {
        return Math.toIntExact(LocalDate.of(year, month, dayOfMonth).toEpochDay());
    }

    private static Clock clockOn(int epochDay) {
        return Clock.fixed(Instant.ofEpochSecond(epochDay * 86_400L + 3_600), ZoneOffset.UTC);
    }

    private static ExtractedTriple ceo(String person, long document) {
        return ExtractedTriple.alwaysValid("Acme", "ceo", person,
            Provenance.of(SourceType.DOCUMENT, document, 0.9, 1));
    }

    /** Who is Acme's CEO at {@code asOf}, by label. */
    private static List<String> ceosAt(MappedEdgeStore store, GraphStagingStore staging,
                                       TemporalFilter asOf) throws IOException {
        List<String> labels = staging.entityLabels();
        int acme = staging.entityIdOf("Acme");
        List<String> found = new ArrayList<>();
        for (long id : store.expand(new long[] { acme }, 1, null, asOf)) {
            if (id != acme) {
                found.add(labels.get((int) id));
            }
        }
        found.sort(String::compareTo);
        return found;
    }

    private static SupersessionPolicy ceoIsSingleValued(GraphStagingStore staging) {
        return GraphBuilder.singleValued(staging, List.of("ceo"));
    }

    @Test
    @DisplayName("a rebuild closes the value a later document replaced")
    void rebuildClosesSupersededValue(@TempDir Path dir) throws IOException {
        int earlier = day(2019, 4, 1);
        int later = day(2023, 9, 1);
        Path target = dir.resolve("graph");

        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("staging"),
                clockOn(day(2026, 1, 1)))) {
            staging.append(ceo("Ada", 1).withStatedOn(earlier));
            staging.append(ceo("Grace", 2).withStatedOn(later));
            GraphBuilder.build(staging, target, ceoIsSingleValued(staging));

            try (MappedEdgeStore store = MappedEdgeStore.open(target)) {
                assertThat(ceosAt(store, staging, TemporalFilter.asOf(LocalDate.of(2021, 1, 1))))
                    .as("as of a date before the change")
                    .containsExactly("Ada");
                assertThat(ceosAt(store, staging, TemporalFilter.asOf(LocalDate.of(2024, 1, 1))))
                    .as("as of a date after it")
                    .containsExactly("Grace");
                assertThat(ceosAt(store, staging, TemporalFilter.ALL))
                    .containsExactly("Ada", "Grace");
            }
        }
    }

    @Test
    @DisplayName("without a policy, the same facts are left exactly as staged")
    void withoutPolicyNothingIsClosed(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("graph");
        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("staging"))) {
            staging.append(ceo("Ada", 1).withStatedOn(day(2019, 4, 1)));
            staging.append(ceo("Grace", 2).withStatedOn(day(2023, 9, 1)));
            GraphBuilder.build(staging, target);

            try (MappedEdgeStore store = MappedEdgeStore.open(target)) {
                assertThat(ceosAt(store, staging, TemporalFilter.asOf(LocalDate.of(2024, 1, 1))))
                    .containsExactly("Ada", "Grace");
            }
        }
    }

    @Test
    @DisplayName("facts staged on different days with no document date are ordered by those days")
    void stagingDayOrdersUndatedDocuments(@TempDir Path dir) throws IOException {
        Path stagingDir = dir.resolve("staging");
        Path target = dir.resolve("graph");
        int monday = day(2025, 6, 2);
        int friday = day(2025, 6, 6);

        try (GraphStagingStore staging = GraphStagingStore.open(stagingDir, clockOn(monday))) {
            staging.append(ceo("Ada", 1));
        }
        try (GraphStagingStore staging = GraphStagingStore.open(stagingDir, clockOn(friday))) {
            staging.append(ceo("Grace", 2));
            GraphBuilder.build(staging, target, ceoIsSingleValued(staging));

            try (MappedEdgeStore store = MappedEdgeStore.open(target)) {
                assertThat(ceosAt(store, staging, new TemporalFilter(friday - 1)))
                    .containsExactly("Ada");
                assertThat(ceosAt(store, staging, new TemporalFilter(friday)))
                    .containsExactly("Grace");
            }
        }
    }

    @Test
    @DisplayName("documents of one day are resolved by the order they were staged in")
    void sameDayDocumentsResolveByStagingOrder(@TempDir Path dir) throws IOException {
        int ingested = day(2025, 6, 2);
        Path target = dir.resolve("graph");

        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("staging"),
                clockOn(ingested))) {
            staging.append(ceo("Ada", 1));
            staging.append(ceo("Grace", 2));
            GraphBuilder.build(staging, target, ceoIsSingleValued(staging));

            try (MappedEdgeStore store = MappedEdgeStore.open(target)) {
                assertThat(ceosAt(store, staging, TemporalFilter.now()))
                    .as("one current value, the later-staged one")
                    .containsExactly("Grace");
                assertThat(ceosAt(store, staging, new TemporalFilter(ingested - 1)))
                    .containsExactly("Ada");
            }
        }
    }

    @Test
    @DisplayName("erasing the later value and rebuilding leaves the earlier one current again")
    void erasingTheSuccessorReopensThePredecessor(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("graph");
        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("staging"))) {
            staging.append(ceo("Ada", 1).withStatedOn(day(2019, 4, 1)));
            staging.append(ceo("Grace", 2).withStatedOn(day(2023, 9, 1)));
            GraphBuilder.build(staging, target, ceoIsSingleValued(staging));

            staging.erase(new StagedErasure() {
                @Override
                public boolean erasesFact(int subject, int relation, int object, int validFrom,
                                          int validTo, Provenance provenance) {
                    return provenance.sourceId() == 2;
                }

                @Override
                public boolean erasesSnippet(int entityId, Provenance provenance) {
                    return false;
                }

                @Override
                public boolean erasesLabel(int entityId) {
                    return false;
                }
            });
            GraphBuilder.build(staging, target, ceoIsSingleValued(staging));

            try (MappedEdgeStore store = MappedEdgeStore.open(target)) {
                assertThat(ceosAt(store, staging, TemporalFilter.now()))
                    .as("the closure was the builder's inference, and goes with its evidence")
                    .containsExactly("Ada");
            }
        }
    }

    @Test
    @DisplayName("an undated fact is recorded as stated on the day it was staged")
    void undatedFactTakesTheStagingDay(@TempDir Path dir) throws IOException {
        int today = day(2025, 6, 2);
        try (GraphStagingStore staging = GraphStagingStore.open(dir, clockOn(today))) {
            staging.append(ceo("Ada", 1));
            staging.append(ceo("Grace", 2).withStatedOn(day(2020, 1, 1)));
            staging.append(ceo("Hedy", 3).withStatedOn(today + 30));

            List<Integer> stated = new ArrayList<>();
            staging.forEachStaged((s, r, o, from, to, statedOn, provenance) -> {
                assertThat(from).isEqualTo(TemporalFilter.UNDATED);
                assertThat(to).isEqualTo(TemporalFilter.OPEN);
                stated.add(statedOn);
            });
            assertThat(stated)
                .as("the staging day, the document's own day, and a future day held to today")
                .containsExactly(today, day(2020, 1, 1), today);
        }
    }

    @Test
    @DisplayName("the replay without stated days still sees every fact")
    void plainReplayStillWorks(@TempDir Path dir) throws IOException {
        try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
            staging.append(ceo("Ada", 1).withStatedOn(day(2020, 1, 1)));
            List<Long> sources = new ArrayList<>();
            assertThat(staging.forEach((s, r, o, from, to, provenance) ->
                sources.add(provenance.sourceId()))).isEqualTo(1);
            assertThat(sources).containsExactly(1L);
        }
    }

    @Test
    @DisplayName("a triple's stated day is a day or undated, never the open sentinel")
    void statedDayIsValidated() {
        ExtractedTriple fact = ceo("Ada", 1);
        assertThat(fact.isDated()).isFalse();
        assertThat(fact.withStatedOn(day(2020, 1, 1)).isDated()).isTrue();
        assertThat(fact.withStatedOn(day(2020, 1, 1)))
            .as("a copy; the original is unchanged")
            .isNotSameAs(fact);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> fact.withStatedOn(TemporalFilter.OPEN))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
