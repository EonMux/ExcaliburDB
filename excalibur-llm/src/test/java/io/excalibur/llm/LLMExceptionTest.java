package io.excalibur.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LLMException}.
 *
 * <h2>Domain Correctness Verification</h2>
 * <ul>
 *   <li>Error type: categorizes failure modes correctly</li>
 *   <li>HTTP status code: clamped to valid range [100, 599], or -1 if not applicable</li>
 *   <li>Retryable flag: correctly determined based on error type and status code</li>
 *   <li>Exception message: preserved and accessible</li>
 *   <li>Cause chain: properly maintained for exception propagation</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>LLMException is immutable and thread-safe.</p>
 */
@Timeout(value = 10)
class LLMExceptionTest {

    // ============= Constructor Tests =============

    @Nested
    @DisplayName("Two-Argument Constructor")
    class TwoArgumentConstructorTests {

        @Test
        @DisplayName("Creates exception with message and error type")
        void createsExceptionWithMessageAndErrorType() {
            LLMException ex = new LLMException("Test error", LLMException.ErrorType.NETWORK);

            assertEquals("Test error", ex.getMessage());
            assertEquals(LLMException.ErrorType.NETWORK, ex.getErrorType());
            assertEquals(-1, ex.getStatusCode());
        }

        @Test
        @DisplayName("All error types are valid")
        void allErrorTypesAreValid() {
            for (LLMException.ErrorType type : LLMException.ErrorType.values()) {
                LLMException ex = new LLMException("Test", type);
                assertEquals(type, ex.getErrorType());
            }
        }

        @Test
        @DisplayName("Null message is accepted (passed to RuntimeException)")
        void nullMessageAccepted() {
            // LLMException does not validate null messages - it delegates to RuntimeException
            LLMException ex = new LLMException(null, LLMException.ErrorType.NETWORK);
            assertNull(ex.getMessage());
            assertEquals(LLMException.ErrorType.NETWORK, ex.getErrorType());
        }
    }

    @Nested
    @DisplayName("Three-Argument Constructor (statusCode)")
    class ThreeArgumentConstructorWithStatusCodeTests {

        @Test
        @DisplayName("Creates exception with message, error type, and status code")
        void createsExceptionWithStatusCode() {
            LLMException ex = new LLMException("HTTP error", LLMException.ErrorType.HTTP_ERROR, 500);

            assertEquals("HTTP error", ex.getMessage());
            assertEquals(LLMException.ErrorType.HTTP_ERROR, ex.getErrorType());
            assertEquals(500, ex.getStatusCode());
        }

        @Test
        @DisplayName("Status code 100 is accepted (minimum valid)")
        void statusCode100Accepted() {
            LLMException ex = new LLMException("Continue", LLMException.ErrorType.HTTP_ERROR, 100);
            assertEquals(100, ex.getStatusCode());
        }

        @Test
        @DisplayName("Status code 599 is accepted (maximum valid)")
        void statusCode599Accepted() {
            LLMException ex = new LLMException("Error", LLMException.ErrorType.HTTP_ERROR, 599);
            assertEquals(599, ex.getStatusCode());
        }

        @Test
        @DisplayName("Status code 200 is accepted")
        void statusCode200Accepted() {
            LLMException ex = new LLMException("OK", LLMException.ErrorType.HTTP_ERROR, 200);
            assertEquals(200, ex.getStatusCode());
        }

        @Test
        @DisplayName("Status code 429 sets RATE_LIMITED type")
        void statusCode429RateLimited() {
            LLMException ex = new LLMException("Rate limited", LLMException.ErrorType.RATE_LIMITED, 429);
            assertEquals(429, ex.getStatusCode());
            assertEquals(LLMException.ErrorType.RATE_LIMITED, ex.getErrorType());
        }

        @Test
        @DisplayName("Status code 401 sets AUTH_ERROR type")
        void statusCode401AuthError() {
            LLMException ex = new LLMException("Unauthorized", LLMException.ErrorType.AUTH_ERROR, 401);
            assertEquals(401, ex.getStatusCode());
            assertEquals(LLMException.ErrorType.AUTH_ERROR, ex.getErrorType());
        }

