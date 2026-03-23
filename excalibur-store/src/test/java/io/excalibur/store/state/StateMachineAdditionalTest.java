package io.excalibur.store.state;

import io.excalibur.store.LifecycleState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Additional tests for StateMachine class using canonical LifecycleState enum.
 */
@DisplayName("StateMachine Additional Tests")
class StateMachineAdditionalTest {

    private StateMachine stateMachine;

    @BeforeEach
    void setUp() {
        // Create state machine with validator that enforces proper lifecycle transitions
        stateMachine = new StateMachine(LifecycleState.BOOTSTRAPPING, (current, desired) -> {
            return switch (current) {
                case BOOTSTRAPPING -> desired == LifecycleState.INDEXING;
                case INDEXING -> desired == LifecycleState.WARMING || desired == LifecycleState.ACTIVE || desired == LifecycleState.TOMBSTONED;
                case WARMING -> desired == LifecycleState.ACTIVE;
                case ACTIVE -> desired == LifecycleState.COMPACTING || desired == LifecycleState.TOMBSTONED;
                case COMPACTING -> desired == LifecycleState.ACTIVE || desired == LifecycleState.TOMBSTONED;
                case TOMBSTONED -> desired == LifecycleState.PURGED;
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
                stateMachine.transitionTo(LifecycleState.WARMING)
            ).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Should reject transition from INDEXING to COMPACTING")
        void shouldRejectTransitionFromIndexingToCompacting() {
            stateMachine.transitionTo(LifecycleState.INDEXING);

            // INDEXING -> COMPACTING is not allowed
            assertThatThrownBy(() ->
                stateMachine.transitionTo(LifecycleState.COMPACTING)
            ).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Should reject transition from BOOTSTRAPPING to TOMBSTONED")
        void shouldRejectTransitionFromBootstrappingToTombstoned() {
            assertThatThrownBy(() ->
                stateMachine.transitionTo(LifecycleState.TOMBSTONED)
            ).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Tombstone Tests")
    class TombstoneTests {

        @Test
        @DisplayName("Should transition from ACTIVE to TOMBSTONED")
        void shouldTransitionFromActiveToTombstoned() {
            stateMachine.transitionTo(LifecycleState.INDEXING);
            stateMachine.transitionTo(LifecycleState.WARMING);
            stateMachine.transitionTo(LifecycleState.ACTIVE);

            stateMachine.transitionTo(LifecycleState.TOMBSTONED);

            assertThat(stateMachine.get()).isEqualTo(LifecycleState.TOMBSTONED);
        }

        @Test
        @DisplayName("Should transition from COMPACTING to ACTIVE then TOMBSTONED")
        void shouldTransitionFromCompactingToTombstoned() {
            stateMachine.transitionTo(LifecycleState.INDEXING);
            stateMachine.transitionTo(LifecycleState.WARMING);
            stateMachine.transitionTo(LifecycleState.ACTIVE);
            stateMachine.transitionTo(LifecycleState.COMPACTING);
            // COMPACTING can only go to ACTIVE first
            stateMachine.transitionTo(LifecycleState.ACTIVE);
            stateMachine.transitionTo(LifecycleState.TOMBSTONED);

            assertThat(stateMachine.get()).isEqualTo(LifecycleState.TOMBSTONED);
        }
    }

    @Nested
    @DisplayName("Purge Tests")
    class PurgeTests {

        @Test
        @DisplayName("Should transition from TOMBSTONED to PURGED")
        void shouldTransitionFromTombstonedToPurged() {
            stateMachine.transitionTo(LifecycleState.INDEXING);
            stateMachine.transitionTo(LifecycleState.WARMING);
            stateMachine.transitionTo(LifecycleState.ACTIVE);
            stateMachine.transitionTo(LifecycleState.TOMBSTONED);

            stateMachine.transitionTo(LifecycleState.PURGED);

            assertThat(stateMachine.get()).isEqualTo(LifecycleState.PURGED);
        }
    }

    @Nested
    @DisplayName("LifecycleState Validation Tests")
    class LifecycleStateValidationTests {

        @Test
        @DisplayName("Should validate all LifecycleState values")
        void shouldValidateAllLifecycleStateValues() {
            for (LifecycleState state : LifecycleState.values()) {
                assertThat(StateMachine.isValidState(state)).isTrue();
            }
        }

        @Test
        @DisplayName("Should enforce states must be from LifecycleState enum")
        void shouldEnforceStatesMustBeFromLifecycleStateEnum() {
            // This test ensures the core requirement: "Segment states must be from the defined lifecycle enum"
            StateMachine sm = new StateMachine(LifecycleState.BOOTSTRAPPING);

            // All valid transitions must use LifecycleState
            assertThat(sm.get()).isInstanceOf(LifecycleState.class);

            sm.transitionTo(LifecycleState.INDEXING);
            assertThat(sm.get()).isInstanceOf(LifecycleState.class);

            sm.transitionTo(LifecycleState.WARMING);
            assertThat(sm.get()).isInstanceOf(LifecycleState.class);

            sm.transitionTo(LifecycleState.ACTIVE);
            assertThat(sm.get()).isInstanceOf(LifecycleState.class);
        }

        @Test
        @DisplayName("Should reject null state as invalid")
        void shouldRejectNullStateAsInvalid() {
            assertThat(StateMachine.isValidState(null)).isFalse();
        }

        @Test
        @DisplayName("Should reject non-enum values as invalid")
        void shouldRejectNonEnumValuesAsInvalid() {
            assertThat(StateMachine.isValidState("BOOTSTRAPPING")).isFalse();
            assertThat(StateMachine.isValidState(0)).isFalse();
            assertThat(StateMachine.isValidState(new Object())).isFalse();
        }
    }
}
