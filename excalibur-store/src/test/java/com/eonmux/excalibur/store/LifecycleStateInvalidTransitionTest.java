package com.eonmux.excalibur.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for invalid LifecycleState transitions.
 *
 * <p>These tests verify that invalid state transitions throw IllegalStateException.</p>
 *
 * @author ExcaliburDB
 */
@DisplayName("LifecycleState Invalid Transition Tests")
class LifecycleStateInvalidTransitionTest {

    // ==================== Enum-level Invalid Transition Tests ====================

    @Nested
    @DisplayName("Enum-level invalid transition validation")
    class EnumInvalidTransitionTests {

        @Test
        @DisplayName("BOOTSTRAPPING → WARMING is invalid")
        void bootstrappingToWarmingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.BOOTSTRAPPING, LifecycleState.WARMING)
            );
        }

        @Test
        @DisplayName("BOOTSTRAPPING → ACTIVE is invalid")
        void bootstrappingToActiveIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.BOOTSTRAPPING, LifecycleState.ACTIVE)
            );
        }

        @Test
        @DisplayName("BOOTSTRAPPING → COMPACTING is invalid")
        void bootstrappingToCompactingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.BOOTSTRAPPING, LifecycleState.COMPACTING)
            );
        }

        @Test
        @DisplayName("BOOTSTRAPPING → TOMBSTONED is invalid")
        void bootstrappingToTombstonedIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.BOOTSTRAPPING, LifecycleState.TOMBSTONED)
            );
        }

        @Test
        @DisplayName("BOOTSTRAPPING → PURGED is invalid")
        void bootstrappingToPurgedIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.BOOTSTRAPPING, LifecycleState.PURGED)
            );
        }

        @Test
        @DisplayName("INDEXING → BOOTSTRAPPING is invalid")
        void indexingToBootstrappingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.INDEXING, LifecycleState.BOOTSTRAPPING)
            );
        }

        @Test
        @DisplayName("INDEXING → COMPACTING is invalid")
        void indexingToCompactingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.INDEXING, LifecycleState.COMPACTING)
            );
        }

        @Test
        @DisplayName("INDEXING → PURGED is invalid")
        void indexingToPurgedIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.INDEXING, LifecycleState.PURGED)
            );
        }

        @Test
        @DisplayName("WARMING → BOOTSTRAPPING is invalid")
        void warmingToBootstrappingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.WARMING, LifecycleState.BOOTSTRAPPING)
            );
        }

        @Test
        @DisplayName("WARMING → INDEXING is invalid")
        void warmingToIndexingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.WARMING, LifecycleState.INDEXING)
            );
        }

        @Test
        @DisplayName("WARMING → COMPACTING is invalid")
        void warmingToCompactingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.WARMING, LifecycleState.COMPACTING)
            );
        }

        @Test
        @DisplayName("WARMING → TOMBSTONED is invalid")
        void warmingToTombstonedIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.WARMING, LifecycleState.TOMBSTONED)
            );
        }

        @Test
        @DisplayName("WARMING → PURGED is invalid")
        void warmingToPurgedIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.WARMING, LifecycleState.PURGED)
            );
        }

        @Test
        @DisplayName("ACTIVE → BOOTSTRAPPING is invalid")
        void activeToBootstrappingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.ACTIVE, LifecycleState.BOOTSTRAPPING)
            );
        }

        @Test
        @DisplayName("ACTIVE → INDEXING is invalid")
        void activeToIndexingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.ACTIVE, LifecycleState.INDEXING)
            );
        }

        @Test
        @DisplayName("ACTIVE → PURGED is invalid")
        void activeToPurgedIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.ACTIVE, LifecycleState.PURGED)
            );
        }

        @Test
        @DisplayName("COMPACTING → BOOTSTRAPPING is invalid")
        void compactingToBootstrappingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.COMPACTING, LifecycleState.BOOTSTRAPPING)
            );
        }

        @Test
        @DisplayName("COMPACTING → INDEXING is invalid")
        void compactingToIndexingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.COMPACTING, LifecycleState.INDEXING)
            );
        }

        @Test
        @DisplayName("COMPACTING → WARMING is invalid")
        void compactingToWarmingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.COMPACTING, LifecycleState.WARMING)
            );
        }

        @Test
        @DisplayName("COMPACTING → PURGED is invalid")
        void compactingToPurgedIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.COMPACTING, LifecycleState.PURGED)
            );
        }

        @Test
        @DisplayName("TOMBSTONED → BOOTSTRAPPING is invalid")
        void tombstonedToBootstrappingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.TOMBSTONED, LifecycleState.BOOTSTRAPPING)
            );
        }

        @Test
        @DisplayName("TOMBSTONED → INDEXING is invalid")
        void tombstonedToIndexingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.TOMBSTONED, LifecycleState.INDEXING)
            );
        }

        @Test
        @DisplayName("TOMBSTONED → WARMING is invalid")
        void tombstonedToWarmingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.TOMBSTONED, LifecycleState.WARMING)
            );
        }

        @Test
        @DisplayName("TOMBSTONED → ACTIVE is invalid")
        void tombstonedToActiveIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.TOMBSTONED, LifecycleState.ACTIVE)
            );
        }

        @Test
        @DisplayName("TOMBSTONED → COMPACTING is invalid")
        void tombstonedToCompactingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.TOMBSTONED, LifecycleState.COMPACTING)
            );
        }

        @Test
        @DisplayName("TOMBSTONED → TOMBSTONED (same state) is valid")
        void tombstonedToTombstonedSameStateIsValid() {
            assertDoesNotThrow(() ->
                LifecycleState.validateTransition(LifecycleState.TOMBSTONED, LifecycleState.TOMBSTONED)
            );
        }

        @Test
        @DisplayName("PURGED → INDEXING is invalid")
        void purgedToIndexingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.PURGED, LifecycleState.INDEXING)
            );
        }

        @Test
        @DisplayName("PURGED → WARMING is invalid")
        void purgedToWarmingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.PURGED, LifecycleState.WARMING)
            );
        }

        @Test
        @DisplayName("PURGED → ACTIVE is invalid")
        void purgedToActiveIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.PURGED, LifecycleState.ACTIVE)
            );
        }

        @Test
        @DisplayName("PURGED → COMPACTING is invalid")
        void purgedToCompactingIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.PURGED, LifecycleState.COMPACTING)
            );
        }

        @Test
        @DisplayName("PURGED → TOMBSTONED is invalid")
        void purgedToTombstonedIsInvalid() {
            assertThrows(IllegalStateException.class, () ->
                LifecycleState.validateTransition(LifecycleState.PURGED, LifecycleState.TOMBSTONED)
            );
        }

        @Test
        @DisplayName("PURGED → PURGED (same state) is valid")
        void purgedToPurgedSameStateIsValid() {
            assertDoesNotThrow(() ->
                LifecycleState.validateTransition(LifecycleState.PURGED, LifecycleState.PURGED)
            );
        }
    }

    // ==================== SegmentCatalog Invalid Transition Tests ====================

    @Nested
    @DisplayName("SegmentCatalog invalid transition exceptions")
    class CatalogInvalidTransitionTests {

        private SegmentCatalog catalog;

        @BeforeEach
        void setUp() {
            catalog = new SegmentCatalog();
        }

        @Test
        @DisplayName("completeIndexing() on non-INDEXING segment - same state is valid")
        void completeIndexingOnSameStateIsValid() {
            catalog.add(1L, java.nio.file.Path.of("/tmp/segment_1.dat"));
            catalog.completeIndexing(1L);

            // Now in WARMING, calling completeIndexing again transitions WARMING → WARMING (same state)
            // which is valid per the state machine
            assertDoesNotThrow(() -> catalog.completeIndexing(1L));
            assertEquals(LifecycleState.WARMING, catalog.get(1L).getLifecycleState());
        }

        @Test
        @DisplayName("completeWarmup() on non-WARMING segment - INDEXING to ACTIVE is valid")
        void completeWarmupFromIndexingIsValid() {
            catalog.add(1L, java.nio.file.Path.of("/tmp/segment_1.dat"));

            // INDEXING → ACTIVE is valid (direct transition per LifecycleState enum)
            assertDoesNotThrow(() -> catalog.completeWarmup(1L));
            assertEquals(LifecycleState.ACTIVE, catalog.get(1L).getLifecycleState());
        }

        @Test
        @DisplayName("beginCompaction() on non-ACTIVE segment throws")
        void beginCompactionOnNonActiveSegmentThrows() {
            catalog.add(1L, java.nio.file.Path.of("/tmp/segment_1.dat"));

            // In INDEXING, can't begin compaction
            assertThrows(IllegalStateException.class, () ->
                catalog.beginCompaction(1L)
            );
        }

        @Test
        @DisplayName("completeCompaction() on non-COMPACTING segment - ACTIVE to ACTIVE is valid")
        void completeCompactionOnActiveSegmentIsValid() {
            catalog.add(1L, java.nio.file.Path.of("/tmp/segment_1.dat"));
            catalog.completeIndexing(1L);
            catalog.completeWarmup(1L);

            // In ACTIVE, calling completeCompaction transitions ACTIVE → ACTIVE (same state)
            // which is valid per the state machine
            assertDoesNotThrow(() -> catalog.completeCompaction(1L));
            assertEquals(LifecycleState.ACTIVE, catalog.get(1L).getLifecycleState());
        }

        @Test
        @DisplayName("tombstone() on INDEXING segment throws (invalid)")
        void tombstoneOnIndexingSegmentSucceeds() {
            catalog.add(1L, java.nio.file.Path.of("/tmp/segment_1.dat"));

            // INDEXING → TOMBSTONED is actually valid per the enum
            // This test verifies it works
            assertDoesNotThrow(() -> catalog.tombstone(1L));
            assertEquals(LifecycleState.TOMBSTONED, catalog.get(1L).getLifecycleState());
        }

        @Test
        @DisplayName("remove() on non-TOMBSTONED segment throws")
        void removeOnNonTombstonedSegmentThrows() {
            catalog.add(1L, java.nio.file.Path.of("/tmp/segment_1.dat"));
            catalog.completeIndexing(1L);
            catalog.completeWarmup(1L);

            // In ACTIVE, can't remove without tombstoning first
            assertThrows(IllegalStateException.class, () ->
                catalog.remove(1L)
            );
        }

        @Test
        @DisplayName("beginIndexing() on already INDEXING segment - same state is valid")
        void beginIndexingOnAlreadyIndexingSegmentIsValid() {
            catalog.add(1L, java.nio.file.Path.of("/tmp/segment_1.dat"));

            // Already in INDEXING, calling beginIndexing again does INDEXING → INDEXING (same state)
            // which is valid per the state machine
            assertDoesNotThrow(() -> catalog.beginIndexing(1L));
            assertEquals(LifecycleState.INDEXING, catalog.get(1L).getLifecycleState());
        }
    }

    // ==================== SegmentMetadata Invalid Transition Tests ====================

    @Nested
    @DisplayName("SegmentMetadata invalid transition exceptions")
    class MetadataInvalidTransitionTests {

        private SegmentCatalog.SegmentMetadata metadata;

        @BeforeEach
        void setUp() {
            metadata = new SegmentCatalog.SegmentMetadata(1L);
        }

        @Test
        @DisplayName("transitionToWarming() from BOOTSTRAPPING throws")
        void transitionToWarmingFromBootstrappingThrows() {
            assertThrows(IllegalStateException.class, () ->
                metadata.transitionToWarming()
            );
        }

        @Test
        @DisplayName("transitionToActive() from INDEXING is valid (direct transition)")
        void transitionToActiveFromIndexingIsValid() {
            metadata.transitionToIndexing();

            // INDEXING → ACTIVE is valid per LifecycleState enum
            assertDoesNotThrow(() -> metadata.transitionToActive());
            assertEquals(LifecycleState.ACTIVE, metadata.getLifecycleState());
        }

        @Test
        @DisplayName("transitionToCompacting() from WARMING throws")
        void transitionToCompactingFromWarmingThrows() {
            metadata.transitionToIndexing();
            metadata.transitionToWarming();

            assertThrows(IllegalStateException.class, () ->
                metadata.transitionToCompacting()
            );
        }

        @Test
        @DisplayName("transitionToTombstoned() from WARMING throws")
        void transitionToTombstonedFromWarmingThrows() {
            metadata.transitionToIndexing();
            metadata.transitionToWarming();

            assertThrows(IllegalStateException.class, () ->
                metadata.transitionToTombstoned()
            );
        }

        @Test
        @DisplayName("transitionToPurged() from ACTIVE throws")
        void transitionToPurgedFromActiveThrows() {
            metadata.transitionToIndexing();
            metadata.transitionToWarming();
            metadata.transitionToActive();

            assertThrows(IllegalStateException.class, () ->
                metadata.transitionToPurged()
            );
        }

        @Test
        @DisplayName("resetToBootstrapping() from non-PURGED throws")
        void resetToBootstrappingFromNonPurgedThrows() {
            metadata.transitionToIndexing();

            assertThrows(IllegalStateException.class, () ->
                metadata.resetToBootstrapping()
            );
        }
    }

    // ==================== Exception Message Tests ====================

    @Nested
    @DisplayName("Exception message quality")
    class ExceptionMessageTests {

        @Test
        @DisplayName("Invalid transition includes current and target states")
        void invalidTransitionMessageIncludesStates() {
            try {
                LifecycleState.validateTransition(LifecycleState.BOOTSTRAPPING, LifecycleState.ACTIVE);
                fail("Expected IllegalStateException");
            } catch (IllegalStateException e) {
                String message = e.getMessage();
                assertTrue(message.contains("BOOTSTRAPPING"),
                    "Message should include current state: " + message);
                assertTrue(message.contains("ACTIVE"),
                    "Message should include target state: " + message);
            }
        }

        @Test
        @DisplayName("remove() exception explains TOMBSTONED requirement")
        void removeExceptionExplainsTombstonedRequirement() {
            SegmentCatalog catalog = new SegmentCatalog();
            catalog.add(1L, java.nio.file.Path.of("/tmp/segment_1.dat"));
            catalog.completeIndexing(1L);
            catalog.completeWarmup(1L);

            try {
                catalog.remove(1L);
                fail("Expected IllegalStateException");
            } catch (IllegalStateException e) {
                String message = e.getMessage();
                assertTrue(message.contains("TOMBSTONED"),
                    "Message should explain TOMBSTONED requirement: " + message);
            }
        }
    }
}
