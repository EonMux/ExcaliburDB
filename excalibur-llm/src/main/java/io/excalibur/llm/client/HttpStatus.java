package io.excalibur.llm.client;

/**
 * Utility class for HTTP status code validation and categorization.
 *
 * <p>Provides methods to validate that HTTP status codes are within the
 * valid range [100, 599] as defined by RFC 7231, and to categorize
 * status codes into their standard classes.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Valid HTTP status codes: [100, 599]</li>
 *   <li>Informational (1xx): [100, 199] - Request received, continuing process</li>
 *   <li>Successful (2xx): [200, 299] - Request successfully received, understood, accepted</li>
 *   <li>Redirection (3xx): [300, 399] - Further action needed to complete request</li>
 *   <li>Client Error (4xx): [400, 499] - Request contains bad syntax or cannot be fulfilled</li>
 *   <li>Server Error (5xx): [500, 599] - Server failed to fulfill valid request</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. All methods are static and stateless.</p>
 *
 * @see <a href="https://tools.ietf.org/html/rfc7231#section-6">RFC 7231 Section 6</a>
 */
public final class HttpStatus {

    /** Minimum valid HTTP status code */
    public static final int MIN_STATUS_CODE = 100;

    /** Maximum valid HTTP status code */
    public static final int MAX_STATUS_CODE = 599;

    /** Continue */
    public static final int CONTINUE = 100;
    /** Switching Protocols */
    public static final int SWITCHING_PROTOCOLS = 101;
    /** Processing */
    public static final int PROCESSING = 102;

    /** OK */
    public static final int OK = 200;
    /** Created */
    public static final int CREATED = 201;
    /** Accepted */
    public static final int ACCEPTED = 202;
    /** Non-Authoritative Information */
    public static final int NON_AUTHORITATIVE_INFORMATION = 203;
    /** No Content */
    public static final int NO_CONTENT = 204;
    /** Reset Content */
    public static final int RESET_CONTENT = 205;
    /** Partial Content */
    public static final int PARTIAL_CONTENT = 206;

    /** Multiple Choices */
    public static final int MULTIPLE_CHOICES = 300;
    /** Moved Permanently */
    public static final int MOVED_PERMANENTLY = 301;
    /** Found */
    public static final int FOUND = 302;
    /** See Other */
    public static final int SEE_OTHER = 303;
    /** Not Modified */
    public static final int NOT_MODIFIED = 304;
    /** Temporary Redirect */
    public static final int TEMPORARY_REDIRECT = 307;
    /** Permanent Redirect */
    public static final int PERMANENT_REDIRECT = 308;

    /** Bad Request */
    public static final int BAD_REQUEST = 400;
    /** Unauthorized */
    public static final int UNAUTHORIZED = 401;
    /** Payment Required */
    public static final int PAYMENT_REQUIRED = 402;
    /** Forbidden */
    public static final int FORBIDDEN = 403;
    /** Not Found */
    public static final int NOT_FOUND = 404;
    /** Method Not Allowed */
    public static final int METHOD_NOT_ALLOWED = 405;
    /** Not Acceptable */
    public static final int NOT_ACCEPTABLE = 406;
    /** Request Timeout */
    public static final int REQUEST_TIMEOUT = 408;
    /** Conflict */
    public static final int CONFLICT = 409;
    /** Gone */
    public static final int GONE = 410;
    /** Length Required */
    public static final int LENGTH_REQUIRED = 411;
    /** Precondition Failed */
    public static final int PRECONDITION_FAILED = 412;
    /** Payload Too Large */
    public static final int PAYLOAD_TOO_LARGE = 413;
    /** URI Too Long */
    public static final int URI_TOO_LONG = 414;
    /** Unsupported Media Type */
    public static final int UNSUPPORTED_MEDIA_TYPE = 415;
    /** Range Not Satisfiable */
    public static final int RANGE_NOT_SATISFIABLE = 416;
    /** Expectation Failed */
    public static final int EXPECTATION_FAILED = 417;
    /** I'm a teapot */
    public static final int IM_A_TEAPOT = 418;
    /** Misdirected Request */
    public static final int MISDIRECTED_REQUEST = 421;
    /** Unprocessable Entity */
    public static final int UNPROCESSABLE_ENTITY = 422;
    /** Locked */
    public static final int LOCKED = 423;
    /** Failed Dependency */
    public static final int FAILED_DEPENDENCY = 424;
    /** Too Early */
    public static final int TOO_EARLY = 425;
    /** Upgrade Required */
    public static final int UPGRADE_REQUIRED = 426;
    /** Precondition Required */
    public static final int PRECONDITION_REQUIRED = 428;
    /** Too Many Requests */
    public static final int TOO_MANY_REQUESTS = 429;
    /** Request Header Fields Too Large */
    public static final int REQUEST_HEADER_FIELDS_TOO_LARGE = 431;
    /** Unavailable For Legal Reasons */
    public static final int UNAVAILABLE_FOR_LEGAL_REASONS = 451;

