package io.excalibur.store.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for state package classes.
 */
@DisplayName("State Package Tests")
class StatePackageTests {

    @Nested
    @DisplayName("SegmentState Tests")
    class SegmentStateTests {

        @Test
        @DisplayName("Should have all expected states")
        void shouldHaveAllExpectedStates() {
            assertThat(SegmentState.values()).containsExactlyInAnyOrder(
                SegmentState.BOOTSTRAPPING,
                SegmentState.INDEXING,
                SegmentState.WARMING,
                SegmentState.ACTIVE,
                SegmentState.COMPACTING,
                SegmentState.TOMBSTONED,
                SegmentState.PURGED
            );
        }

        @Test
        @DisplayName("Should return correct codes")
        void shouldReturnCorrectCodes() {
            assertThat(SegmentState.BOOTSTRAPPING.getCode()).isEqualTo(0);
            assertThat(SegmentState.INDEXING.getCode()).isEqualTo(1);
            assertThat(SegmentState.WARMING.getCode()).isEqualTo(2);
            assertThat(SegmentState.ACTIVE.getCode()).isEqualTo(3);
            assertThat(SegmentState.COMPACTING.getCode()).isEqualTo(4);
            assertThat(SegmentState.TOMBSTONED.getCode()).isEqualTo(5);
            assertThat(SegmentState.PURGED.getCode()).isEqualTo(6);
        }

        @Test
        @DisplayName("Should convert code to state")
        void shouldConvertCodeToState() {
            assertThat(SegmentState.fromCode(0)).isEqualTo(SegmentState.BOOTSTRAPPING);
            assertThat(SegmentState.fromCode(1)).isEqualTo(SegmentState.INDEXING);
            assertThat(SegmentState.fromCode(2)).isEqualTo(SegmentState.WARMING);
            assertThat(SegmentState.fromCode(3)).isEqualTo(SegmentState.ACTIVE);
            assertThat(SegmentState.fromCode(4)).isEqualTo(SegmentState.COMPACTING);
            assertThat(SegmentState.fromCode(5)).isEqualTo(SegmentState.TOMBSTONED);
            assertThat(SegmentState.fromCode(6)).isEqualTo(SegmentState.PURGED);
        }

        @Test
        @DisplayName("Should return null for invalid code")
        void shouldReturnNullForInvalidCode() {
            assertThat(SegmentState.fromCode(-1)).isNull();
            assertThat(SegmentState.fromCode(7)).isNull();
            assertThat(SegmentState.fromCode(100)).isNull();
        }

        @Test
        @DisplayName("Should return human readable names")
        void shouldReturnHumanReadableNames() {
            assertThat(SegmentState.BOOTSTRAPPING.toStringHuman()).isEqualTo("BOOTSTRAPPING");
            assertThat(SegmentState.INDEXING.toStringHuman()).isEqualTo("INDEXING");
            assertThat(SegmentState.ACTIVE.toStringHuman()).isEqualTo("ACTIVE");
        }
    }

    @Nested
    @DisplayName("StateMachine Tests")
    class StateMachineTests {

        private StateMachine<SegmentState> stateMachine;

        @BeforeEach
        void setUp() {
            stateMachine = new StateMachine<>(SegmentState.BOOTSTRAPPING);
        }

        @Test
        @DisplayName("Should initialize with given state")
        void shouldInitializeWithGivenState() {
            assertThat(stateMachine.get()).isEqualTo(SegmentState.BOOTSTRAPPING);
        }

        @Test
        @DisplayName("Should transition to new state")
        void shouldTransitionToNewState() {
            SegmentState previous = stateMachine.transitionTo(SegmentState.INDEXING);

            assertThat(previous).isEqualTo(SegmentState.BOOTSTRAPPING);
            assertThat(stateMachine.get()).isEqualTo(SegmentState.INDEXING);
        }

        @Test
        @DisplayName("Should try transition to new state")
        void shouldTryTransitionToNewState() {
            boolean success = stateMachine.tryTransitionTo(SegmentState.INDEXING);

            assertThat(success).isTrue();
            assertThat(stateMachine.get()).isEqualTo(SegmentState.INDEXING);
        }

