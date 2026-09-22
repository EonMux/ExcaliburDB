package com.eonmux.excalibur.store;

import com.eonmux.excalibur.store.catalog.CatalogFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That the ids the catalog allocates can never be ids the routing directory publishes at.
 *
 * <p>{@link SegmentCatalog#add} and {@link SegmentCatalog#registerFile} take routing indices
 * the storage engine chooses; {@link SegmentCatalog#create} allocates ids for segments that
 * belong to a source. A split publishes its right child at an index the directory has never
 * used before, so if {@code create()} could have handed that index out already, the child
 * would be registered over another source's record - frequently a TOMBSTONED one - and
 * disappear from every query while still accepting writes.</p>
 */
@DisplayName("Catalog id spaces")
class CatalogIdSpacesTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("created ids start above the largest index a routing directory can hold")
    void createdIdsNeverReachTheRoutingRange() {
        SegmentCatalog catalog = new SegmentCatalog();
        catalog.add(0L, tempDir.resolve("segment_0.dat"));
        catalog.add(63L, tempDir.resolve("segment_63.dat"));

        long first = catalog.create("source-a", tempDir.resolve("rolling-a.dat")).getSegmentId();

        // A split registering the next indices up, including the largest an int-wide
        // directory can reach, does not move the created counter into its path.
        catalog.add(64L, tempDir.resolve("segment_64.dat"));
        catalog.registerFile(Integer.MAX_VALUE, tempDir.resolve("segment_max.dat"));
        long second = catalog.create("source-b", tempDir.resolve("rolling-b.dat")).getSegmentId();

        assertThat(SegmentCatalog.FIRST_CREATED_SEGMENT_ID).isGreaterThan(Integer.MAX_VALUE);
        assertThat(first).isEqualTo(SegmentCatalog.FIRST_CREATED_SEGMENT_ID);
        assertThat(second).isEqualTo(first + 1);
        assertThat(catalog.get(64L).getSourceId())
            .as("the routing index kept its own record")
            .isNull();
    }

    @Test
    @DisplayName("an id outside the routing range is refused as a routing index")
    void routingRegistrationRejectsIdsOutsideTheRoutingRange() {
        SegmentCatalog catalog = new SegmentCatalog();
        long created = catalog.create("source-a", tempDir.resolve("rolling.dat")).getSegmentId();
        Path live = tempDir.resolve("segment_live.dat");

        assertThatThrownBy(() -> catalog.add(created, live))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not a routing index");
        assertThatThrownBy(() -> catalog.registerFile(created, live))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.add(-1L, live))
            .isInstanceOf(IllegalArgumentException.class);

        SegmentCatalog.SegmentMetadata record = catalog.get(created);
        assertThat(record.getVolumePath()).isEqualTo(tempDir.resolve("rolling.dat"));
        assertThat(record.getSourceId()).isEqualTo("source-a");
        assertThat(catalog.getByVolume(live)).isEmpty();
    }

    @Test
    @DisplayName("a routing index held by a created segment is refused, not adopted")
    void aCreatedRecordInTheRoutingRangeIsNotAdopted() throws Exception {
        Path catalogPath = tempDir.resolve("catalog.dat");
        Path rolling = tempDir.resolve("rolling.dat");
        writeLegacyCreatedRecord(catalogPath, 64L, "other-source", rolling, 0L);

        SegmentCatalog catalog = new SegmentCatalog(catalogPath);
        try {
            catalog.recover();
            Path rightChild = tempDir.resolve("segment_64.dat");

            assertThatThrownBy(() -> catalog.checkRoutingRegistration(64L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("other-source");
            assertThatThrownBy(() -> catalog.add(64L, rightChild))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> catalog.registerFile(64L, rightChild))
                .isInstanceOf(IllegalStateException.class);

            SegmentCatalog.SegmentMetadata record = catalog.get(64L);
            assertThat(record.getLifecycleState()).isEqualTo(LifecycleState.TOMBSTONED);
            assertThat(record.getVolumePath())
                .as("the other source's record still names its own file")
                .isEqualTo(rolling);
            assertThat(catalog.getByVolume(rightChild)).isEmpty();

            // Once that record is purged, the index is free for the routing segment.
            catalog.remove(64L);
            assertThat(catalog.add(64L, rightChild).getLifecycleState())
                .isEqualTo(LifecycleState.INDEXING);
        } finally {
            catalog.close();
        }
    }

    @Test
    @DisplayName("a created record holding a routing index is replaced by the routing segment's row")
    void aCreatedRecordIsReplacedWithARoutingRow() throws Exception {
        Path catalogPath = tempDir.resolve("catalog.dat");
        Path volume = tempDir.resolve("volume");
        writeLegacyCreatedRecord(catalogPath, 64L, "other-source", volume, 4096L);
        writeLegacyCreatedRecord(catalogPath, 65L, "other-source", volume, 0L);

        SegmentCatalog catalog = new SegmentCatalog(catalogPath);
        try {
            catalog.recover();
            catalog.add(1L, tempDir.resolve("segment_1.dat"));
            Path file = tempDir.resolve("segment_64.dat");

            assertThat(catalog.isHeldByCreatedRecord(64L)).isTrue();
            assertThat(catalog.isHeldByCreatedRecord(1L)).isFalse();
            assertThat(catalog.isHeldByCreatedRecord(2L)).isFalse();

            SegmentCatalog.SegmentMetadata row = catalog.replaceWithRoutingRecord(64L, file, 10);
            assertThat(row.getSourceId()).isNull();
            assertThat(row.getLifecycleState()).isEqualTo(LifecycleState.ACTIVE);
            assertThat(row.getEntryCount()).isEqualTo(10);
            assertThat(row.getLastIntegratedOffset()).isEqualTo(4096L);
            assertThat(row.getTombstoneEpoch()).isNegative();
            assertThat(catalog.get(64L)).isSameAs(row);
            assertThat(catalog.getByVolume(file)).containsExactly(row);
            assertThat(catalog.getByVolume(volume)).extracting(SegmentCatalog.SegmentMetadata::getSegmentId)
                .containsExactly(65L);
            assertThat(catalog.isHeldByCreatedRecord(64L)).isFalse();

            assertThat(catalog.replaceWithRoutingRecord(65L, tempDir.resolve("segment_65.dat"), 0)
                    .getLifecycleState())
                .as("an empty routing segment gets the state discovery gives an empty one")
                .isEqualTo(LifecycleState.INDEXING);

            assertThatThrownBy(() -> catalog.replaceWithRoutingRecord(1L, file, 1))
                .as("a routing segment's own row is never replaced")
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> catalog.replaceWithRoutingRecord(
                    SegmentCatalog.FIRST_CREATED_SEGMENT_ID, file, 1))
                .isInstanceOf(IllegalArgumentException.class);
        } finally {
            catalog.close();
        }

        SegmentCatalog reopened = new SegmentCatalog(catalogPath);
        try {
            reopened.recover();
            assertThat(reopened.get(64L).getSourceId()).isNull();
            assertThat(reopened.get(64L).getLifecycleState()).isEqualTo(LifecycleState.ACTIVE);
        } finally {
            reopened.close();
        }
    }

    @Test
    @DisplayName("recovery resumes created ids past every one persisted, and round-trips them")
    void recoveryRestoresTheCreatedIdGenerator() {
        Path catalogPath = tempDir.resolve("catalog.dat");
        Path rolling = tempDir.resolve("rolling-b.dat");

        long highest;
        SegmentCatalog catalog = new SegmentCatalog(catalogPath);
        try {
            catalog.add(0L, tempDir.resolve("segment_0.dat"));
            catalog.create("source-a", tempDir.resolve("rolling-a.dat"));
            highest = catalog.create("source-b", rolling).getSegmentId();
            catalog.flush();
        } finally {
            catalog.close();
        }

        SegmentCatalog reopened = new SegmentCatalog(catalogPath);
        try {
            reopened.recover();

            SegmentCatalog.SegmentMetadata recovered = reopened.get(highest);
            assertThat(recovered).as("an id above the int range survives persistence").isNotNull();
            assertThat(recovered.getSegmentId()).isEqualTo(highest);
            assertThat(recovered.getSourceId()).isEqualTo("source-b");
            assertThat(recovered.getVolumePath()).isEqualTo(rolling);

            long next = reopened.create("source-c", tempDir.resolve("rolling-c.dat")).getSegmentId();
            assertThat(next).isEqualTo(highest + 1);
        } finally {
            reopened.close();
        }
    }

    @Test
    @DisplayName("a created id persisted inside the routing range does not pull new ids into it")
    void recoveryOfALegacyCreatedIdKeepsNewIdsOutOfTheRoutingRange() throws Exception {
        Path catalogPath = tempDir.resolve("catalog.dat");
        writeLegacyCreatedRecord(catalogPath, 65L, "legacy-source", tempDir.resolve("old.dat"), 0L);

        SegmentCatalog catalog = new SegmentCatalog(catalogPath);
        try {
            catalog.recover();

            assertThat(catalog.get(65L).getSourceId())
                .as("the legacy record is kept under its id")
                .isEqualTo("legacy-source");
            assertThat(catalog.create("source-a", tempDir.resolve("rolling.dat")).getSegmentId())
                .isEqualTo(SegmentCatalog.FIRST_CREATED_SEGMENT_ID);
        } finally {
            catalog.close();
        }
    }

    @Test
    @DisplayName("clearing the catalog restarts created ids at the bottom of their range")
    void clearResetsTheCreatedIdGenerator() {
        SegmentCatalog catalog = new SegmentCatalog();
        catalog.create("source-a", tempDir.resolve("a.dat"));
        catalog.create("source-b", tempDir.resolve("b.dat"));

        catalog.clear();

        assertThat(catalog.create("source-c", tempDir.resolve("c.dat")).getSegmentId())
            .isEqualTo(SegmentCatalog.FIRST_CREATED_SEGMENT_ID);
    }

    /**
     * Writes a TOMBSTONED record for a source under an id inside the routing range, as a
     * catalog whose {@code create()} shared its counter with routing indices could hold.
     */
    private static void writeLegacyCreatedRecord(Path catalogPath, long segmentId,
                                                 String sourceId, Path volumePath,
                                                 long lastIntegratedOffset)
            throws Exception {
        SegmentCatalog.SegmentMetadata legacy = new SegmentCatalog.SegmentMetadata(segmentId);
        legacy.setLastIntegratedOffset(lastIntegratedOffset);
        legacy.setTombstoneEpoch(7L);
        legacy.setSourceId(sourceId);
        legacy.setVolumePath(volumePath);
        legacy.setCreationTime(System.currentTimeMillis());
        legacy.restoreLifecycleState(LifecycleState.TOMBSTONED);
        try (CatalogFile file = new CatalogFile(catalogPath)) {
            file.writeSegment(legacy);
            file.flush();
        }
    }
}
