package com.eonmux.excalibur.graph.store;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.eonmux.excalibur.graph.erasure.EdgeStoreCompactor;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.temporal.SupersessionPolicy;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roaringbitmap.RoaringBitmap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Supersession over statements that do not say when their value began.
 *
 * <p>That is every statement a production extractor stages from text that gives no date, so this
 * is the case the feature exists for rather than an edge of it. What orders such statements is
 * the day each was made and then the order each was staged in; the tests below pin down that the
 * order decides the intervals, and that a question asked either side of a change gets the value
 * that held there.</p>
 */
@DisplayName("Supersession ordered by when statements were made")
class StatementOrderSupersessionTest {

    private static final int SUBJECT = 0;
    private static final int CEO = 1;

    private static final int ACME = 1;
    private static final int BETA = 2;
    private static final int GAMMA = 3;

    private static final int UNDATED = TemporalFilter.UNDATED;
    private static final int OPEN = TemporalFilter.OPEN;

    private static int day(int year, int month, int dayOfMonth) {
        return Math.toIntExact(LocalDate.of(year, month, dayOfMonth).toEpochDay());
    }

    private static Provenance document(long id) {
        return Provenance.of(SourceType.DOCUMENT, id, 0.9, 1);
    }

    private static EdgeStoreWriter singleValuedCeo() {
        return new EdgeStoreWriter().supersessionPolicy(SupersessionPolicy.forRelations(CEO));
    }

    /** What the subject's CEO edges are at {@code asOf}, as sorted object ids. */
    private static long[] valuesAt(MappedEdgeStore store, TemporalFilter asOf) {
        long[] reached = store.expand(new long[] { SUBJECT }, 1, null, asOf);
        long[] values = Arrays.stream(reached).filter(id -> id != SUBJECT).toArray();
        Arrays.sort(values);
        return values;
    }

    private static long[] valuesOn(MappedEdgeStore store, int day) {
        return valuesAt(store, new TemporalFilter(day));
    }

    /** One stored edge's value and interval. */
    private record Stored(int object, int validFrom, int validTo) {
    }

    private static List<Stored> stored(MappedEdgeStore store) {
        List<Stored> edges = new ArrayList<>();
        store.forEachEdge((subject, relation, object, index) ->
            edges.add(new Stored(object, store.validFrom(index), store.validTo(index))));
        return edges;
    }

