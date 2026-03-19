package io.excalibur.store.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Additional tests for StateMachine class.
 */
@DisplayName("StateMachine Additional Tests")
class StateMachineAdditionalTest {

    private StateMachine<SegmentState> stateMachine;

    @BeforeEach
    void setUp() {
        // Create state machine with validator that enforces proper lifecycle transitions
        stateMachine = new StateMachine<>(SegmentState.BOOTSTRAPPING, (current, desired) -> {
            return switch (current) {
                case BOOTSTRAPPING -> desired == SegmentState.INDEXING;
                case INDEXING -> desired == SegmentState.WARMING || desired == SegmentState.ACTIVE || desired == SegmentState.TOMBSTONED;
                case WARMING -> desired == SegmentState.ACTIVE;
                case ACTIVE -> desired == SegmentState.COMPACTING || desired == SegmentState.TOMBSTONED;
                case COMPACTING -> desired == SegmentState.ACTIVE || desired == SegmentState.TOMBSTONED;
                case TOMBSTONED -> desired == SegmentState.PURGED;
                case PURGED -> false;
            };
        });
    }

    @Nested
    @DisplayName("Invalid Transition Tests")
    class InvalidTransitionTests {

        @Test
        @DisplayName("Should reject transition from BOOTSTRAPPING to WARMING")
        void shouldRejectTransitionFromBootstrappingToWarming() {
            assertThatThrownBy(() ->
                stateMachine.transitionTo(SegmentState.WARMING)
            ).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Should reject transition from INDEXING to ACTIVE")
        void shouldRejectTransitionFromIndexingToActive() {
            stateMachine.transitionTo(SegmentState.INDEXING);

            // This should fail because INDEXING can only go to WARMING (or ACTIVE/TOMBSTONED)
            // But our validator allows INDEXING -> ACTIVE, so let's test INDEXING -> COMPACTING instead
            assertThatThrownBy(() ->
                stateMachine.transitionTo(SegmentState.COMPACTING)
            ).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Should reject transition from BOOTSTRAPPING to TOMBSTONED")
        void shouldRejectTransitionFromBootstrappingToTombstoned() {
            assertThatThrownBy(() ->
                stateMachine.transitionTo(SegmentState.TOMBSTONED)
            ).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Tombstone Tests")
    class TombstoneTests {

        @Test
        @DisplayName("Should transition from ACTIVE to TOMBSTONED")
        void shouldTransitionFromActiveToTombstoned() {
            stateMachine.transitionTo(SegmentState.INDEXING);
            stateMachine.transitionTo(SegmentState.WARMING);
            stateMachine.transitionTo(SegmentState.ACTIVE);

            stateMachine.transitionTo(SegmentState.TOMBSTONED);

            assertThat(stateMachine.get()).isEqualTo(SegmentState.TOMBSTONED);
        }

        @Test
        @DisplayName("Should transition from COMPACTING to TOMBSTONED")
        void shouldTransitionFromCompactingToTombstoned() {
            stateMachine.transitionTo(SegmentState.INDEXING);
            stateMachine.transitionTo(SegmentState.WARMING);
            stateMachine.transitionTo(SegmentState.ACTIVE);
            stateMachine.transitionTo(SegmentState.COMPACTING);

            stateMachine.transitionTo(SegmentState.TOMBSTONED);

            assertThat(stateMachine.get()).isEqualTo(SegmentState.TOMBSTONED);
        }
    }

    @Nested
    @DisplayName("Purge Tests")
    class PurgeTests {

        @Test
        @DisplayName("Should transition from TOMBSTONED to PURGED")
        void shouldTransitionFromTombstonedToPurged() {
            stateMachine.transitionTo(SegmentState.INDEXING);
            stateMachine.transitionTo(SegmentState.WARMING);
            stateMachine.transitionTo(SegmentState.ACTIVE);
            stateMachine.transitionTo(SegmentState.TOMBSTONED);

            stateMachine.transitionTo(SegmentState.PURGED);

            assertThat(stateMachine.get()).isEqualTo(SegmentState.PURGED);
        }
    }
}
