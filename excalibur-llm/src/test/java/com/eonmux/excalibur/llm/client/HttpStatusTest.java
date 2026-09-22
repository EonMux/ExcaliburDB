package com.eonmux.excalibur.llm.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HttpStatus}.
 *
 * <h2>Domain Correctness Verification</h2>
 * <ul>
 *   <li>Valid HTTP status codes: [100, 599]</li>
 *   <li>Invalid codes: below 100 or above 599</li>
 *   <li>Classification: 1xx informational, 2xx success, 3xx redirect, 4xx client error, 5xx server error</li>
 *   <li>Retryable codes: 408, 429, and all 5xx</li>
 * </ul>
 */
@Timeout(value = 10)
class HttpStatusTest {

    // ============= isValid Tests =============

    @Test
    @DisplayName("isValid returns true for minimum valid status code (100)")
    void isValid_minimumCode_returnsTrue() {
        assertTrue(HttpStatus.isValid(100));
    }

    @Test
    @DisplayName("isValid returns true for maximum valid status code (599)")
    void isValid_maximumCode_returnsTrue() {
        assertTrue(HttpStatus.isValid(599));
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 101, 200, 201, 204, 301, 400, 404, 500, 503, 599})
    @DisplayName("isValid returns true for valid status codes")
    void isValid_validCodes_returnsTrue(int statusCode) {
        assertTrue(HttpStatus.isValid(statusCode));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 99, 600, 601, 1000, Integer.MIN_VALUE, Integer.MAX_VALUE})
    @DisplayName("isValid returns false for invalid status codes")
    void isValid_invalidCodes_returnsFalse(int statusCode) {
        assertFalse(HttpStatus.isValid(statusCode));
    }

    @Test
    @DisplayName("isValid returns false for code just below minimum (99)")
    void isValid_justBelowMinimum_returnsFalse() {
        assertFalse(HttpStatus.isValid(99));
    }

    @Test
    @DisplayName("isValid returns false for code just above maximum (600)")
    void isValid_justAboveMaximum_returnsFalse() {
        assertFalse(HttpStatus.isValid(600));
    }

    // ============= validate Tests =============

    @Test
    @DisplayName("validate does not throw for valid status codes")
    void validate_validCode_doesNotThrow() {
        assertDoesNotThrow(() -> HttpStatus.validate(200));
        assertDoesNotThrow(() -> HttpStatus.validate(100));
        assertDoesNotThrow(() -> HttpStatus.validate(599));
    }

    @Test
    @DisplayName("validate throws IllegalArgumentException for invalid status codes")
    void validate_invalidCode_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> HttpStatus.validate(99)
        );
        assertTrue(ex.getMessage().contains("99"));
        assertTrue(ex.getMessage().contains("100"));
        assertTrue(ex.getMessage().contains("599"));
    }

    @Test
    @DisplayName("validate throws for status code 600")
    void validate_code600_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> HttpStatus.validate(600)
        );
        assertTrue(ex.getMessage().contains("600"));
    }

    @Test
    @DisplayName("validate throws for negative status code")
    void validate_negativeCode_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> HttpStatus.validate(-1));
        assertThrows(IllegalArgumentException.class, () -> HttpStatus.validate(-100));
    }

    // ============= isInformational Tests =============

    @ParameterizedTest
    @ValueSource(ints = {100, 101, 102, 150, 199})
    @DisplayName("isInformational returns true for 1xx codes")
    void isInformational_1xxCodes_returnsTrue(int statusCode) {
        assertTrue(HttpStatus.isInformational(statusCode));
    }

    @ParameterizedTest
    @ValueSource(ints = {99, 200, 300, 400, 500})
    @DisplayName("isInformational returns false for non-1xx codes")
    void isInformational_non1xxCodes_returnsFalse(int statusCode) {
        assertFalse(HttpStatus.isInformational(statusCode));
    }

    // ============= isSuccess Tests =============

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 202, 204, 250, 299})
    @DisplayName("isSuccess returns true for 2xx codes")
    void isSuccess_2xxCodes_returnsTrue(int statusCode) {
        assertTrue(HttpStatus.isSuccess(statusCode));
    }

    @ParameterizedTest
    @ValueSource(ints = {199, 300, 400, 500})
    @DisplayName("isSuccess returns false for non-2xx codes")
    void isSuccess_non2xxCodes_returnsFalse(int statusCode) {
        assertFalse(HttpStatus.isSuccess(statusCode));
    }

    // ============= isRedirection Tests =============

    @ParameterizedTest
    @ValueSource(ints = {300, 301, 302, 304, 307, 308, 350, 399})
    @DisplayName("isRedirection returns true for 3xx codes")
    void isRedirection_3xxCodes_returnsTrue(int statusCode) {
        assertTrue(HttpStatus.isRedirection(statusCode));
    }

    @ParameterizedTest
    @ValueSource(ints = {299, 400, 500})
    @DisplayName("isRedirection returns false for non-3xx codes")
    void isRedirection_non3xxCodes_returnsFalse(int statusCode) {
        assertFalse(HttpStatus.isRedirection(statusCode));
    }

    // ============= isClientError Tests =============

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 429, 450, 499})
    @DisplayName("isClientError returns true for 4xx codes")
    void isClientError_4xxCodes_returnsTrue(int statusCode) {
        assertTrue(HttpStatus.isClientError(statusCode));
    }

    @ParameterizedTest
    @ValueSource(ints = {399, 500, 200})
    @DisplayName("isClientError returns false for non-4xx codes")
    void isClientError_non4xxCodes_returnsFalse(int statusCode) {
        assertFalse(HttpStatus.isClientError(statusCode));
    }

    // ============= isServerError Tests =============

    @ParameterizedTest
    @ValueSource(ints = {500, 501, 502, 503, 504, 550, 599})
    @DisplayName("isServerError returns true for 5xx codes")
    void isServerError_5xxCodes_returnsTrue(int statusCode) {
        assertTrue(HttpStatus.isServerError(statusCode));
    }

    @ParameterizedTest
    @ValueSource(ints = {499, 400, 200})
    @DisplayName("isServerError returns false for non-5xx codes")
    void isServerError_non5xxCodes_returnsFalse(int statusCode) {
        assertFalse(HttpStatus.isServerError(statusCode));
    }

    // ============= isError Tests =============

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 429, 500, 503, 599})
    @DisplayName("isError returns true for 4xx and 5xx codes")
    void isError_errorCodes_returnsTrue(int statusCode) {
        assertTrue(HttpStatus.isError(statusCode));
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 200, 201, 300, 301, 399})
    @DisplayName("isError returns false for non-error codes")
    void isError_nonErrorCodes_returnsFalse(int statusCode) {
        assertFalse(HttpStatus.isError(statusCode));
    }

    // ============= isRetryable Tests =============

    @ParameterizedTest
    @ValueSource(ints = {408, 429})
    @DisplayName("isRetryable returns true for specific 4xx retryable codes")
    void isRetryable_retryable4xx_returnsTrue(int statusCode) {
        assertTrue(HttpStatus.isRetryable(statusCode));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504, 599})
    @DisplayName("isRetryable returns true for all 5xx server errors")
    void isRetryable_serverErrors_returnsTrue(int statusCode) {
        assertTrue(HttpStatus.isRetryable(statusCode));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 405, 410, 415})
    @DisplayName("isRetryable returns false for non-retryable 4xx errors")
    void isRetryable_nonRetryable4xx_returnsFalse(int statusCode) {
        assertFalse(HttpStatus.isRetryable(statusCode));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 300, 301, 100})
    @DisplayName("isRetryable returns false for success/redirect codes")
    void isRetryable_successCodes_returnsFalse(int statusCode) {
        assertFalse(HttpStatus.isRetryable(statusCode));
    }

    // ============= getStatusClass Tests =============

    static Stream<Arguments> statusClassProvider() {
        return Stream.of(
            Arguments.of(100, HttpStatus.StatusClass.INFORMATIONAL),
            Arguments.of(150, HttpStatus.StatusClass.INFORMATIONAL),
            Arguments.of(199, HttpStatus.StatusClass.INFORMATIONAL),
            Arguments.of(200, HttpStatus.StatusClass.SUCCESS),
            Arguments.of(204, HttpStatus.StatusClass.SUCCESS),
            Arguments.of(299, HttpStatus.StatusClass.SUCCESS),
            Arguments.of(300, HttpStatus.StatusClass.REDIRECTION),
            Arguments.of(304, HttpStatus.StatusClass.REDIRECTION),
            Arguments.of(399, HttpStatus.StatusClass.REDIRECTION),
            Arguments.of(400, HttpStatus.StatusClass.CLIENT_ERROR),
            Arguments.of(404, HttpStatus.StatusClass.CLIENT_ERROR),
            Arguments.of(499, HttpStatus.StatusClass.CLIENT_ERROR),
            Arguments.of(500, HttpStatus.StatusClass.SERVER_ERROR),
            Arguments.of(503, HttpStatus.StatusClass.SERVER_ERROR),
            Arguments.of(599, HttpStatus.StatusClass.SERVER_ERROR)
        );
    }

    @ParameterizedTest
    @MethodSource("statusClassProvider")
    @DisplayName("getStatusClass returns correct class for valid codes")
    void getStatusClass_validCodes_returnsCorrectClass(int statusCode, HttpStatus.StatusClass expected) {
        assertEquals(expected, HttpStatus.getStatusClass(statusCode));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 99, 600, -1, Integer.MIN_VALUE, Integer.MAX_VALUE})
    @DisplayName("getStatusClass returns UNKNOWN for invalid codes")
    void getStatusClass_invalidCodes_returnsUnknown(int statusCode) {
        assertEquals(HttpStatus.StatusClass.UNKNOWN, HttpStatus.getStatusClass(statusCode));
    }

    // ============= clamp Tests =============

    @Test
    @DisplayName("clamp returns same value for valid status codes")
    void clamp_validCode_returnsSameValue() {
        assertEquals(100, HttpStatus.clamp(100));
        assertEquals(200, HttpStatus.clamp(200));
        assertEquals(404, HttpStatus.clamp(404));
        assertEquals(500, HttpStatus.clamp(500));
        assertEquals(599, HttpStatus.clamp(599));
    }

    @Test
    @DisplayName("clamp returns -1 for codes below valid range")
    void clamp_belowRange_returnsMinusOne() {
        assertEquals(-1, HttpStatus.clamp(99));
        assertEquals(-1, HttpStatus.clamp(0));
        assertEquals(-1, HttpStatus.clamp(-1));
        assertEquals(-1, HttpStatus.clamp(-100));
        assertEquals(-1, HttpStatus.clamp(Integer.MIN_VALUE));
    }

    @Test
    @DisplayName("clamp returns 599 for codes above valid range")
    void clamp_aboveRange_returns599() {
        assertEquals(599, HttpStatus.clamp(600));
        assertEquals(599, HttpStatus.clamp(601));
        assertEquals(599, HttpStatus.clamp(1000));
        assertEquals(599, HttpStatus.clamp(Integer.MAX_VALUE));
    }

    // ============= getReasonPhrase Tests =============

    @Test
    @DisplayName("getReasonPhrase returns correct phrase for common status codes")
    void getReasonPhrase_commonCodes_returnsCorrectPhrase() {
        assertEquals("OK", HttpStatus.getReasonPhrase(200));
        assertEquals("Created", HttpStatus.getReasonPhrase(201));
        assertEquals("No Content", HttpStatus.getReasonPhrase(204));
        assertEquals("Not Found", HttpStatus.getReasonPhrase(404));
        assertEquals("Internal Server Error", HttpStatus.getReasonPhrase(500));
        assertEquals("Service Unavailable", HttpStatus.getReasonPhrase(503));
    }

    @Test
    @DisplayName("getReasonPhrase returns correct phrases for 1xx informational codes")
    void getReasonPhrase_informationalCodes_returnsCorrectPhrase() {
        assertEquals("Continue", HttpStatus.getReasonPhrase(100));
        assertEquals("Switching Protocols", HttpStatus.getReasonPhrase(101));
        assertEquals("Processing", HttpStatus.getReasonPhrase(102));
    }

    @Test
    @DisplayName("getReasonPhrase returns correct phrases for 2xx success codes")
    void getReasonPhrase_successCodes_returnsCorrectPhrase() {
        assertEquals("OK", HttpStatus.getReasonPhrase(200));
        assertEquals("Created", HttpStatus.getReasonPhrase(201));
        assertEquals("Accepted", HttpStatus.getReasonPhrase(202));
        assertEquals("Non-Authoritative Information", HttpStatus.getReasonPhrase(203));
        assertEquals("No Content", HttpStatus.getReasonPhrase(204));
        assertEquals("Reset Content", HttpStatus.getReasonPhrase(205));
        assertEquals("Partial Content", HttpStatus.getReasonPhrase(206));
    }

    @Test
    @DisplayName("getReasonPhrase returns correct phrases for 3xx redirection codes")
    void getReasonPhrase_redirectionCodes_returnsCorrectPhrase() {
        assertEquals("Multiple Choices", HttpStatus.getReasonPhrase(300));
        assertEquals("Moved Permanently", HttpStatus.getReasonPhrase(301));
        assertEquals("Found", HttpStatus.getReasonPhrase(302));
        assertEquals("See Other", HttpStatus.getReasonPhrase(303));
        assertEquals("Not Modified", HttpStatus.getReasonPhrase(304));
        assertEquals("Temporary Redirect", HttpStatus.getReasonPhrase(307));
        assertEquals("Permanent Redirect", HttpStatus.getReasonPhrase(308));
    }

    @Test
    @DisplayName("getReasonPhrase returns correct phrases for 4xx client error codes")
    void getReasonPhrase_clientErrorCodes_returnsCorrectPhrase() {
        assertEquals("Bad Request", HttpStatus.getReasonPhrase(400));
        assertEquals("Unauthorized", HttpStatus.getReasonPhrase(401));
        assertEquals("Payment Required", HttpStatus.getReasonPhrase(402));
        assertEquals("Forbidden", HttpStatus.getReasonPhrase(403));
        assertEquals("Not Found", HttpStatus.getReasonPhrase(404));
        assertEquals("Method Not Allowed", HttpStatus.getReasonPhrase(405));
        assertEquals("Not Acceptable", HttpStatus.getReasonPhrase(406));
        assertEquals("Request Timeout", HttpStatus.getReasonPhrase(408));
        assertEquals("Conflict", HttpStatus.getReasonPhrase(409));
        assertEquals("Gone", HttpStatus.getReasonPhrase(410));
        assertEquals("Length Required", HttpStatus.getReasonPhrase(411));
        assertEquals("Precondition Failed", HttpStatus.getReasonPhrase(412));
        assertEquals("Payload Too Large", HttpStatus.getReasonPhrase(413));
        assertEquals("URI Too Long", HttpStatus.getReasonPhrase(414));
        assertEquals("Unsupported Media Type", HttpStatus.getReasonPhrase(415));
        assertEquals("Range Not Satisfiable", HttpStatus.getReasonPhrase(416));
        assertEquals("Expectation Failed", HttpStatus.getReasonPhrase(417));
        assertEquals("I'm a teapot", HttpStatus.getReasonPhrase(418));
        assertEquals("Misdirected Request", HttpStatus.getReasonPhrase(421));
        assertEquals("Unprocessable Entity", HttpStatus.getReasonPhrase(422));
        assertEquals("Locked", HttpStatus.getReasonPhrase(423));
        assertEquals("Failed Dependency", HttpStatus.getReasonPhrase(424));
        assertEquals("Too Early", HttpStatus.getReasonPhrase(425));
        assertEquals("Upgrade Required", HttpStatus.getReasonPhrase(426));
        assertEquals("Precondition Required", HttpStatus.getReasonPhrase(428));
        assertEquals("Too Many Requests", HttpStatus.getReasonPhrase(429));
        assertEquals("Request Header Fields Too Large", HttpStatus.getReasonPhrase(431));
        assertEquals("Unavailable For Legal Reasons", HttpStatus.getReasonPhrase(451));
    }

    @Test
    @DisplayName("getReasonPhrase returns correct phrases for 5xx server error codes")
    void getReasonPhrase_serverErrorCodes_returnsCorrectPhrase() {
        assertEquals("Internal Server Error", HttpStatus.getReasonPhrase(500));
        assertEquals("Not Implemented", HttpStatus.getReasonPhrase(501));
        assertEquals("Bad Gateway", HttpStatus.getReasonPhrase(502));
        assertEquals("Service Unavailable", HttpStatus.getReasonPhrase(503));
        assertEquals("Gateway Timeout", HttpStatus.getReasonPhrase(504));
        assertEquals("HTTP Version Not Supported", HttpStatus.getReasonPhrase(505));
        assertEquals("Variant Also Negotiates", HttpStatus.getReasonPhrase(506));
        assertEquals("Insufficient Storage", HttpStatus.getReasonPhrase(507));
        assertEquals("Loop Detected", HttpStatus.getReasonPhrase(508));
        assertEquals("Not Extended", HttpStatus.getReasonPhrase(510));
        assertEquals("Network Authentication Required", HttpStatus.getReasonPhrase(511));
    }

    @Test
    @DisplayName("getReasonPhrase returns 'Too Many Requests' for 429")
    void getReasonPhrase_429_returnsTooManyRequests() {
        assertEquals("Too Many Requests", HttpStatus.getReasonPhrase(429));
    }

    @Test
    @DisplayName("getReasonPhrase returns 'I'm a teapot' for 418")
    void getReasonPhrase_418_returnsImATeapot() {
        assertEquals("I'm a teapot", HttpStatus.getReasonPhrase(418));
    }

    @Test
    @DisplayName("getReasonPhrase returns 'Unknown' for valid but unrecognized codes")
    void getReasonPhrase_unrecognizedValidCode_returnsUnknown() {
        assertEquals("Unknown", HttpStatus.getReasonPhrase(299));
        assertEquals("Unknown", HttpStatus.getReasonPhrase(598));
        assertEquals("Unknown", HttpStatus.getReasonPhrase(103)); // Unassigned informational
        assertEquals("Unknown", HttpStatus.getReasonPhrase(207)); // Unassigned success
    }

    @Test
    @DisplayName("getReasonPhrase returns 'Invalid Status Code' for invalid codes")
    void getReasonPhrase_invalidCode_returnsInvalidStatusCode() {
        assertEquals("Invalid Status Code", HttpStatus.getReasonPhrase(99));
        assertEquals("Invalid Status Code", HttpStatus.getReasonPhrase(600));
        assertEquals("Invalid Status Code", HttpStatus.getReasonPhrase(-1));
    }

    // ============= StatusClass Enum Tests =============

    @Test
    @DisplayName("StatusClass INFORMATIONAL has correct bounds")
    void statusClass_informational_correctBounds() {
        assertEquals(100, HttpStatus.StatusClass.INFORMATIONAL.getMin());
        assertEquals(199, HttpStatus.StatusClass.INFORMATIONAL.getMax());
    }

    @Test
    @DisplayName("StatusClass SUCCESS has correct bounds")
    void statusClass_success_correctBounds() {
        assertEquals(200, HttpStatus.StatusClass.SUCCESS.getMin());
        assertEquals(299, HttpStatus.StatusClass.SUCCESS.getMax());
    }

    @Test
    @DisplayName("StatusClass REDIRECTION has correct bounds")
    void statusClass_redirection_correctBounds() {
        assertEquals(300, HttpStatus.StatusClass.REDIRECTION.getMin());
        assertEquals(399, HttpStatus.StatusClass.REDIRECTION.getMax());
    }

    @Test
    @DisplayName("StatusClass CLIENT_ERROR has correct bounds")
    void statusClass_clientError_correctBounds() {
        assertEquals(400, HttpStatus.StatusClass.CLIENT_ERROR.getMin());
        assertEquals(499, HttpStatus.StatusClass.CLIENT_ERROR.getMax());
    }

    @Test
    @DisplayName("StatusClass SERVER_ERROR has correct bounds")
    void statusClass_serverError_correctBounds() {
        assertEquals(500, HttpStatus.StatusClass.SERVER_ERROR.getMin());
        assertEquals(599, HttpStatus.StatusClass.SERVER_ERROR.getMax());
    }

    @Test
    @DisplayName("StatusClass UNKNOWN has -1 bounds")
    void statusClass_unknown_correctBounds() {
        assertEquals(-1, HttpStatus.StatusClass.UNKNOWN.getMin());
        assertEquals(-1, HttpStatus.StatusClass.UNKNOWN.getMax());
    }

    // ============= Constants Tests =============

    @Test
    @DisplayName("Constants have correct values")
    void constants_correctValues() {
        assertEquals(100, HttpStatus.MIN_STATUS_CODE);
        assertEquals(599, HttpStatus.MAX_STATUS_CODE);
        assertEquals(200, HttpStatus.OK);
        assertEquals(201, HttpStatus.CREATED);
        assertEquals(204, HttpStatus.NO_CONTENT);
        assertEquals(400, HttpStatus.BAD_REQUEST);
        assertEquals(401, HttpStatus.UNAUTHORIZED);
        assertEquals(403, HttpStatus.FORBIDDEN);
        assertEquals(404, HttpStatus.NOT_FOUND);
        assertEquals(429, HttpStatus.TOO_MANY_REQUESTS);
        assertEquals(500, HttpStatus.INTERNAL_SERVER_ERROR);
        assertEquals(502, HttpStatus.BAD_GATEWAY);
        assertEquals(503, HttpStatus.SERVICE_UNAVAILABLE);
        assertEquals(504, HttpStatus.GATEWAY_TIMEOUT);
    }

    // ============= Constructor Test =============

    @Test
    @DisplayName("Private constructor throws AssertionError")
    void constructor_throwsAssertionError() throws Exception {
        var constructor = HttpStatus.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        // When using reflection to invoke constructor, InvocationTargetException wraps the actual exception
        var exception = assertThrows(java.lang.reflect.InvocationTargetException.class, constructor::newInstance);
        assertTrue(exception.getCause() instanceof AssertionError,
            "Expected AssertionError as cause but got: " + exception.getCause());
    }

    // ============= Edge Cases =============

    @Test
    @DisplayName("Boundary test: code 99 is invalid, code 100 is valid")
    void boundary_99invalid_100valid() {
        assertFalse(HttpStatus.isValid(99));
        assertTrue(HttpStatus.isValid(100));
    }

    @Test
    @DisplayName("Boundary test: code 599 is valid, code 600 is invalid")
    void boundary_599valid_600invalid() {
        assertTrue(HttpStatus.isValid(599));
        assertFalse(HttpStatus.isValid(600));
    }

    @Test
    @DisplayName("isError returns true for all codes >= 400 and <= 599")
    void isError_allErrorCodes_returnsTrue() {
        for (int i = 400; i <= 599; i++) {
            assertTrue(HttpStatus.isError(i), "Expected isError true for " + i);
        }
    }
}
