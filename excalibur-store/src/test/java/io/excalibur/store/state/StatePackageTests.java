package io.excalibur.store.state;

import io.excalibur.store.LifecycleState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for state package classes using canonical LifecycleState enum.
 */
@DisplayName("State Package Tests")
class StatePackageTests {

    @Nested
    @DisplayName("SegmentState Deprecated Tests")
    class SegmentStateTests {

        @Test
        @DisplayName("Should have all expected states")
        @SuppressWarnings("deprecation")
        void shouldHaveAllExpectedStates() {
            // SegmentState is deprecated, test that it maps correctly to LifecycleState
            assertThat(SegmentState.BOOTSTRAPPING.toLifecycleState()).isEqualTo(LifecycleState.BOOTSTRAPPING);
            assertThat(SegmentState.INDEXING.toLifecycleState()).isEqualTo(LifecycleState.INDEXING);
            assertThat(SegmentState.WARMING.toLifecycleState()).isEqualTo(LifecycleState.WARMING);
            assertThat(SegmentState.ACTIVE.toLifecycleState()).isEqualTo(LifecycleState.ACTIVE);
            assertThat(SegmentState.COMPACTING.toLifecycleState()).isEqualTo(LifecycleState.COMPACTING);
            assertThat(SegmentState.TOMBSTONED.toLifecycleState()).isEqualTo(LifecycleState.TOMBSTONED);
            assertThat(SegmentState.PURGED.toLifecycleState()).isEqualTo(LifecycleState.PURGED);
        }

        @Test
        @DisplayName("Should return correct codes")
        @SuppressWarnings("deprecation")
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
        @SuppressWarnings("deprecation")
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
        @SuppressWarnings("deprecation")
        void shouldReturnNullForInvalidCode() {
            assertThat(SegmentState.fromCode(-1)).isNull();
            assertThat(SegmentState.fromCode(7)).isNull();
            assertThat(SegmentState.fromCode(100)).isNull();
        }

