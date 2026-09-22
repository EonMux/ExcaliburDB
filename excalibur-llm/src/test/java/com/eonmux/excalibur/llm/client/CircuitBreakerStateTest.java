package com.eonmux.excalibur.llm.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CircuitBreakerState}.
 *
 * <p>Tests verify:</p>
 * <ul>
 *   <li>All three states exist: CLOSED, OPEN, HALF_OPEN</li>
 *   <li>Helper methods return correct values for each state</li>
 *   <li>Domain correctness of state semantics</li>
 * </ul>
 */
@Timeout(value = 10)
class CircuitBreakerStateTest {

    @Nested
    @DisplayName("State Existence")
    class StateExistenceTests {

        @Test
        @DisplayName("CLOSED state exists")
        void closedStateExists() {
            assertNotNull(CircuitBreakerState.CLOSED);
        }

        @Test
        @DisplayName("OPEN state exists")
        void openStateExists() {
            assertNotNull(CircuitBreakerState.OPEN);
        }

        @Test
        @DisplayName("HALF_OPEN state exists")
        void halfOpenStateExists() {
            assertNotNull(CircuitBreakerState.HALF_OPEN);
        }

        @Test
        @DisplayName("Exactly three states exist")
        void exactlyThreeStatesExist() {
            CircuitBreakerState[] states = CircuitBreakerState.values();
            assertEquals(3, states.length);
        }
    }

    @Nested
    @DisplayName("allowsRequests() method")
    class AllowsRequestsTests {

        @Test
        @DisplayName("CLOSED allows requests")
        void closedAllowsRequests() {
            assertTrue(CircuitBreakerState.CLOSED.allowsRequests());
        }

        @Test
        @DisplayName("OPEN blocks requests")
        void openBlocksRequests() {
            assertFalse(CircuitBreakerState.OPEN.allowsRequests());
        }

        @Test
        @DisplayName("HALF_OPEN allows requests")
        void halfOpenAllowsRequests() {
            assertTrue(CircuitBreakerState.HALF_OPEN.allowsRequests());
        }
    }

    @Nested
    @DisplayName("isHealthy() method")
    class IsHealthyTests {

        @Test
        @DisplayName("CLOSED is healthy")
        void closedIsHealthy() {
            assertTrue(CircuitBreakerState.CLOSED.isHealthy());
        }

        @Test
        @DisplayName("OPEN is not healthy")
        void openIsNotHealthy() {
            assertFalse(CircuitBreakerState.OPEN.isHealthy());
        }

        @Test
        @DisplayName("HALF_OPEN is not healthy")
        void halfOpenIsNotHealthy() {
            assertFalse(CircuitBreakerState.HALF_OPEN.isHealthy());
        }
    }

    @Nested
    @DisplayName("isRecovering() method")
    class IsRecoveringTests {

        @Test
        @DisplayName("CLOSED is not recovering")
        void closedIsNotRecovering() {
            assertFalse(CircuitBreakerState.CLOSED.isRecovering());
        }

        @Test
        @DisplayName("OPEN is not recovering")
        void openIsNotRecovering() {
            assertFalse(CircuitBreakerState.OPEN.isRecovering());
        }

        @Test
        @DisplayName("HALF_OPEN is recovering")
        void halfOpenIsRecovering() {
            assertTrue(CircuitBreakerState.HALF_OPEN.isRecovering());
        }
    }

    @Nested
    @DisplayName("isTripped() method")
    class IsTrippedTests {

        @Test
        @DisplayName("CLOSED is not tripped")
        void closedIsNotTripped() {
            assertFalse(CircuitBreakerState.CLOSED.isTripped());
        }

        @Test
        @DisplayName("OPEN is tripped")
        void openIsTripped() {
            assertTrue(CircuitBreakerState.OPEN.isTripped());
        }

        @Test
        @DisplayName("HALF_OPEN is not tripped")
        void halfOpenIsNotTripped() {
            assertFalse(CircuitBreakerState.HALF_OPEN.isTripped());
        }
    }

