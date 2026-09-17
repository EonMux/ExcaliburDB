package io.excalibur.store.catalog;

import io.excalibur.store.LifecycleState;
import io.excalibur.store.SegmentCatalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A purged segment has to give its catalog row back.
 *
 * <p>Rows are only ever appended, and the file refuses to grow past {@code MAX_SEGMENTS}. If a
 * purge merely marks its row and the next registration appends a new one, the ceiling counts
 * every segment the store has ever had rather than the ones it has now. A store that splits and
 * purges - which is what a long-lived store does - therefore walks into a wall it can never come
 * back from, and it surfaces as a split that fails for no reason an operator can see.</p>
 *
 * <p>Reuse is safe because nothing addresses a row by its position: lookups scan for a segment
 * id, and recovery reads the rows as a set. A purged row is the one row in the file that no
 * longer names anything on disk.</p>
 */
@DisplayName("Catalog slot reuse after a purge")
class CatalogFileSlotReuseTest {

    @TempDir
    Path tempDir;

    private SegmentCatalog.SegmentMetadata live(long id) {
        SegmentCatalog.SegmentMetadata metadata = new SegmentCatalog.SegmentMetadata(id);
        metadata.setVolumePath(tempDir.resolve("segment_" + id + ".dat"));
        metadata.setEntryCount((int) id);
        metadata.setEntryState(SegmentCatalog.ENTRY_STATE_ACTIVE);
        metadata.restoreLifecycleState(LifecycleState.ACTIVE);
        return metadata;
    }

    /**
     * Retires a segment the way the engine does: tombstone, purge, persist the purged row.
     */
    private void purge(CatalogFile catalog, long id) throws IOException {
        SegmentCatalog.SegmentMetadata metadata = catalog.readSegment(id);
        assertThat(metadata).isNotNull();
        metadata.transitionToTombstoned();
        metadata.transitionToPurged();
        catalog.writeSegment(metadata);
    }

    @Test
    @DisplayName("a registration after a purge reuses the purged row instead of adding one")
    void aPurgedRowIsReused() throws IOException {
        try (CatalogFile catalog = new CatalogFile(tempDir.resolve("catalog.dat"))) {
            catalog.writeSegment(live(1L));
            catalog.writeSegment(live(2L));
            assertThat(catalog.getSegmentCount()).isEqualTo(2);

            purge(catalog, 1L);
            catalog.writeSegment(live(3L));

            assertThat(catalog.getSegmentCount())
                .as("the ceiling has to bound live segments, not every segment there has "
                    + "ever been")
                .isEqualTo(2);

            List<SegmentCatalog.SegmentMetadata> rows = catalog.readAll();
            assertThat(rows).extracting(SegmentCatalog.SegmentMetadata::getSegmentId)
                .containsExactlyInAnyOrder(2L, 3L);
            assertThat(catalog.readSegment(1L))
                .as("the purged row is gone, not shadowed by the row that replaced it")
                .isNull();
            assertThat(catalog.readSegment(3L)).isNotNull();
            assertThat(catalog.readSegment(3L).getLifecycleState())
                .isEqualTo(LifecycleState.ACTIVE);
        }
    }

    @Test
    @DisplayName("reuse survives a reopen, and the live rows come back intact")
    void reuseSurvivesAReopen() throws IOException {
        Path catalogPath = tempDir.resolve("reopened.dat");
        try (CatalogFile catalog = new CatalogFile(catalogPath)) {
            catalog.writeSegment(live(10L));
            catalog.writeSegment(live(11L));
            purge(catalog, 10L);
            catalog.writeSegment(live(12L));
        }

        try (CatalogFile reopened = new CatalogFile(catalogPath)) {
            assertThat(reopened.getSegmentCount()).isEqualTo(2);
            assertThat(reopened.readAll())
                .extracting(SegmentCatalog.SegmentMetadata::getSegmentId)
                .containsExactlyInAnyOrder(11L, 12L);
        }
    }

    @Test
    @DisplayName("with no purged row to reuse the catalog still grows")
    void withoutAPurgedRowTheCatalogStillGrows() throws IOException {
        try (CatalogFile catalog = new CatalogFile(tempDir.resolve("growing.dat"))) {
            for (long id = 1; id <= 5; id++) {
                catalog.writeSegment(live(id));
            }
            assertThat(catalog.getSegmentCount()).isEqualTo(5);
            assertThat(catalog.readAll()).hasSize(5);
        }
    }
}