        @Test
        @DisplayName("Should increment transition count")
        void shouldIncrementTransitionCount() {
            assertThat(stateMachine.getTransitionCount()).isEqualTo(0);

            stateMachine.transitionTo(SegmentState.INDEXING);
            assertThat(stateMachine.getTransitionCount()).isEqualTo(1);

            stateMachine.transitionTo(SegmentState.WARMING);
            assertThat(stateMachine.getTransitionCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should check if queryable")
        void shouldCheckIfQueryable() {
            stateMachine = new StateMachine<>(SegmentState.INDEXING);
            assertThat(stateMachine.isQueryable()).isTrue();

            stateMachine = new StateMachine<>(SegmentState.ACTIVE);
            assertThat(stateMachine.isQueryable()).isTrue();

            stateMachine = new StateMachine<>(SegmentState.BOOTSTRAPPING);
            assertThat(stateMachine.isQueryable()).isFalse();

            stateMachine = new StateMachine<>(SegmentState.TOMBSTONED);
            assertThat(stateMachine.isQueryable()).isFalse();
        }

        @Test
        @DisplayName("Should check if active")
        void shouldCheckIfActive() {
            stateMachine = new StateMachine<>(SegmentState.ACTIVE);
            assertThat(stateMachine.isActive()).isTrue();

            stateMachine = new StateMachine<>(SegmentState.INDEXING);
            assertThat(stateMachine.isActive()).isFalse();
        }

        @Test
        @DisplayName("Should check if terminal")
        void shouldCheckIfTerminal() {
            stateMachine = new StateMachine<>(SegmentState.PURGED);
            assertThat(stateMachine.isTerminal()).isTrue();

            stateMachine = new StateMachine<>(SegmentState.ACTIVE);
            assertThat(stateMachine.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("Should reset to new state")
        void shouldResetToNewState() {
            stateMachine.transitionTo(SegmentState.INDEXING);
            stateMachine.transitionTo(SegmentState.ACTIVE);

            stateMachine.reset(SegmentState.BOOTSTRAPPING);

            assertThat(stateMachine.get()).isEqualTo(SegmentState.BOOTSTRAPPING);
            assertThat(stateMachine.getTransitionCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should return state code")
        void shouldReturnStateCode() {
            assertThat(stateMachine.getStateCode()).isEqualTo(0);

            stateMachine.transitionTo(SegmentState.INDEXING);
            assertThat(stateMachine.getStateCode()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("SegmentStateTransition Tests")
    class SegmentStateTransitionTests {

        private SegmentStateTransition transition;

        @BeforeEach
        void setUp() {
            transition = new SegmentStateTransition("test-segment");
        }

        @Test
        @DisplayName("Should initialize with segment ID")
        void shouldInitializeWithSegmentId() {
            assertThat(transition.getState()).isEqualTo(SegmentState.BOOTSTRAPPING);
        }

        @Test
        @DisplayName("Should initialize with custom initial state")
        void shouldInitializeWithCustomInitialState() {
            transition = new SegmentStateTransition("test-segment", SegmentState.ACTIVE);

            assertThat(transition.getState()).isEqualTo(SegmentState.ACTIVE);
        }

        @Test
        @DisplayName("Should transition to new state")
        void shouldTransitionToNewState() {
            SegmentState previous = transition.transitionTo(SegmentState.INDEXING);

            assertThat(previous).isEqualTo(SegmentState.BOOTSTRAPPING);
            assertThat(transition.getState()).isEqualTo(SegmentState.INDEXING);
        }

        @Test
        @DisplayName("Should try transition to new state")
        void shouldTryTransitionToNewState() {
            boolean success = transition.tryTransitionTo(SegmentState.INDEXING);

            assertThat(success).isTrue();
            assertThat(transition.getState()).isEqualTo(SegmentState.INDEXING);
        }

        @Test
        @DisplayName("Should check if queryable")
        void shouldCheckIfQueryable() {
            transition = new SegmentStateTransition("test", SegmentState.INDEXING);
            assertThat(transition.isQueryable()).isTrue();
        }

        @Test
        @DisplayName("Should check if active")
        void shouldCheckIfActive() {
            transition = new SegmentStateTransition("test", SegmentState.ACTIVE);
            assertThat(transition.isActive()).isTrue();
        }

        @Test
        @DisplayName("Should check if terminal")
        void shouldCheckIfTerminal() {
            transition = new SegmentStateTransition("test", SegmentState.PURGED);
            assertThat(transition.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("Should get transition count")
        void shouldGetTransitionCount() {
            assertThat(transition.getTransitionCount()).isEqualTo(0);

            transition.transitionTo(SegmentState.INDEXING);
            assertThat(transition.getTransitionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should check if transition is valid")
        void shouldCheckIfTransitionIsValid() {
            // With default validator, all transitions are valid
            assertThat(transition.isTransitionValid(SegmentState.INDEXING)).isTrue();
        }

        @Test
        @DisplayName("Should get state code")
        void shouldGetStateCode() {
            assertThat(transition.getStateCode()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should get human readable state")
        void shouldGetHumanReadableState() {
            assertThat(transition.getStateHuman()).isEqualTo("BOOTSTRAPPING");
        }

        @Test
        @DisplayName("Should generate summary")
        void shouldGenerateSummary() {
            String summary = transition.toSummary();

            assertThat(summary).contains("test-segment");
            assertThat(summary).contains("BOOTSTRAPPING");
        }

        @Test
        @DisplayName("Should reset state")
        void shouldResetState() {
            transition.transitionTo(SegmentState.INDEXING);
            transition.reset(SegmentState.ACTIVE);

            assertThat(transition.getState()).isEqualTo(SegmentState.ACTIVE);
            assertThat(transition.getTransitionCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should record history")
        void shouldRecordHistory() {
            transition.transitionTo(SegmentState.INDEXING);
            transition.transitionTo(SegmentState.WARMING);

            String history = transition.getHistory().toString();

            assertThat(history).contains("BOOTSTRAPPING → INDEXING");
            assertThat(history).contains("INDEXING → WARMING");
        }
    }

    @Nested
    @DisplayName("StateTransitionResult Tests")
    class StateTransitionResultTests {

        @Test
        @DisplayName("Should create successful result")
        void shouldCreateSuccessfulResult() {
            StateTransitionResult result = new StateTransitionResult(
                SegmentState.BOOTSTRAPPING, SegmentState.INDEXING
            );

            assertThat(result.getPreviousState()).isEqualTo(SegmentState.BOOTSTRAPPING);
            assertThat(result.getDesiredState()).isEqualTo(SegmentState.INDEXING);
            assertThat(result.isSuccessful()).isTrue();
            assertThat(result.getErrorMessage()).isNull();
        }

        @Test
        @DisplayName("Should create failed result")
        void shouldCreateFailedResult() {
            StateTransitionResult result = new StateTransitionResult(
                SegmentState.BOOTSTRAPPING, SegmentState.INDEXING, "Invalid transition"
            );

            assertThat(result.isSuccessful()).isFalse();
            assertThat(result.getErrorMessage()).isEqualTo("Invalid transition");
        }

        @Test
        @DisplayName("Should generate string for successful result")
        void shouldGenerateStringForSuccessfulResult() {
            StateTransitionResult result = new StateTransitionResult(
                SegmentState.BOOTSTRAPPING, SegmentState.INDEXING
            );

            assertThat(result.toString()).contains("SUCCESSFUL");
            assertThat(result.toString()).contains("BOOTSTRAPPING");
            assertThat(result.toString()).contains("INDEXING");
        }

        @Test
        @DisplayName("Should generate string for failed result")
        void shouldGenerateStringForFailedResult() {
            StateTransitionResult result = new StateTransitionResult(
                SegmentState.BOOTSTRAPPING, SegmentState.INDEXING, "Error message"
            );

            assertThat(result.toString()).contains("FAILED");
            assertThat(result.toString()).contains("Error message");
        }

        @Test
        @DisplayName("Should check if has states")
        void shouldCheckIfHasStates() {
            StateTransitionResult result = new StateTransitionResult(
                SegmentState.BOOTSTRAPPING, SegmentState.INDEXING
            );

            assertThat(result.hasStates()).isTrue();
        }
    }
}