    @Nested
    @DisplayName("Domain Correctness")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("Only OPEN state blocks requests")
        void onlyOpenBlocksRequests() {
            int blockingCount = 0;
            for (CircuitBreakerState state : CircuitBreakerState.values()) {
                if (!state.allowsRequests()) {
                    blockingCount++;
                    assertEquals(CircuitBreakerState.OPEN, state,
                        "Only OPEN state should block requests");
                }
            }
            assertEquals(1, blockingCount, "Exactly one state should block requests");
        }

        @Test
        @DisplayName("Only CLOSED state is healthy")
        void onlyClosedIsHealthy() {
            int healthyCount = 0;
            for (CircuitBreakerState state : CircuitBreakerState.values()) {
                if (state.isHealthy()) {
                    healthyCount++;
                    assertEquals(CircuitBreakerState.CLOSED, state,
                        "Only CLOSED state should be healthy");
                }
            }
            assertEquals(1, healthyCount, "Exactly one state should be healthy");
        }

        @Test
        @DisplayName("Only HALF_OPEN state is recovering")
        void onlyHalfOpenIsRecovering() {
            int recoveringCount = 0;
            for (CircuitBreakerState state : CircuitBreakerState.values()) {
                if (state.isRecovering()) {
                    recoveringCount++;
                    assertEquals(CircuitBreakerState.HALF_OPEN, state,
                        "Only HALF_OPEN state should be recovering");
                }
            }
            assertEquals(1, recoveringCount, "Exactly one state should be recovering");
        }

        @Test
        @DisplayName("Only OPEN state is tripped")
        void onlyOpenIsTripped() {
            int trippedCount = 0;
            for (CircuitBreakerState state : CircuitBreakerState.values()) {
                if (state.isTripped()) {
                    trippedCount++;
                    assertEquals(CircuitBreakerState.OPEN, state,
                        "Only OPEN state should be tripped");
                }
            }
            assertEquals(1, trippedCount, "Exactly one state should be tripped");
        }

        @ParameterizedTest
        @EnumSource(CircuitBreakerState.class)
        @DisplayName("All states have consistent semantics")
        void allStatesHaveConsistentSemantics(CircuitBreakerState state) {
            // If state allows requests, it should either be healthy or recovering
            if (state.allowsRequests()) {
                assertTrue(state.isHealthy() || state.isRecovering(),
                    state + " allows requests but is neither healthy nor recovering");
            }

            // If state is tripped, it should not allow requests
            if (state.isTripped()) {
                assertFalse(state.allowsRequests(),
                    state + " is tripped but allows requests");
            }

            // If state is healthy, it should allow requests
            if (state.isHealthy()) {
                assertTrue(state.allowsRequests(),
                    state + " is healthy but does not allow requests");
            }
        }
    }

    @Nested
    @DisplayName("Enum Contract")
    class EnumContractTests {

        @Test
        @DisplayName("valueOf() works for all states")
        void valueOfWorksForAllStates() {
            assertEquals(CircuitBreakerState.CLOSED, CircuitBreakerState.valueOf("CLOSED"));
            assertEquals(CircuitBreakerState.OPEN, CircuitBreakerState.valueOf("OPEN"));
            assertEquals(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.valueOf("HALF_OPEN"));
        }

        @Test
        @DisplayName("ordinal() values are sequential starting from 0")
        void ordinalValuesAreSequential() {
            assertEquals(0, CircuitBreakerState.CLOSED.ordinal());
            assertEquals(1, CircuitBreakerState.OPEN.ordinal());
            assertEquals(2, CircuitBreakerState.HALF_OPEN.ordinal());
        }

        @Test
        @DisplayName("name() returns expected values")
        void nameReturnsExpectedValues() {
            assertEquals("CLOSED", CircuitBreakerState.CLOSED.name());
            assertEquals("OPEN", CircuitBreakerState.OPEN.name());
            assertEquals("HALF_OPEN", CircuitBreakerState.HALF_OPEN.name());
        }
    }
}
