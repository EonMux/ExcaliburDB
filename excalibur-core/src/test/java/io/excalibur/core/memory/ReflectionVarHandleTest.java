package io.excalibur.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ReflectionVarHandle.
 */
@DisplayName("ReflectionVarHandle Tests")
class ReflectionVarHandleTest {

    @Nested
    @DisplayName("GetVarHandle Tests")
    class GetVarHandleTests {

        @Test
        @DisplayName("Should reject non-existent field")
        void shouldRejectNonExistentField() {
            assertThatThrownBy(() -> ReflectionVarHandle.getVarHandle(String.class, "nonexistent"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to create VarHandle");
        }

        @Test
        @DisplayName("Should reject null class")
        void shouldRejectNullClass() {
            assertThatThrownBy(() -> ReflectionVarHandle.getVarHandle(null, "value"))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject null field name")
        void shouldRejectNullFieldName() {
            assertThatThrownBy(() -> ReflectionVarHandle.getVarHandle(String.class, null))
                .isInstanceOf(NullPointerException.class);
        }
    }
}
