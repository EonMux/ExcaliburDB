package io.excalibur.llm;

import io.excalibur.llm.client.HttpStatus;

/**
 * Exception thrown when LLM operations fail.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. Once constructed, the exception's
 * state (message and cause) cannot be modified.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>HTTP status code: valid range [100, 599] (validated by {@link HttpStatus}), or -1 if not applicable</li>
 *   <li>Error type: categorizes the failure mode for proper handling</li>
 * </ul>
 *
 * @see HttpStatus
 */
public class LLMException extends RuntimeException {

    /**
     * Categories of LLM failures for proper error handling.
     */
    public enum ErrorType {
        /** Network-level failure (connection refused, timeout, etc.) */
        NETWORK,
        /** HTTP error response (4xx, 5xx) */
        HTTP_ERROR,
        /** Response parsing failure (malformed JSON, missing fields) */
        PARSE_ERROR,
        /** Rate limiting (429 status) */
        RATE_LIMITED,
        /** Circuit breaker is open */
        CIRCUIT_OPEN,
        /** Invalid request (bad parameters, invalid input) */
        INVALID_REQUEST,
        /** API authentication/authorization failure */
        AUTH_ERROR
    }

    private final ErrorType errorType;
    private final int statusCode;
    private final boolean retryable;

    /**
     * Creates a new LLMException with a message.
     *
     * @param message Error message
     * @param errorType Category of the error
     */
    public LLMException(String message, ErrorType errorType) {
        super(message);
        this.errorType = errorType;
        this.statusCode = -1;
        this.retryable = isErrorTypeRetryable(errorType);
    }

    /**
     * Creates a new LLMException with a message and HTTP status code.
     *
     * @param message Error message
     * @param errorType Category of the error
     * @param statusCode HTTP status code (should be in [100, 599])
     */
    public LLMException(String message, ErrorType errorType, int statusCode) {
        super(message);
        this.errorType = errorType;
        // Domain correctness: HTTP status codes are in range [100, 599]
        this.statusCode = clampStatusCode(statusCode);
        this.retryable = isErrorTypeRetryable(errorType) || statusCode >= 500;
    }

    /**
     * Creates a new LLMException with a message and cause.
     *
     * @param message Error message
     * @param errorType Category of the error
     * @param cause Underlying cause
     */
    public LLMException(String message, ErrorType errorType, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.statusCode = -1;
        this.retryable = isErrorTypeRetryable(errorType);
    }

    /**
     * Creates a new LLMException with all parameters.
     *
     * @param message Error message
     * @param errorType Category of the error
     * @param statusCode HTTP status code (should be in [100, 599])
     * @param cause Underlying cause
     */
    public LLMException(String message, ErrorType errorType, int statusCode, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
        this.statusCode = clampStatusCode(statusCode);
        this.retryable = isErrorTypeRetryable(errorType) || statusCode >= 500;
    }

    /**
     * Returns the error type category.
     *
     * @return Error type (never null)
     */
    public ErrorType getErrorType() {
        return errorType;
    }

    /**
     * Returns the HTTP status code, if applicable.
     *
     * @return HTTP status code in [100, 599], or -1 if not applicable
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Indicates whether this error might succeed on retry.
     *
     * @return true if retrying might succeed
     */
    public boolean isRetryable() {
        return retryable;
    }

    /**
     * Clamps status code to valid HTTP range using {@link HttpStatus#clamp(int)}.
     * Domain correctness: HTTP status codes must be in [100, 599].
     *
     * @param code the status code to clamp
     * @return clamped status code, or -1 if below valid range
     */
    private static int clampStatusCode(int code) {
        return HttpStatus.clamp(code);
    }

    /**
     * Determines if an error type is generally retryable.
     */
    private static boolean isErrorTypeRetryable(ErrorType type) {
        return type == ErrorType.NETWORK || type == ErrorType.RATE_LIMITED;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("LLMException{");
        sb.append("type=").append(errorType);
        if (statusCode > 0) {
            sb.append(", statusCode=").append(statusCode);
        }
        sb.append(", retryable=").append(retryable);
        sb.append(", message='").append(getMessage()).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
