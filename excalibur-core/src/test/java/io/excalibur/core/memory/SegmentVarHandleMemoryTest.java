package io.excalibur.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for SegmentVarHandle.
 */
@DisplayName("SegmentVarHandle Tests")
class SegmentVarHandleMemoryTest {

    @Nested
    @DisplayName("GetVarHandle Tests")
    class GetVarHandleTests {

        @Test
        @DisplayName("Should reject non-existent field")
        void shouldRejectNonExistentField() {
            assertThatThrownBy(() -> SegmentVarHandle.getVarHandle(String.class, "nonexistent"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to create VarHandle");
        }

        @Test
        @DisplayName("Should reject null class")
        void shouldRejectNullClass() {
            assertThatThrownBy(() -> SegmentVarHandle.getVarHandle(null, "value"))
                .isInstanceOf(NullPointerException.class);
        }
    }
}