    /** Internal Server Error */
    public static final int INTERNAL_SERVER_ERROR = 500;
    /** Not Implemented */
    public static final int NOT_IMPLEMENTED = 501;
    /** Bad Gateway */
    public static final int BAD_GATEWAY = 502;
    /** Service Unavailable */
    public static final int SERVICE_UNAVAILABLE = 503;
    /** Gateway Timeout */
    public static final int GATEWAY_TIMEOUT = 504;
    /** HTTP Version Not Supported */
    public static final int HTTP_VERSION_NOT_SUPPORTED = 505;
    /** Variant Also Negotiates */
    public static final int VARIANT_ALSO_NEGOTIATES = 506;
    /** Insufficient Storage */
    public static final int INSUFFICIENT_STORAGE = 507;
    /** Loop Detected */
    public static final int LOOP_DETECTED = 508;
    /** Not Extended */
    public static final int NOT_EXTENDED = 510;
    /** Network Authentication Required */
    public static final int NETWORK_AUTHENTICATION_REQUIRED = 511;

    /** Private constructor - utility class */
    private HttpStatus() {
        throw new AssertionError("Utility class - do not instantiate");
    }

    /**
     * Validates that the given status code is within the valid HTTP range.
     *
     * <p>Domain Correctness: HTTP status codes must be in [100, 599].</p>
     *
     * @param statusCode the status code to validate
     * @return true if the status code is valid, false otherwise
     */
    public static boolean isValid(int statusCode) {
        return statusCode >= MIN_STATUS_CODE && statusCode <= MAX_STATUS_CODE;
    }

    /**
     * Validates the status code and throws an exception if invalid.
     *
     * <p>Domain Correctness: HTTP status codes must be in [100, 599].</p>
     *
     * @param statusCode the status code to validate
     * @throws IllegalArgumentException if the status code is outside [100, 599]
     */
    public static void validate(int statusCode) {
        if (!isValid(statusCode)) {
            throw new IllegalArgumentException(
                "Invalid HTTP status code: " + statusCode +
                ". Valid range is [" + MIN_STATUS_CODE + ", " + MAX_STATUS_CODE + "]."
            );
        }
    }

    /**
     * Checks if the status code is informational (1xx).
     *
     * <p>Domain Correctness: Informational codes are in [100, 199].</p>
     *
     * @param statusCode the status code to check
     * @return true if informational, false otherwise
     */
    public static boolean isInformational(int statusCode) {
        return statusCode >= 100 && statusCode <= 199;
    }