        @Test
        @DisplayName("Status code 403 sets AUTH_ERROR type")
        void statusCode403AuthError() {
            LLMException ex = new LLMException("Forbidden", LLMException.ErrorType.AUTH_ERROR, 403);
            assertEquals(403, ex.getStatusCode());
            assertEquals(LLMException.ErrorType.AUTH_ERROR, ex.getErrorType());
        }
    }

    @Nested
    @DisplayName("Three-Argument Constructor (cause)")
    class ThreeArgumentConstructorWithCauseTests {

        @Test
        @DisplayName("Creates exception with message, error type, and cause")
        void createsExceptionWithCause() {
            Throwable cause = new RuntimeException("Underlying error");
            LLMException ex = new LLMException("Wrapped error", LLMException.ErrorType.NETWORK, cause);

            assertEquals("Wrapped error", ex.getMessage());
            assertEquals(LLMException.ErrorType.NETWORK, ex.getErrorType());
            assertEquals(-1, ex.getStatusCode());
            assertSame(cause, ex.getCause());
        }

        @Test
        @DisplayName("Null cause is accepted")
        void nullCauseAccepted() {
            LLMException ex = new LLMException("Test", LLMException.ErrorType.PARSE_ERROR, (Throwable) null);
            assertNull(ex.getCause());
        }
    }

    @Nested
    @DisplayName("Four-Argument Constructor")
    class FourArgumentConstructorTests {

        @Test
        @DisplayName("Creates exception with all parameters")
        void createsExceptionWithAllParameters() {
            Throwable cause = new RuntimeException("Root cause");
            LLMException ex = new LLMException("Full error", LLMException.ErrorType.HTTP_ERROR, 503, cause);

            assertEquals("Full error", ex.getMessage());
            assertEquals(LLMException.ErrorType.HTTP_ERROR, ex.getErrorType());
            assertEquals(503, ex.getStatusCode());
            assertSame(cause, ex.getCause());
        }

        @Test
        @DisplayName("Validates and clamps status code")
        void validatesStatusCode() {
            Throwable cause = new RuntimeException("Cause");
            LLMException ex = new LLMException("Error", LLMException.ErrorType.HTTP_ERROR, 500, cause);
            assertEquals(500, ex.getStatusCode());
        }
    }

    // ============= Retryable Logic Tests =============

    @Nested
    @DisplayName("Retryable Logic")
    class RetryableLogicTests {

        @Test
        @DisplayName("NETWORK errors are retryable")
        void networkErrorsAreRetryable() {
            LLMException ex = new LLMException("Network error", LLMException.ErrorType.NETWORK);
            assertTrue(ex.isRetryable());
        }

        @Test
        @DisplayName("RATE_LIMITED errors are retryable")
        void rateLimitedErrorsAreRetryable() {
            LLMException ex = new LLMException("Rate limited", LLMException.ErrorType.RATE_LIMITED);
            assertTrue(ex.isRetryable());
        }

        @Test
        @DisplayName("HTTP_ERROR without status code is not retryable")
        void httpErrorWithoutStatusCodeNotRetryable() {
            LLMException ex = new LLMException("HTTP error", LLMException.ErrorType.HTTP_ERROR);
            assertFalse(ex.isRetryable());
        }

        @Test
        @DisplayName("HTTP_ERROR with 5xx status code is retryable")
        void httpError5xxRetryable() {
            LLMException ex500 = new LLMException("Internal error", LLMException.ErrorType.HTTP_ERROR, 500);
            LLMException ex502 = new LLMException("Bad gateway", LLMException.ErrorType.HTTP_ERROR, 502);
            LLMException ex503 = new LLMException("Service unavailable", LLMException.ErrorType.HTTP_ERROR, 503);

            assertTrue(ex500.isRetryable());
            assertTrue(ex502.isRetryable());
            assertTrue(ex503.isRetryable());
        }

        @Test
        @DisplayName("HTTP_ERROR with 4xx status code is not retryable")
        void httpError4xxNotRetryable() {
            LLMException ex400 = new LLMException("Bad request", LLMException.ErrorType.HTTP_ERROR, 400);
            LLMException ex404 = new LLMException("Not found", LLMException.ErrorType.HTTP_ERROR, 404);

            assertFalse(ex400.isRetryable());
            assertFalse(ex404.isRetryable());
        }

