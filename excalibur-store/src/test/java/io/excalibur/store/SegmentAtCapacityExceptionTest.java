package io.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for SegmentAtCapacityException.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>Exception message handling</li>
 *   <li>Exception cause chaining</li>
 *   <li>Domain correctness of error messages</li>
 * </ul>
 */
@DisplayName("SegmentAtCapacityException Tests")
class SegmentAtCapacityExceptionTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create exception with message")
        void shouldCreateExceptionWithMessage() {
            String message = "Segment 0 has reached maximum capacity";
            SegmentAtCapacityException exception = new SegmentAtCapacityException(message);

            assertThat(exception.getMessage()).isEqualTo(message);
        }

        @Test
        @DisplayName("Should create exception with message and cause")
        void shouldCreateExceptionWithMessageAndCause() {
            String message = "Segment capacity exceeded";
            Throwable cause = new IllegalStateException("Underlying issue");
            SegmentAtCapacityException exception = new SegmentAtCapacityException(message, cause);

            assertThat(exception.getMessage()).isEqualTo(message);
            assertThat(exception.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("Should accept null message (delegates to super)")
        void shouldAcceptNullMessage() {
            // RuntimeException allows null message
            SegmentAtCapacityException exception = new SegmentAtCapacityException(null);
            assertThat(exception.getMessage()).isNull();
        }

        @Test
        @DisplayName("Should handle null cause")
        void shouldHandleNullCause() {
            SegmentAtCapacityException exception = new SegmentAtCapacityException("message", null);
            assertThat(exception.getMessage()).isEqualTo("message");
            assertThat(exception.getCause()).isNull();
        }
    }

    @Nested
    @DisplayName("Domain Correctness Tests")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("Should include segment ID in message")
        void shouldIncludeSegmentIdInMessage() {
            long segmentId = 42L;
            SegmentAtCapacityException exception = new SegmentAtCapacityException(
                "Segment " + segmentId + " has reached capacity limit");

            assertThat(exception.getMessage()).contains(String.valueOf(segmentId));
        }

        @Test
        @DisplayName("Should be runtime exception")
        void shouldBeRuntimeException() {
            SegmentAtCapacityException exception = new SegmentAtCapacityException("test");
            assertThat(exception).isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("Should be serializable")
        void shouldBeSerializable() {
            SegmentAtCapacityException original = new SegmentAtCapacityException("test message");
            assertThat(original.getMessage()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("Inheritance Tests")
    class InheritanceTests {

        @Test
        @DisplayName("Should extend RuntimeException")
        void shouldExtendRuntimeException() {
            SegmentAtCapacityException exception = new SegmentAtCapacityException("test");
            assertThat(exception).isInstanceOf(RuntimeException.class);
        }
    }
}