    @Test
    @DisplayName("a later document supersedes an earlier one when neither dates its value")
    void laterStatementSupersedesEarlier(@TempDir Path dir) throws IOException {
        int firstDocument = day(2020, 3, 1);
        int secondDocument = day(2023, 7, 1);

        EdgeStoreWriter writer = singleValuedCeo();
        writer.add(SUBJECT, CEO, ACME, UNDATED, OPEN, firstDocument, document(1));
        writer.add(SUBJECT, CEO, BETA, UNDATED, OPEN, secondDocument, document(2));
        writer.build(dir, 3);

        try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
            assertThat(valuesOn(store, day(2010, 1, 1)))
                .as("the first value keeps its unknown start, so the years before it still "
                    + "have an answer")
                .containsExactly(ACME);
            assertThat(valuesOn(store, secondDocument - 1)).containsExactly(ACME);
            assertThat(valuesOn(store, secondDocument))
                .as("from the day the later document was written, only the new value")
                .containsExactly(BETA);
            assertThat(valuesOn(store, day(2026, 1, 1))).containsExactly(BETA);
            assertThat(valuesAt(store, TemporalFilter.ALL))
                .as("the whole history still has both")
                .containsExactly(ACME, BETA);

            assertThat(stored(store)).containsExactlyInAnyOrder(
                new Stored(ACME, UNDATED, secondDocument),
                new Stored(BETA, secondDocument, OPEN));
        }
    }

    @Test
    @DisplayName("the order statements are dated in decides, not the order they were staged in")
    void statedDayOrdersBeforeStagingOrder(@TempDir Path dir) throws IOException {
        // A crawl reads whatever order the filesystem lists; a newer document read first must
        // still be the one that is current.
        EdgeStoreWriter writer = singleValuedCeo();
        writer.add(SUBJECT, CEO, BETA, UNDATED, OPEN, day(2023, 1, 1), document(2));
        writer.add(SUBJECT, CEO, ACME, UNDATED, OPEN, day(2020, 1, 1), document(1));
        writer.build(dir, 3);

        try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
            assertThat(valuesOn(store, day(2021, 1, 1))).containsExactly(ACME);
            assertThat(valuesOn(store, day(2024, 1, 1))).containsExactly(BETA);
        }
    }

    @Test
    @DisplayName("of two sources stated the same day, the later-staged one is current")
    void sameDayTieBrokenByStagingOrder(@TempDir Path dir) throws IOException {
        // A batch ingested in one day with no document dates: every statement is stated the day
        // it was staged, and leaving them all open would return every CEO the corpus ever named.
        int ingested = day(2024, 5, 1);

        EdgeStoreWriter writer = singleValuedCeo();
        writer.add(SUBJECT, CEO, ACME, UNDATED, OPEN, ingested, document(1));
        writer.add(SUBJECT, CEO, BETA, UNDATED, OPEN, ingested, document(2));
        writer.add(SUBJECT, CEO, GAMMA, UNDATED, OPEN, ingested, document(3));
        writer.build(dir, 4);

        try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
            assertThat(valuesOn(store, ingested))
                .as("the last staged of the three is the one current from that day")
                .containsExactly(GAMMA);
            assertThat(valuesOn(store, day(2025, 1, 1))).containsExactly(GAMMA);
            assertThat(valuesOn(store, ingested - 1))
                .as("before it, the first value, which nothing earlier contradicts")
                .containsExactly(ACME);
            assertThat(valuesAt(store, TemporalFilter.ALL)).containsExactly(ACME, BETA, GAMMA);
        }
    }

    @Test
    @DisplayName("the same tie resolves the same way on every rebuild")
    void tieIsDeterministic(@TempDir Path first, @TempDir Path second) throws IOException {
        int ingested = day(2024, 5, 1);
        for (Path dir : List.of(first, second)) {
            EdgeStoreWriter writer = singleValuedCeo();
            writer.add(SUBJECT, CEO, BETA, UNDATED, OPEN, ingested, document(9));
            writer.add(SUBJECT, CEO, ACME, UNDATED, OPEN, ingested, document(4));
            writer.build(dir, 3);
        }

        try (MappedEdgeStore a = MappedEdgeStore.open(first);
             MappedEdgeStore b = MappedEdgeStore.open(second)) {
            assertThat(stored(a)).isEqualTo(stored(b));
            assertThat(valuesOn(a, ingested))
                .as("staging order, not the value's id or the source's id, breaks the tie")
                .containsExactly(ACME);
        }
    }

    @Test
    @DisplayName("stated starts on the same day leave the earlier-staged one in history alone")
    void statedStartTieLeavesAnEmptyInterval(@TempDir Path dir) throws IOException {
        int appointed = day(2022, 1, 1);

        EdgeStoreWriter writer = singleValuedCeo();
        writer.add(SUBJECT, CEO, ACME, appointed, OPEN, day(2022, 2, 1), document(1));
        writer.add(SUBJECT, CEO, BETA, appointed, OPEN, day(2022, 3, 1), document(2));
        writer.build(dir, 3);

        try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
            assertThat(valuesOn(store, appointed))
                .as("superseded the day it began, so no day ever had it")
                .containsExactly(BETA);
            assertThat(valuesOn(store, appointed - 1)).isEmpty();
            assertThat(valuesAt(store, TemporalFilter.ALL))
                .as("the contradicted statement is still part of the history")
                .containsExactly(ACME, BETA);
            assertThat(stored(store)).contains(new Stored(ACME, appointed, appointed));
        }
    }

    @Test
    @DisplayName("one source's same-day values are left ambiguous, then closed together")
    void oneSourceSameDayStaysAmbiguous(@TempDir Path dir) throws IOException {
        // "She ran Acme and Beta": one document, one day, and the order a model listed the two in
        // is no evidence of which came second.
        int written = day(2021, 1, 1);
        int later = day(2023, 1, 1);

        EdgeStoreWriter writer = singleValuedCeo();
        writer.add(SUBJECT, CEO, ACME, UNDATED, OPEN, written, document(1));
        writer.add(SUBJECT, CEO, BETA, UNDATED, OPEN, written, document(1));
        writer.add(SUBJECT, CEO, GAMMA, UNDATED, OPEN, later, document(2));
        writer.build(dir, 4);

        try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
            assertThat(valuesOn(store, day(2022, 1, 1))).containsExactly(ACME, BETA);
            assertThat(valuesOn(store, later)).containsExactly(GAMMA);
        }
    }

    @Test
    @DisplayName("a restatement of the current value corroborates it rather than replacing it")
    void restatementClosesNothing(@TempDir Path dir) throws IOException {
        int first = day(2020, 1, 1);
        int restated = day(2021, 1, 1);
        int changed = day(2022, 1, 1);

        EdgeStoreWriter writer = singleValuedCeo();
        writer.add(SUBJECT, CEO, ACME, UNDATED, OPEN, first, document(1));
        writer.add(SUBJECT, CEO, ACME, UNDATED, OPEN, restated, document(2));
        writer.add(SUBJECT, CEO, BETA, UNDATED, OPEN, changed, document(3));
        writer.build(dir, 3);

        try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
            assertThat(stored(store)).containsExactlyInAnyOrder(
                new Stored(ACME, UNDATED, changed),
                new Stored(ACME, restated, changed),
                new Stored(BETA, changed, OPEN));
            assertThat(valuesOn(store, changed)).containsExactly(BETA);
        }
    }

    @Test
    @DisplayName("a stated start outranks the day another statement was made")
    void statedStartOrdersAgainstStatedDay(@TempDir Path dir) throws IOException {
        // A 2024 profile says Acme's tenure began in 2015; a 2020 article says Beta without
        // saying since when. On the timeline, Acme began first and Beta is known by 2020.
        int acmeBegan = day(2015, 6, 1);
        int betaStated = day(2020, 1, 1);

        EdgeStoreWriter writer = singleValuedCeo();
        writer.add(SUBJECT, CEO, ACME, acmeBegan, OPEN, day(2024, 1, 1), document(1));
        writer.add(SUBJECT, CEO, BETA, UNDATED, OPEN, betaStated, document(2));
        writer.build(dir, 3);

        try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
            assertThat(valuesOn(store, day(2014, 1, 1))).isEmpty();
            assertThat(valuesOn(store, day(2018, 1, 1))).containsExactly(ACME);
            assertThat(valuesOn(store, betaStated)).containsExactly(BETA);
        }
    }

    @Test
    @DisplayName("a statement with an end and no start is history and closes nothing")
    void historyWithoutAStartClosesNothing(@TempDir Path dir) throws IOException {
        // "Acme's CEO until 2019", stated in 2024, says nothing about when Acme began and so
        // cannot be the change that ended anything.
        int acmeEnded = day(2019, 1, 1);
        int betaStated = day(2021, 1, 1);

        EdgeStoreWriter writer = singleValuedCeo();
        writer.add(SUBJECT, CEO, BETA, UNDATED, OPEN, betaStated, document(2));
        writer.add(SUBJECT, CEO, ACME, UNDATED, acmeEnded, day(2024, 1, 1), document(1));
        writer.build(dir, 3);

        try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
            assertThat(stored(store)).containsExactlyInAnyOrder(
                new Stored(ACME, UNDATED, acmeEnded),
                new Stored(BETA, betaStated, OPEN));
            assertThat(valuesOn(store, day(2018, 1, 1)))
                .as("Beta, known from 2021, does not reach back over the ended tenure")
                .containsExactly(ACME);
            assertThat(valuesOn(store, day(2020, 1, 1))).isEmpty();
            assertThat(valuesOn(store, betaStated)).containsExactly(BETA);
        }
    }

    @Test
    @DisplayName("statements with no date at all are ordered by staging alone")
    void undatedStatementsFallBackToStagingOrder(@TempDir Path dir) throws IOException {
        // What a log written before statements were dated replays as.
        EdgeStoreWriter writer = singleValuedCeo();
        writer.add(SUBJECT, CEO, ACME, UNDATED, OPEN, document(1));
        writer.add(SUBJECT, CEO, BETA, UNDATED, OPEN, document(2));
        writer.build(dir, 3);

        try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
            assertThat(valuesOn(store, day(1990, 1, 1))).containsExactly(BETA);
            assertThat(valuesOn(store, day(2030, 1, 1))).containsExactly(BETA);
            assertThat(valuesAt(store, TemporalFilter.ALL)).containsExactly(ACME, BETA);
        }
    }

    @Test
    @DisplayName("a relation not declared single-valued ignores the order statements were made in")
    void multiValuedRelationIgnoresStatementOrder(@TempDir Path dir) throws IOException {
        EdgeStoreWriter writer = new EdgeStoreWriter()
            .supersessionPolicy(SupersessionPolicy.forRelations(CEO + 1));
        writer.add(SUBJECT, CEO, ACME, UNDATED, OPEN, day(2020, 1, 1), document(1));
        writer.add(SUBJECT, CEO, BETA, UNDATED, OPEN, day(2023, 1, 1), document(2));
        writer.build(dir, 3);

        try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
            assertThat(stored(store)).containsExactlyInAnyOrder(
                new Stored(ACME, UNDATED, OPEN),
                new Stored(BETA, UNDATED, OPEN));
        }
    }

    @Test
    @DisplayName("a stated day cannot be the open-interval sentinel")
    void openIsNotADay() {
        EdgeStoreWriter writer = new EdgeStoreWriter();
        assertThatThrownBy(() -> writer.add(SUBJECT, CEO, ACME, UNDATED, OPEN, OPEN, document(1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an empty interval is refused from a caller and carried across from a store")
    void emptyIntervalOnlyAsStored() {
        EdgeStoreWriter writer = new EdgeStoreWriter();
        int day = day(2022, 1, 1);

        assertThatThrownBy(() -> writer.add(SUBJECT, CEO, ACME, day, day, Provenance.UNKNOWN))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.addStored(SUBJECT, CEO, ACME, day, day - 1,
                Provenance.UNKNOWN))
            .as("backwards is wrong however the interval arrived")
            .isInstanceOf(IllegalArgumentException.class);

        writer.addStored(SUBJECT, CEO, ACME, day, day, Provenance.UNKNOWN);
        assertThat(writer.edgeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a store holding a same-day supersession can still be compacted")
    void compactionCarriesEmptyIntervals(@TempDir Path dir) throws IOException {
        int appointed = day(2022, 1, 1);

        EdgeStoreWriter writer = singleValuedCeo();
        writer.add(SUBJECT, CEO, ACME, appointed, OPEN, appointed, document(1));
        writer.add(SUBJECT, CEO, BETA, appointed, OPEN, appointed, document(2));
        writer.add(SUBJECT, CEO + 1, GAMMA, UNDATED, OPEN, appointed, document(3));
        writer.build(dir, 4);

        MappedEdgeStore store = MappedEdgeStore.open(dir);
        RoaringBitmap tombstoned = new RoaringBitmap();
        store.forEachEdge((subject, relation, object, index) -> {
            if (object == GAMMA) {
                tombstoned.add((int) index);
            }
        });
        assertThat(EdgeStoreCompactor.compact(store, dir, tombstoned)).isEqualTo(1);

        try (MappedEdgeStore compacted = MappedEdgeStore.open(dir)) {
            assertThat(stored(compacted)).containsExactlyInAnyOrder(
                new Stored(ACME, appointed, appointed),
                new Stored(BETA, appointed, OPEN));
        }
    }
}