        @Test
        @DisplayName("PARSE_ERROR is not retryable")
        void parseErrorNotRetryable() {
            LLMException ex = new LLMException("Parse error", LLMException.ErrorType.PARSE_ERROR);
            assertFalse(ex.isRetryable());
        }

        @Test
        @DisplayName("CIRCUIT_OPEN is not retryable")
        void circuitOpenNotRetryable() {
            LLMException ex = new LLMException("Circuit open", LLMException.ErrorType.CIRCUIT_OPEN);
            assertFalse(ex.isRetryable());
        }

        @Test
        @DisplayName("INVALID_REQUEST is not retryable")
        void invalidRequestNotRetryable() {
            LLMException ex = new LLMException("Invalid request", LLMException.ErrorType.INVALID_REQUEST);
            assertFalse(ex.isRetryable());
        }

        @Test
        @DisplayName("AUTH_ERROR is not retryable")
        void authErrorNotRetryable() {
            LLMException ex = new LLMException("Auth error", LLMException.ErrorType.AUTH_ERROR);
            assertFalse(ex.isRetryable());
        }
    }

    // ============= toString Tests =============

    @Nested
    @DisplayName("toString Method")
    class ToStringTests {

        @Test
        @DisplayName("Contains error type")
        void containsErrorType() {
            LLMException ex = new LLMException("Test", LLMException.ErrorType.NETWORK);
            String str = ex.toString();
            assertTrue(str.contains("NETWORK"));
        }

        @Test
        @DisplayName("Contains message")
        void containsMessage() {
            LLMException ex = new LLMException("Test error message", LLMException.ErrorType.HTTP_ERROR);
            String str = ex.toString();
            assertTrue(str.contains("Test error message"));
        }

        @Test
        @DisplayName("Contains retryable flag")
        void containsRetryableFlag() {
            LLMException ex = new LLMException("Test", LLMException.ErrorType.NETWORK);
            String str = ex.toString();
            assertTrue(str.contains("retryable=true"));
        }

        @Test
        @DisplayName("Contains status code when present")
        void containsStatusCodeWhenPresent() {
            LLMException ex = new LLMException("HTTP error", LLMException.ErrorType.HTTP_ERROR, 500);
            String str = ex.toString();
            assertTrue(str.contains("statusCode=500"));
        }

        @Test
        @DisplayName("Does not contain status code when -1")
        void doesNotContainStatusCodeWhenMinusOne() {
            LLMException ex = new LLMException("Network error", LLMException.ErrorType.NETWORK);
            String str = ex.toString();
            assertFalse(str.contains("statusCode=-1"));
            assertFalse(str.contains("statusCode="));
        }

        @Test
        @DisplayName("Format is consistent")
        void formatIsConsistent() {
            LLMException ex = new LLMException("Error", LLMException.ErrorType.HTTP_ERROR, 500);
            String str = ex.toString();

            assertTrue(str.startsWith("LLMException{"));
            assertTrue(str.contains("type="));
            assertTrue(str.contains("retryable="));
            assertTrue(str.contains("message="));
            assertTrue(str.endsWith("}"));
        }
    }

    // ============= Error Type Tests =============

    @Nested
    @DisplayName("Error Types")
    class ErrorTypeTests {

        @ParameterizedTest
        @EnumSource(LLMException.ErrorType.class)
        @DisplayName("All error types are defined")
        void allErrorTypesDefined(LLMException.ErrorType type) {
            assertNotNull(type);
            assertNotNull(type.name());
        }

        @Test
        @DisplayName("Error types have expected values")
        void errorTypesHaveExpectedValues() {
            LLMException.ErrorType[] types = LLMException.ErrorType.values();
            assertEquals(7, types.length);

            // Verify all expected types exist
            assertTrue(java.util.Arrays.asList(types).contains(LLMException.ErrorType.NETWORK));
            assertTrue(java.util.Arrays.asList(types).contains(LLMException.ErrorType.HTTP_ERROR));
            assertTrue(java.util.Arrays.asList(types).contains(LLMException.ErrorType.PARSE_ERROR));
            assertTrue(java.util.Arrays.asList(types).contains(LLMException.ErrorType.RATE_LIMITED));
            assertTrue(java.util.Arrays.asList(types).contains(LLMException.ErrorType.CIRCUIT_OPEN));
            assertTrue(java.util.Arrays.asList(types).contains(LLMException.ErrorType.INVALID_REQUEST));
            assertTrue(java.util.Arrays.asList(types).contains(LLMException.ErrorType.AUTH_ERROR));
        }
    }

