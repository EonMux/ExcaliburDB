package excalibur.ingest.source;

/**
 * Exception thrown when a source operation fails.
 *
 * <p>This is the base exception for all source-related errors including
 * connection failures, permission errors, and I/O problems.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe for read access (immutable after construction).</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try {
 *     Stream<String> ids = source.crawl();
 * } catch (SourceException e) {
 *     log.error("Crawl failed for source: {}", source.getSourceId(), e);
 * }
 * }</pre>
 */
public class SourceException extends Exception {

    private final String sourceId;
    private final boolean retryable;

    /**
     * Creates a new SourceException with a message.
     *
     * @param message Error description
     */
    public SourceException(String message) {
        super(message);
        this.sourceId = null;
        this.retryable = true;
    }

    /**
     * Creates a new SourceException with a message and cause.
     *
     * @param message Error description
     * @param cause   Underlying exception
     */
    public SourceException(String message, Throwable cause) {
        super(message, cause);
        this.sourceId = null;
        this.retryable = true;
    }

    /**
     * Creates a new SourceException with source context.
     *
     * @param message   Error description
     * @param sourceId  Source identifier for context
     * @param retryable Whether the operation might succeed if retried
     */
    public SourceException(String message, String sourceId, boolean retryable) {
        super(message);
        this.sourceId = sourceId;
        this.retryable = retryable;
    }

    /**
     * Creates a new SourceException with source context and cause.
     *
     * @param message   Error description
     * @param sourceId  Source identifier for context
     * @param retryable Whether the operation might succeed if retried
     * @param cause     Underlying exception
     */
    public SourceException(String message, String sourceId, boolean retryable, Throwable cause) {
        super(message, cause);
        this.sourceId = sourceId;
        this.retryable = retryable;
    }

    /**
     * Returns the source ID associated with this exception.
     *
     * @return Source ID, or null if not specified
     */
    public String getSourceId() {
        return sourceId;
    }

    /**
     * Indicates whether the failed operation might succeed if retried.
     *
     * <p>Retryable errors include:</p>
     * <ul>
     *   <li>Transient network failures</li>
     *   <li>Rate limiting</li>
     *   <li>Temporary resource unavailability</li>
     * </ul>
     *
     * <p>Non-retryable errors include:</p>
     * <ul>
     *   <li>Authentication failures</li>
     *   <li>Permission denied</li>
     *   <li>Resource not found (404)</li>
     *   <li>Invalid configuration</li>
     * </ul>
     *
     * @return true if the operation might succeed on retry
     */
    public boolean isRetryable() {
        return retryable;
    }

    @Override
    public String toString() {
        String s = getClass().getName();
        String message = getLocalizedMessage();
        StringBuilder sb = new StringBuilder(s);
        if (sourceId != null) {
            sb.append(" [source=").append(sourceId);
            if (!retryable) {
                sb.append(", retryable=false");
            }
            sb.append("]");
        }
        if (message != null) {
            sb.append(": ").append(message);
        }
        return sb.toString();
    }
}