    /**
     * Checks if the status code indicates success (2xx).
     *
     * <p>Domain Correctness: Success codes are in [200, 299].</p>
     *
     * @param statusCode the status code to check
     * @return true if success, false otherwise
     */
    public static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode <= 299;
    }

    /**
     * Checks if the status code is a redirection (3xx).
     *
     * <p>Domain Correctness: Redirection codes are in [300, 399].</p>
     *
     * @param statusCode the status code to check
     * @return true if redirection, false otherwise
     */
    public static boolean isRedirection(int statusCode) {
        return statusCode >= 300 && statusCode <= 399;
    }

    /**
     * Checks if the status code is a client error (4xx).
     *
     * <p>Domain Correctness: Client error codes are in [400, 499].</p>
     *
     * @param statusCode the status code to check
     * @return true if client error, false otherwise
     */
    public static boolean isClientError(int statusCode) {
        return statusCode >= 400 && statusCode <= 499;
    }

    /**
     * Checks if the status code is a server error (5xx).
     *
     * <p>Domain Correctness: Server error codes are in [500, 599].</p>
     *
     * @param statusCode the status code to check
     * @return true if server error, false otherwise
     */
    public static boolean isServerError(int statusCode) {
        return statusCode >= 500 && statusCode <= 599;
    }

    /**
     * Checks if the status code indicates any kind of error (4xx or 5xx).
     *
     * <p>Domain Correctness: Error codes are in [400, 599].</p>
     *
     * @param statusCode the status code to check
     * @return true if error (client or server), false otherwise
     */
    public static boolean isError(int statusCode) {
        return statusCode >= 400 && statusCode <= MAX_STATUS_CODE;
    }

    /**
     * Checks if the status code indicates a retryable error.
     *
     * <p>Retryable errors include:</p>
     * <ul>
     *   <li>408 Request Timeout</li>
     *   <li>429 Too Many Requests</li>
     *   <li>500-599 Server Errors</li>
     * </ul>
     *
     * <p>Domain Correctness: Based on HTTP semantics - some errors are
     * transient and may succeed on retry.</p>
     *
     * @param statusCode the status code to check
     * @return true if the error might succeed on retry
     */
    public static boolean isRetryable(int statusCode) {
        return statusCode == REQUEST_TIMEOUT ||
               statusCode == TOO_MANY_REQUESTS ||
               isServerError(statusCode);
    }

    /**
     * Returns the status class for the given status code.
     *
     * <p>Domain Correctness: Maps status codes to their standard HTTP class.</p>
     *
     * @param statusCode the status code
     * @return the status class, or UNKNOWN if outside valid range
     */
    public static StatusClass getStatusClass(int statusCode) {
        if (isInformational(statusCode)) {
            return StatusClass.INFORMATIONAL;
        }
        if (isSuccess(statusCode)) {
            return StatusClass.SUCCESS;
        }
        if (isRedirection(statusCode)) {
            return StatusClass.REDIRECTION;
        }
        if (isClientError(statusCode)) {
            return StatusClass.CLIENT_ERROR;
        }
        if (isServerError(statusCode)) {
            return StatusClass.SERVER_ERROR;
        }
        return StatusClass.UNKNOWN;
    }

    /**
     * Clamps a status code to the valid HTTP range.
     *
     * <p>Domain Correctness: If the code is below 100, returns -1 (invalid sentinel).
     * If the code is above 599, returns 599 (max valid code).</p>
     *
     * @param statusCode the status code to clamp
     * @return the clamped status code, or -1 if below valid range
     */
    public static int clamp(int statusCode) {
        if (statusCode < MIN_STATUS_CODE) {
            return -1;
        }
        if (statusCode > MAX_STATUS_CODE) {
            return MAX_STATUS_CODE;
        }
        return statusCode;
    }

    /**
     * Returns a human-readable reason phrase for common status codes.
     *
     * <p>Domain Correctness: Returns standard reason phrases from RFC 7231
     * and related RFCs for known status codes.</p>
     *
     * @param statusCode the status code
     * @return the reason phrase, or "Unknown" for unrecognized codes
     */
    public static String getReasonPhrase(int statusCode) {
        return switch (statusCode) {
            case 100 -> "Continue";
            case 101 -> "Switching Protocols";
            case 102 -> "Processing";
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 203 -> "Non-Authoritative Information";
            case 204 -> "No Content";
            case 205 -> "Reset Content";
            case 206 -> "Partial Content";
            case 300 -> "Multiple Choices";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 303 -> "See Other";
            case 304 -> "Not Modified";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 402 -> "Payment Required";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 411 -> "Length Required";
            case 412 -> "Precondition Failed";
            case 413 -> "Payload Too Large";
            case 414 -> "URI Too Long";
            case 415 -> "Unsupported Media Type";
            case 416 -> "Range Not Satisfiable";
            case 417 -> "Expectation Failed";
            case 418 -> "I'm a teapot";
            case 421 -> "Misdirected Request";
            case 422 -> "Unprocessable Entity";
            case 423 -> "Locked";
            case 424 -> "Failed Dependency";
            case 425 -> "Too Early";
            case 426 -> "Upgrade Required";
            case 428 -> "Precondition Required";
            case 429 -> "Too Many Requests";
            case 431 -> "Request Header Fields Too Large";
            case 451 -> "Unavailable For Legal Reasons";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            case 505 -> "HTTP Version Not Supported";
            case 506 -> "Variant Also Negotiates";
            case 507 -> "Insufficient Storage";
            case 508 -> "Loop Detected";
            case 510 -> "Not Extended";
            case 511 -> "Network Authentication Required";
            default -> isValid(statusCode) ? "Unknown" : "Invalid Status Code";
        };
    }

    /**
     * Classification of HTTP status codes into their standard categories.
     */
    public enum StatusClass {
        /** Informational (1xx): Request received, continuing process */
        INFORMATIONAL(100, 199),
        /** Success (2xx): Request successfully received, understood, accepted */
        SUCCESS(200, 299),
        /** Redirection (3xx): Further action needed to complete request */
        REDIRECTION(300, 399),
        /** Client Error (4xx): Request contains bad syntax or cannot be fulfilled */
        CLIENT_ERROR(400, 499),
        /** Server Error (5xx): Server failed to fulfill valid request */
        SERVER_ERROR(500, 599),
        /** Unknown: Status code outside valid range [100, 599] */
        UNKNOWN(-1, -1);

        private final int min;
        private final int max;

        StatusClass(int min, int max) {
            this.min = min;
            this.max = max;
        }

        /**
         * Returns the minimum status code for this class.
         *
         * @return minimum status code, or -1 for UNKNOWN
         */
        public int getMin() {
            return min;
        }

        /**
         * Returns the maximum status code for this class.
         *
         * @return maximum status code, or -1 for UNKNOWN
         */
        public int getMax() {
            return max;
        }
    }
}
