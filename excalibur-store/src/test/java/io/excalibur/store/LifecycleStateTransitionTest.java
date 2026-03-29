package io.excalibur.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for valid LifecycleState transitions.
 *
 * <p>Valid transitions according to the state machine:
 * <pre>
 * BOOTSTRAPPING → INDEXING → WARMING → ACTIVE ⟷ COMPACTING → TOMBSTONED → PURGED
 * </pre>
 * </p>
 *
 * @author ExcaliburDB
 */
@DisplayName("LifecycleState Transition Tests")
class LifecycleStateTransitionTest {

    // ==================== Enum Transition Validation Tests ====================

    @Nested
    @DisplayName("Enum-level transition validation")
    class EnumTransitionTests {

        @Test
        @DisplayName("BOOTSTRAPPING → INDEXING is valid")
        void bootstrappingToIndexingIsValid() {
            assertDoesNotThrow(() ->
                LifecycleState.validateTransition(LifecycleState.BOOTSTRAPPING, LifecycleState.INDEXING)
            );
        }

        @Test
        @DisplayName("INDEXING → WARMING is valid")
        void indexingToWarmingIsValid() {
            assertDoesNotThrow(() ->
                LifecycleState.validateTransition(LifecycleState.INDEXING, LifecycleState.WARMING)
            );
        }

        @Test
        @DisplayName("INDEXING → ACTIVE is valid (direct transition)")
        void indexingToActiveIsValid() {
            assertDoesNotThrow(() ->
                LifecycleState.validateTransition(LifecycleState.INDEXING, LifecycleState.ACTIVE)
            );
        }

        @Test
        @DisplayName("INDEXING → TOMBSTONED is valid (direct deletion)")
        void indexingToTombstonedIsValid() {
            assertDoesNotThrow(() ->
                LifecycleState.validateTransition(LifecycleState.INDEXING, LifecycleState.TOMBSTONED)
            );
        }

        @Test
        @DisplayName("WARMING → ACTIVE is valid")
        void warmingToActiveIsValid() {
            assertDoesNotThrow(() ->
                LifecycleState.validateTransition(LifecycleState.WARMING, LifecycleState.ACTIVE)
            );
        }

        @Test
        @DisplayName("ACTIVE → COMPACTING is valid")
        void activeToCompactingIsValid() {
            assertDoesNotThrow(() ->
                LifecycleState.validateTransition(LifecycleState.ACTIVE, LifecycleState.COMPACTING)
            );
        }

        @Test
        @DisplayName("ACTIVE → TOMBSTONED is valid")
        void activeToTombstonedIsValid() {
            assertDoesNotThrow(() ->
                LifecycleState.validateTransition(LifecycleState.ACTIVE, LifecycleState.TOMBSTONED)
            );
        }

        @Test
        @DisplayName("ACTIVE → WARMING is valid (re-indexing)")
        void activeToWarmingIsValid() {
            assertDoesNotThrow(() ->
                LifecycleState.validateTransition(LifecycleState.ACTIVE, LifecycleState.WARMING)
            );
        }

        @Test
        @DisplayName("COMPACTING → ACTIVE is valid")
        void compactingToActiveIsValid() {
            assertDoesNotThrow(() ->
                LifecycleState.validateTransition(LifecycleState.COMPACTING, LifecycleState.ACTIVE)
            );
        }

        @Test
        @DisplayName("COMPACTING → TOMBSTONED is valid")
        void compactingToTombstonedIsValid() {
            assertDoesNotThrow(() ->
                LifecycleState.validateTransition(LifecycleState.COMPACTING, LifecycleState.TOMBSTONED)
            );
        }

        @Test
        @DisplayName("TOMBSTONED → PURGED is valid")
        void tombstonedToPurgedIsValid() {
            assertDoesNotThrow(() ->
                LifecycleState.validateTransition(LifecycleState.TOMBSTONED, LifecycleState.PURGED)
            );
        }

        @Test
        @DisplayName("PURGED → BOOTSTRAPPING is valid (re-allocation)")
        void purgedToBootstrappingIsValid() {
            assertDoesNotThrow(() ->
                LifecycleState.validateTransition(LifecycleState.PURGED, LifecycleState.BOOTSTRAPPING)
            );
        }

        @Test
        @DisplayName("Same state transition is valid (no-op)")
        void sameStateTransitionIsValid() {
            for (LifecycleState state : LifecycleState.values()) {
                assertDoesNotThrow(() ->
                    LifecycleState.validateTransition(state, state),
                    "Same state transition should be valid for " + state
                );
            }
        }
    }

