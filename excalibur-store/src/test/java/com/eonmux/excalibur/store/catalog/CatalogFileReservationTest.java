package com.eonmux.excalibur.store.catalog;

import com.eonmux.excalibur.store.LifecycleState;
import com.eonmux.excalibur.store.SegmentCatalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rows reserved for a registration that cannot be undone once it starts.
 *
 * <p>A split moves its children's files into place before it writes their rows. If another
 * registration could take the last row in between, the split would be left holding moved files
 * and nowhere to record them. So the split reserves its rows first, and a reserved row belongs to
 * that segment id until it is written or released.</p>
 *
 * <p>Filling the file with real rows would take tens of thousands of forced writes, so these
 * tests fill it with reservations for ids that are never written: to every other registration
 * a reserved row is as taken as a written one.</p>
 */
@DisplayName("Catalog row reservations")
class CatalogFileReservationTest {

    /** Ids no test writes, used to hold the catalog's spare rows. */
    private static final long FILLER_BASE = 1_000_000L;

    @TempDir
    Path tempDir;

    private SegmentCatalog.SegmentMetadata live(long id) {
        SegmentCatalog.SegmentMetadata metadata = new SegmentCatalog.SegmentMetadata(id);
        metadata.setVolumePath(tempDir.resolve("segment_" + id + ".dat"));
        metadata.setEntryState(SegmentCatalog.ENTRY_STATE_ACTIVE);
        metadata.restoreLifecycleState(LifecycleState.ACTIVE);
        return metadata;
    }

    /** Retires a segment the way the engine does: tombstone, purge, persist the purged row. */
    private static void purge(CatalogFile catalog, long id) throws IOException {
        SegmentCatalog.SegmentMetadata metadata = catalog.readSegment(id);
        metadata.transitionToTombstoned();
        metadata.transitionToPurged();
        catalog.writeSegment(metadata);
    }

    private static long[] fillers(int count) {
        return LongStream.range(FILLER_BASE, FILLER_BASE + count).toArray();
    }

    /** Reserves every row the catalog has left but {@code spare}. */
    private static void fillLeaving(CatalogFile catalog, int spare) {
        long[] fillers = fillers(CatalogFile.MAX_SEGMENTS - catalog.getSegmentCount() - spare);
        assertThat(catalog.reserveRows(fillers)).isTrue();
    }

    @Test
    @DisplayName("a row reserved for one segment is refused to every other")
    void aReservedRowIsHeld() throws IOException {
        try (CatalogFile catalog = new CatalogFile(tempDir.resolve("held.dat"))) {
            catalog.writeSegment(live(1L));
            fillLeaving(catalog, 1);
            assertThat(catalog.reserveRows(2L)).isTrue();

            assertThat(catalog.hasRoomFor(3L)).isFalse();
            assertThatThrownBy(() -> catalog.writeSegment(live(3L)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("none free")
                .hasMessageContaining("reserved");
            assertThat(catalog.readSegment(3L)).isNull();

            catalog.writeSegment(live(2L));
            assertThat(catalog.readSegment(2L)).isNotNull();
            assertThat(catalog.getSegmentCount()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("a reservation is all or nothing")
    void aReservationIsAllOrNothing() throws IOException {
        try (CatalogFile catalog = new CatalogFile(tempDir.resolve("all.dat"))) {
            fillLeaving(catalog, 1);

            assertThat(catalog.hasRoomFor(2L, 3L)).isFalse();
            assertThat(catalog.reserveRows(2L, 3L)).isFalse();
            assertThat(catalog.reserveRows(4L))
                .as("the refused pair reserved nothing, so the last row is still free")
                .isTrue();
        }
    }

    @Test
    @DisplayName("a segment that already has a live row needs no new one")
    void aLiveRowNeedsNoReservation() throws IOException {
        try (CatalogFile catalog = new CatalogFile(tempDir.resolve("live.dat"))) {
            catalog.writeSegment(live(1L));
            fillLeaving(catalog, 0);

            assertThat(catalog.hasRoomFor(1L)).isTrue();
            assertThat(catalog.reserveRows(1L)).isTrue();
            catalog.writeSegment(live(1L));
            assertThat(catalog.hasRoomFor(2L)).isFalse();
        }
    }

    @Test
    @DisplayName("a released row is free again")
    void aReleasedRowIsFreeAgain() throws IOException {
        try (CatalogFile catalog = new CatalogFile(tempDir.resolve("released.dat"))) {
            fillLeaving(catalog, 0);
            assertThat(catalog.hasRoomFor(1L)).isFalse();

            catalog.releaseRows(FILLER_BASE, 42L);

            assertThat(catalog.hasRoomFor(1L)).isTrue();
            catalog.writeSegment(live(1L));
            assertThat(catalog.readSegment(1L)).isNotNull();
        }
    }

    @Test
    @DisplayName("a purged row counts as free, and a reserved segment is written into it")
    void aPurgedRowIsFreeForAReservation() throws IOException {
        try (CatalogFile catalog = new CatalogFile(tempDir.resolve("purged.dat"))) {
            catalog.writeSegment(live(1L));
            catalog.writeSegment(live(2L));
            purge(catalog, 1L);
            fillLeaving(catalog, 0);

            assertThat(catalog.reserveRows(3L))
                .as("the purged row is the one free row left")
                .isTrue();
            assertThatThrownBy(() -> catalog.writeSegment(live(4L)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("none free");

            catalog.writeSegment(live(3L));
            assertThat(catalog.getSegmentCount()).isEqualTo(2);
            assertThat(catalog.readSegment(3L)).isNotNull();
        }
    }

    @Test
    @DisplayName("writing a live segment over its own purged row takes a free row too")
    void rewritingAPurgedRowTakesAFreeRow() throws IOException {
        try (CatalogFile catalog = new CatalogFile(tempDir.resolve("own.dat"))) {
            catalog.writeSegment(live(1L));
            catalog.writeSegment(live(2L));
            purge(catalog, 1L);
            fillLeaving(catalog, 0);
            assertThat(catalog.reserveRows(3L)).isTrue();

            assertThat(catalog.hasRoomFor(1L)).isFalse();
            assertThatThrownBy(() -> catalog.writeSegment(live(1L)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("none free");

            catalog.releaseRows(3L);
            catalog.writeSegment(live(1L));
            assertThat(catalog.readSegment(1L).getLifecycleState())
                .isEqualTo(LifecycleState.ACTIVE);
        }
    }

    @Test
    @DisplayName("with nothing reserved, the catalog is full at MAX_SEGMENTS live rows")
    void theLimitIsLiveRows() throws IOException {
        try (CatalogFile catalog = new CatalogFile(tempDir.resolve("limit.dat"))) {
            catalog.writeSegment(live(1L));
            assertThat(catalog.hasRoomFor(LongStream.range(10, 10 + CatalogFile.MAX_SEGMENTS - 1)
                .toArray())).isTrue();
            assertThat(catalog.hasRoomFor(LongStream.range(10, 10 + CatalogFile.MAX_SEGMENTS)
                .toArray())).isFalse();
        }
    }
}