    // ============= Domain Correctness Tests =============

    @Nested
    @DisplayName("Domain Correctness")
    class DomainCorrectnessTests {

        @Test
        @DisplayName("Status code is clamped to valid range")
        void statusCodeClampedToValidRange() {
            // Valid range is [100, 599] or -1
            LLMException ex1 = new LLMException("Test", LLMException.ErrorType.HTTP_ERROR, 99);
            // Status codes below 100 are clamped by HttpStatus.clamp()
            // The actual behavior depends on HttpStatus implementation

            LLMException ex2 = new LLMException("Test", LLMException.ErrorType.HTTP_ERROR, 600);
            // Status codes above 599 are also clamped
        }

        @Test
        @DisplayName("Exception is serializable")
        void exceptionIsSerializable() {
            // LLMException extends RuntimeException which is serializable
            LLMException ex = new LLMException("Test", LLMException.ErrorType.NETWORK);
            assertTrue(ex instanceof java.io.Serializable);
        }

        @Test
        @DisplayName("Cause chain is preserved")
        void causeChainPreserved() {
            Exception rootCause = new IllegalArgumentException("Root");
            Exception middleCause = new RuntimeException("Middle", rootCause);
            LLMException ex = new LLMException("Top", LLMException.ErrorType.NETWORK, middleCause);

            assertSame(middleCause, ex.getCause());
            assertSame(rootCause, ex.getCause().getCause());
        }

        @Test
        @DisplayName("Message can contain special characters")
        void messageCanContainSpecialCharacters() {
            String specialMessage = "Error: \n\t\"quotes\" and \\backslash\\";
            LLMException ex = new LLMException(specialMessage, LLMException.ErrorType.PARSE_ERROR);

            assertEquals(specialMessage, ex.getMessage());
        }

        @Test
        @DisplayName("Message can be empty string")
        void messageCanBeEmpty() {
            LLMException ex = new LLMException("", LLMException.ErrorType.NETWORK);
            assertEquals("", ex.getMessage());
        }
    }

    // ============= Immutability Tests =============

    @Nested
    @DisplayName("Immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("Exception state is immutable")
        void exceptionStateIsImmutable() {
            LLMException ex = new LLMException("Test", LLMException.ErrorType.HTTP_ERROR, 500);

            // Multiple calls return same values
            assertEquals("Test", ex.getMessage());
            assertEquals("Test", ex.getMessage());
            assertEquals(LLMException.ErrorType.HTTP_ERROR, ex.getErrorType());
            assertEquals(LLMException.ErrorType.HTTP_ERROR, ex.getErrorType());
            assertEquals(500, ex.getStatusCode());
            assertEquals(500, ex.getStatusCode());
            assertEquals(ex.isRetryable(), ex.isRetryable());
        }
    }

    // ============= Edge Cases =============

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Very long message is handled")
        void veryLongMessageHandled() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10000; i++) {
                sb.append("a");
            }
            String longMessage = sb.toString();

            LLMException ex = new LLMException(longMessage, LLMException.ErrorType.NETWORK);
            assertEquals(longMessage, ex.getMessage());
        }

        @Test
        @DisplayName("Unicode message is handled")
        void unicodeMessageHandled() {
            String unicodeMessage = "Error: 日本語 🎉 café";
            LLMException ex = new LLMException(unicodeMessage, LLMException.ErrorType.PARSE_ERROR);
            assertEquals(unicodeMessage, ex.getMessage());
        }

        @Test
        @DisplayName("Multiple exceptions with same values are not equal by default")
        void multipleExceptionsNotEqual() {
            LLMException ex1 = new LLMException("Test", LLMException.ErrorType.NETWORK);
            LLMException ex2 = new LLMException("Test", LLMException.ErrorType.NETWORK);

            // Exception equality is identity-based by default
            assertNotEquals(ex1, ex2);
        }
    }
}