    // ==================== SegmentCatalog Transition Tests ====================

    @Nested
    @DisplayName("SegmentCatalog lifecycle transitions")
    class CatalogTransitionTests {

        private SegmentCatalog catalog;

        @BeforeEach
        void setUp() {
            catalog = new SegmentCatalog();
        }

        @Test
        @DisplayName("add() transitions BOOTSTRAPPING → INDEXING")
        void addTransitionsToIndexing() {
            SegmentCatalog.SegmentMetadata metadata = catalog.add(1L, java.nio.file.Path.of("/tmp/segment_1.dat"));

            assertEquals(LifecycleState.INDEXING, metadata.getLifecycleState());
        }

        @Test
        @DisplayName("completeIndexing() transitions INDEXING → WARMING")
        void completeIndexingTransitionsToWarming() {
            catalog.add(1L, java.nio.file.Path.of("/tmp/segment_1.dat"));

            catalog.completeIndexing(1L);

            assertEquals(LifecycleState.WARMING, catalog.get(1L).getLifecycleState());
        }

        @Test
        @DisplayName("completeWarmup() transitions WARMING → ACTIVE")
        void completeWarmupTransitionsToActive() {
            catalog.add(1L, java.nio.file.Path.of("/tmp/segment_1.dat"));
            catalog.completeIndexing(1L);

            catalog.completeWarmup(1L);

            assertEquals(LifecycleState.ACTIVE, catalog.get(1L).getLifecycleState());
            assertEquals(SegmentCatalog.ENTRY_STATE_ACTIVE, catalog.get(1L).getEntryState());
        }

        @Test
        @DisplayName("beginCompaction() transitions ACTIVE → COMPACTING")
        void beginCompactionTransitionsToCompacting() {
            catalog.add(1L, java.nio.file.Path.of("/tmp/segment_1.dat"));
            catalog.completeIndexing(1L);
            catalog.completeWarmup(1L);

            catalog.beginCompaction(1L);

            assertEquals(LifecycleState.COMPACTING, catalog.get(1L).getLifecycleState());
        }

        @Test
        @DisplayName("completeCompaction() transitions COMPACTING → ACTIVE")
        void completeCompactionTransitionsToActive() {
            catalog.add(1L, java.nio.file.Path.of("/tmp/segment_1.dat"));
            catalog.completeIndexing(1L);
            catalog.completeWarmup(1L);
            catalog.beginCompaction(1L);

            catalog.completeCompaction(1L);

            assertEquals(LifecycleState.ACTIVE, catalog.get(1L).getLifecycleState());
            assertEquals(SegmentCatalog.ENTRY_STATE_ACTIVE, catalog.get(1L).getEntryState());
            assertTrue(catalog.get(1L).getLastCompactionTime() > 0);
        }

        @Test
        @DisplayName("tombstone() transitions ACTIVE → TOMBSTONED")
        void tombstoneFromActiveTransitionsToTombstoned() {
            catalog.add(1L, java.nio.file.Path.of("/tmp/segment_1.dat"));
            catalog.completeIndexing(1L);
            catalog.completeWarmup(1L);

            catalog.tombstone(1L);

            assertEquals(LifecycleState.TOMBSTONED, catalog.get(1L).getLifecycleState());
        }

        @Test
        @DisplayName("tombstone() with epoch records tombstone epoch")
        void tombstoneWithEpochRecordsEpoch() {
            catalog.add(1L, java.nio.file.Path.of("/tmp/segment_1.dat"));
            catalog.completeIndexing(1L);
            catalog.completeWarmup(1L);

            catalog.tombstone(1L, 100L);

            assertEquals(LifecycleState.TOMBSTONED, catalog.get(1L).getLifecycleState());
            assertEquals(100L, catalog.get(1L).getTombstoneEpoch());
        }

        @Test
        @DisplayName("remove() transitions TOMBSTONED → PURGED")
        void removeTransitionsToPurged() {
            catalog.add(1L, java.nio.file.Path.of("/tmp/segment_1.dat"));
            catalog.completeIndexing(1L);
            catalog.completeWarmup(1L);
            catalog.tombstone(1L);

            catalog.remove(1L);

            assertNull(catalog.get(1L));
        }
    }

    // ==================== SegmentMetadata Transition Tests ====================

    @Nested
    @DisplayName("SegmentMetadata direct transitions")
    class MetadataTransitionTests {

        private SegmentCatalog.SegmentMetadata metadata;

        @BeforeEach
        void setUp() {
            metadata = new SegmentCatalog.SegmentMetadata(1L);
        }