        @Test
        @DisplayName("Should return human readable names")
        @SuppressWarnings("deprecation")
        void shouldReturnHumanReadableNames() {
            assertThat(SegmentState.BOOTSTRAPPING.toStringHuman()).isEqualTo("BOOTSTRAPPING");
            assertThat(SegmentState.INDEXING.toStringHuman()).isEqualTo("INDEXING");
            assertThat(SegmentState.ACTIVE.toStringHuman()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("Should convert to and from LifecycleState")
        @SuppressWarnings("deprecation")
        void shouldConvertToAndFromLifecycleState() {
            for (SegmentState ss : SegmentState.values()) {
                LifecycleState ls = ss.toLifecycleState();
                assertThat(ls.value()).isEqualTo(ss.getCode());

                SegmentState converted = SegmentState.fromLifecycleState(ls);
                assertThat(converted).isEqualTo(ss);
            }
        }
    }

    @Nested
    @DisplayName("StateMachine Tests")
    class StateMachineTests {

        private StateMachine stateMachine;

        @BeforeEach
        void setUp() {
            stateMachine = new StateMachine(LifecycleState.BOOTSTRAPPING);
        }

        @Test
        @DisplayName("Should initialize with given state")
        void shouldInitializeWithGivenState() {
            assertThat(stateMachine.get()).isEqualTo(LifecycleState.BOOTSTRAPPING);
        }

        @Test
        @DisplayName("Should transition to new state")
        void shouldTransitionToNewState() {
            LifecycleState previous = stateMachine.transitionTo(LifecycleState.INDEXING);

            assertThat(previous).isEqualTo(LifecycleState.BOOTSTRAPPING);
            assertThat(stateMachine.get()).isEqualTo(LifecycleState.INDEXING);
        }

        @Test
        @DisplayName("Should try transition to new state")
        void shouldTryTransitionToNewState() {
            boolean success = stateMachine.tryTransitionTo(LifecycleState.INDEXING);

            assertThat(success).isTrue();
            assertThat(stateMachine.get()).isEqualTo(LifecycleState.INDEXING);
        }

        @Test
        @DisplayName("Should increment transition count")
        void shouldIncrementTransitionCount() {
            assertThat(stateMachine.getTransitionCount()).isEqualTo(0);

            stateMachine.transitionTo(LifecycleState.INDEXING);
            assertThat(stateMachine.getTransitionCount()).isEqualTo(1);

            stateMachine.transitionTo(LifecycleState.WARMING);
            assertThat(stateMachine.getTransitionCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should check if queryable")
        void shouldCheckIfQueryable() {
            stateMachine = new StateMachine(LifecycleState.INDEXING);
            assertThat(stateMachine.isQueryable()).isTrue();

            stateMachine = new StateMachine(LifecycleState.ACTIVE);
            assertThat(stateMachine.isQueryable()).isTrue();

            stateMachine = new StateMachine(LifecycleState.BOOTSTRAPPING);
            assertThat(stateMachine.isQueryable()).isFalse();

            stateMachine = new StateMachine(LifecycleState.TOMBSTONED);
            assertThat(stateMachine.isQueryable()).isFalse();
        }

        @Test
        @DisplayName("Should check if active")
        void shouldCheckIfActive() {
            stateMachine = new StateMachine(LifecycleState.ACTIVE);
            assertThat(stateMachine.isActive()).isTrue();

            stateMachine = new StateMachine(LifecycleState.INDEXING);
            assertThat(stateMachine.isActive()).isFalse();
        }

        @Test
        @DisplayName("Should check if terminal")
        void shouldCheckIfTerminal() {
            stateMachine = new StateMachine(LifecycleState.PURGED);
            assertThat(stateMachine.isTerminal()).isTrue();

            stateMachine = new StateMachine(LifecycleState.ACTIVE);
            assertThat(stateMachine.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("Should reset to new state")
        void shouldResetToNewState() {
            stateMachine.transitionTo(LifecycleState.INDEXING);
            stateMachine.transitionTo(LifecycleState.WARMING);

            stateMachine.reset(LifecycleState.BOOTSTRAPPING);

            assertThat(stateMachine.get()).isEqualTo(LifecycleState.BOOTSTRAPPING);
            assertThat(stateMachine.getTransitionCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should return state code")
        void shouldReturnStateCode() {
            assertThat(stateMachine.getStateCode()).isEqualTo(0);

            stateMachine.transitionTo(LifecycleState.INDEXING);
            assertThat(stateMachine.getStateCode()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should reject null initial state")
        void shouldRejectNullInitialState() {
            assertThatThrownBy(() -> new StateMachine((LifecycleState) null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Should reject null transition target")
        void shouldRejectNullTransitionTarget() {
            assertThatThrownBy(() -> stateMachine.transitionTo(null))
                .isInstanceOf(IllegalArgumentException.class);
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
            assertThat(transition.getState()).isEqualTo(LifecycleState.BOOTSTRAPPING);
        }

        @Test
        @DisplayName("Should initialize with custom initial state")
        void shouldInitializeWithCustomInitialState() {
            transition = new SegmentStateTransition("test-segment", LifecycleState.ACTIVE);

            assertThat(transition.getState()).isEqualTo(LifecycleState.ACTIVE);
        }

        @Test
        @DisplayName("Should transition to new state")
        void shouldTransitionToNewState() {
            LifecycleState previous = transition.transitionTo(LifecycleState.INDEXING);

            assertThat(previous).isEqualTo(LifecycleState.BOOTSTRAPPING);
            assertThat(transition.getState()).isEqualTo(LifecycleState.INDEXING);
        }

        @Test
        @DisplayName("Should try transition to new state")
        void shouldTryTransitionToNewState() {
            boolean success = transition.tryTransitionTo(LifecycleState.INDEXING);

            assertThat(success).isTrue();
            assertThat(transition.getState()).isEqualTo(LifecycleState.INDEXING);
        }

        @Test
        @DisplayName("Should check if queryable")
        void shouldCheckIfQueryable() {
            transition = new SegmentStateTransition("test", LifecycleState.INDEXING);
            assertThat(transition.isQueryable()).isTrue();
        }

        @Test
        @DisplayName("Should check if active")
        void shouldCheckIfActive() {
            transition = new SegmentStateTransition("test", LifecycleState.ACTIVE);
            assertThat(transition.isActive()).isTrue();
        }

        @Test
        @DisplayName("Should check if terminal")
        void shouldCheckIfTerminal() {
            transition = new SegmentStateTransition("test", LifecycleState.PURGED);
            assertThat(transition.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("Should get transition count")
        void shouldGetTransitionCount() {
            assertThat(transition.getTransitionCount()).isEqualTo(0);

            transition.transitionTo(LifecycleState.INDEXING);
            assertThat(transition.getTransitionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should check if transition is valid")
        void shouldCheckIfTransitionIsValid() {
            // BOOTSTRAPPING -> INDEXING is a valid transition
            assertThat(transition.isTransitionValid(LifecycleState.INDEXING)).isTrue();
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
            transition.transitionTo(LifecycleState.INDEXING);
            transition.reset(LifecycleState.ACTIVE);

            assertThat(transition.getState()).isEqualTo(LifecycleState.ACTIVE);
            assertThat(transition.getTransitionCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should record history")
        void shouldRecordHistory() {
            transition.transitionTo(LifecycleState.INDEXING);
            transition.transitionTo(LifecycleState.WARMING);

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
        @SuppressWarnings("deprecation")
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
        @SuppressWarnings("deprecation")
        void shouldCreateFailedResult() {
            StateTransitionResult result = new StateTransitionResult(
                SegmentState.BOOTSTRAPPING, SegmentState.INDEXING, "Invalid transition"
            );

            assertThat(result.isSuccessful()).isFalse();
            assertThat(result.getErrorMessage()).isEqualTo("Invalid transition");
        }

        @Test
        @DisplayName("Should generate string for successful result")
        @SuppressWarnings("deprecation")
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
        @SuppressWarnings("deprecation")
        void shouldGenerateStringForFailedResult() {
            StateTransitionResult result = new StateTransitionResult(
                SegmentState.BOOTSTRAPPING, SegmentState.INDEXING, "Error message"
            );

            assertThat(result.toString()).contains("FAILED");
            assertThat(result.toString()).contains("Error message");
        }

        @Test
        @DisplayName("Should check if has states")
        @SuppressWarnings("deprecation")
        void shouldCheckIfHasStates() {
            StateTransitionResult result = new StateTransitionResult(
                SegmentState.BOOTSTRAPPING, SegmentState.INDEXING
            );

            assertThat(result.hasStates()).isTrue();
        }
    }

    @Nested
    @DisplayName("State Validation Tests")
    class StateValidationTests {

        @Test
        @DisplayName("Should validate LifecycleState as valid state")
        void shouldValidateLifecycleStateAsValidState() {
            assertThat(StateMachine.isValidState(LifecycleState.ACTIVE)).isTrue();
            assertThat(StateMachine.isValidState(LifecycleState.INDEXING)).isTrue();
            assertThat(StateMachine.isValidState(LifecycleState.BOOTSTRAPPING)).isTrue();
        }

        @Test
        @DisplayName("Should reject non-LifecycleState objects")
        void shouldRejectNonLifecycleStateObjects() {
            assertThat(StateMachine.isValidState("ACTIVE")).isFalse();
            assertThat(StateMachine.isValidState(1)).isFalse();
            assertThat(StateMachine.isValidState(null)).isFalse();
        }

        @Test
        @DisplayName("Should require valid state without exception")
        void shouldRequireValidStateWithoutException() {
            // Should not throw
            StateMachine.requireValidState(LifecycleState.ACTIVE);
            StateMachine.requireValidState(LifecycleState.INDEXING);
        }

        @Test
        @DisplayName("Should throw on invalid state")
        void shouldThrowOnInvalidState() {
            assertThatThrownBy(() -> StateMachine.requireValidState("ACTIVE"))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> StateMachine.requireValidState(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