        @Test
        @DisplayName("transitionToIndexing() sets INDEXING state")
        void transitionToIndexing() {
            metadata.transitionToIndexing();
            assertEquals(LifecycleState.INDEXING, metadata.getLifecycleState());
        }

        @Test
        @DisplayName("transitionToWarming() sets WARMING state")
        void transitionToWarming() {
            metadata.transitionToIndexing();
            metadata.transitionToWarming();
            assertEquals(LifecycleState.WARMING, metadata.getLifecycleState());
        }

        @Test
        @DisplayName("transitionToActive() sets ACTIVE state and entry state")
        void transitionToActive() {
            metadata.transitionToIndexing();
            metadata.transitionToWarming();
            metadata.transitionToActive();
            assertEquals(LifecycleState.ACTIVE, metadata.getLifecycleState());
            assertEquals(SegmentCatalog.ENTRY_STATE_ACTIVE, metadata.getEntryState());
        }

        @Test
        @DisplayName("transitionToCompacting() sets COMPACTING state")
        void transitionToCompacting() {
            metadata.transitionToIndexing();
            metadata.transitionToWarming();
            metadata.transitionToActive();
            metadata.transitionToCompacting();
            assertEquals(LifecycleState.COMPACTING, metadata.getLifecycleState());
        }

        @Test
        @DisplayName("transitionToTombstoned() sets TOMBSTONED state")
        void transitionToTombstoned() {
            metadata.transitionToIndexing();
            metadata.transitionToWarming();
            metadata.transitionToActive();
            metadata.transitionToTombstoned();
            assertEquals(LifecycleState.TOMBSTONED, metadata.getLifecycleState());
        }

        @Test
        @DisplayName("transitionToTombstoned(epoch) records epoch")
        void transitionToTombstonedWithEpoch() {
            metadata.transitionToIndexing();
            metadata.transitionToWarming();
            metadata.transitionToActive();
            metadata.transitionToTombstoned(42L);
            assertEquals(LifecycleState.TOMBSTONED, metadata.getLifecycleState());
            assertEquals(42L, metadata.getTombstoneEpoch());
        }

        @Test
        @DisplayName("transitionToPurged() sets PURGED state")
        void transitionToPurged() {
            metadata.transitionToIndexing();
            metadata.transitionToWarming();
            metadata.transitionToActive();
            metadata.transitionToTombstoned();
            metadata.transitionToPurged();
            assertEquals(LifecycleState.PURGED, metadata.getLifecycleState());
        }

        @Test
        @DisplayName("setLifecycleState() validates transition")
        void setLifecycleStateValidates() {
            // Valid transition
            assertDoesNotThrow(() -> metadata.setLifecycleState(LifecycleState.INDEXING));

            // Invalid transition: INDEXING -> BOOTSTRAPPING
            assertThrows(IllegalStateException.class, () ->
                metadata.setLifecycleState(LifecycleState.BOOTSTRAPPING)
            );
        }
    }

    // ==================== LifecycleState Advance Tests ====================

    @Nested
    @DisplayName("LifecycleState advance() method")
    class AdvanceTests {

        @Test
        @DisplayName("BOOTSTRAPPING advances to INDEXING")
        void bootstrappingAdvances() {
            assertEquals(LifecycleState.INDEXING, LifecycleState.BOOTSTRAPPING.advance());
        }

        @Test
        @DisplayName("INDEXING advances to WARMING")
        void indexingAdvances() {
            assertEquals(LifecycleState.WARMING, LifecycleState.INDEXING.advance());
        }

        @Test
        @DisplayName("WARMING advances to ACTIVE")
        void warmingAdvances() {
            assertEquals(LifecycleState.ACTIVE, LifecycleState.WARMING.advance());
        }

        @Test
        @DisplayName("ACTIVE advances to COMPACTING")
        void activeAdvances() {
            assertEquals(LifecycleState.COMPACTING, LifecycleState.ACTIVE.advance());
        }

        @Test
        @DisplayName("COMPACTING advances to ACTIVE")
        void compactingAdvances() {
            assertEquals(LifecycleState.ACTIVE, LifecycleState.COMPACTING.advance());
        }

        @Test
        @DisplayName("TOMBSTONED advances to PURGED")
        void tombstonedAdvances() {
            assertEquals(LifecycleState.PURGED, LifecycleState.TOMBSTONED.advance());
        }

        @Test
        @DisplayName("PURGED advances to BOOTSTRAPPING")
        void purgedAdvances() {
            assertEquals(LifecycleState.BOOTSTRAPPING, LifecycleState.PURGED.advance());
        }
    }

    // ==================== Queryable State Tests ====================

    @Nested
    @DisplayName("Queryable state checks")
    class QueryableTests {

        @Test
        @DisplayName("INDEXING is queryable")
        void indexingIsQueryable() {
            assertTrue(LifecycleState.INDEXING.isQueryable());
        }

        @Test
        @DisplayName("WARMING is queryable")
        void warmingIsQueryable() {
            assertTrue(LifecycleState.WARMING.isQueryable());
        }

        @Test
        @DisplayName("ACTIVE is queryable")
        void activeIsQueryable() {
            assertTrue(LifecycleState.ACTIVE.isQueryable());
        }

        @Test
        @DisplayName("COMPACTING is queryable")
        void compactingIsQueryable() {
            assertTrue(LifecycleState.COMPACTING.isQueryable());
        }

        @Test
        @DisplayName("BOOTSTRAPPING is not queryable")
        void bootstrappingIsNotQueryable() {
            assertFalse(LifecycleState.BOOTSTRAPPING.isQueryable());
        }

        @Test
        @DisplayName("TOMBSTONED is not queryable")
        void tombstonedIsNotQueryable() {
            assertFalse(LifecycleState.TOMBSTONED.isQueryable());
        }

        @Test
        @DisplayName("PURGED is not queryable")
        void purgedIsNotQueryable() {
            assertFalse(LifecycleState.PURGED.isQueryable());
        }
    }

    // ==================== Entry State Synchronization Tests ====================

    @Nested
    @DisplayName("Entry state to lifecycle state synchronization")
    class EntryStateSyncTests {

        private SegmentCatalog catalog;

        @BeforeEach
        void setUp() {
            catalog = new SegmentCatalog();
        }

        @Test
        @DisplayName("update() with ENTRY_STATE_INDEXING syncs lifecycleState")
        void updateIndexingSyncsLifecycle() {
            catalog.update(1L, SegmentCatalog.ENTRY_STATE_INDEXING, 1);

            assertEquals(LifecycleState.INDEXING, catalog.get(1L).getLifecycleState());
            assertEquals(SegmentCatalog.ENTRY_STATE_INDEXING, catalog.get(1L).getEntryState());
        }

        @Test
        @DisplayName("update() with ENTRY_STATE_WARMING syncs lifecycleState")
        void updateWarmingSyncsLifecycle() {
            catalog.update(1L, SegmentCatalog.ENTRY_STATE_WARMING, 1);

            assertEquals(LifecycleState.WARMING, catalog.get(1L).getLifecycleState());
            assertEquals(SegmentCatalog.ENTRY_STATE_WARMING, catalog.get(1L).getEntryState());
        }

        @Test
        @DisplayName("update() with ENTRY_STATE_ACTIVE syncs lifecycleState")
        void updateActiveSyncsLifecycle() {
            catalog.update(1L, SegmentCatalog.ENTRY_STATE_ACTIVE, 1);

            assertEquals(LifecycleState.ACTIVE, catalog.get(1L).getLifecycleState());
            assertEquals(SegmentCatalog.ENTRY_STATE_ACTIVE, catalog.get(1L).getEntryState());
        }

        @Test
        @DisplayName("update() with ENTRY_STATE_COMPACTING syncs lifecycleState")
        void updateCompactingSyncsLifecycle() {
            catalog.update(1L, SegmentCatalog.ENTRY_STATE_COMPACTING, 1);

            assertEquals(LifecycleState.COMPACTING, catalog.get(1L).getLifecycleState());
            assertEquals(SegmentCatalog.ENTRY_STATE_COMPACTING, catalog.get(1L).getEntryState());
        }

        @Test
        @DisplayName("update() with ENTRY_STATE_DELETED syncs lifecycleState")
        void updateDeletedSyncsLifecycle() {
            catalog.update(1L, SegmentCatalog.ENTRY_STATE_DELETED, 1);

            assertEquals(LifecycleState.TOMBSTONED, catalog.get(1L).getLifecycleState());
            assertEquals(SegmentCatalog.ENTRY_STATE_DELETED, catalog.get(1L).getEntryState());
        }

        @Test
        @DisplayName("update() with ENTRY_STATE_NONE syncs lifecycleState")
        void updateNoneSyncsLifecycle() {
            catalog.update(1L, SegmentCatalog.ENTRY_STATE_NONE, 0);

            assertEquals(LifecycleState.BOOTSTRAPPING, catalog.get(1L).getLifecycleState());
            assertEquals(SegmentCatalog.ENTRY_STATE_NONE, catalog.get(1L).getEntryState());
        }
    }
}
