package com.eonmux.excalibur.store;

/**
 * Exception thrown when a segment cannot accept more entries due to load factor constraints.
 *
 * <p>This exception indicates that a segment has reached its capacity limit (load factor >= 0.7)
 * and automatic splitting is either not available or could not complete in time.</p>
 *
 * <h2>When This Exception is Thrown</h2>
 * <ul>
 *   <li>The target segment has reached the load factor threshold (0.7)</li>
 *   <li>Automatic splitting is disabled (StorageEngine not started)</li>
 *   <li>A split was requested but did not complete within the timeout</li>
 *   <li>The split worker is not running or encountered an error</li>
 * </ul>
 *
 * <h2>Recovery Strategies</h2>
 * <p>Callers should implement one of the following recovery strategies:</p>
 * <ol>
 *   <li><strong>Retry:</strong> Wait briefly and retry the operation. The split may complete.</li>
 *   <li><strong>Backoff:</strong> Implement exponential backoff for high-throughput scenarios.</li>
 *   <li><strong>Circuit Breaker:</strong> Temporarily reject writes if capacity issues persist.</li>
 * </ol>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * try {
 *     engine.put(key, value);
 * } catch (SegmentAtCapacityException e) {
 *     // Implement retry with backoff
 *     int retries = 0;
 *     while (retries < MAX_RETRIES) {
 *         Thread.sleep(100 * (1 << retries)); // Exponential backoff
 *         try {
 *             engine.put(key, value);
 *             break; // Success
 *         } catch (SegmentAtCapacityException e2) {
 *             retries++;
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>This exception is immutable and thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 * @see StorageEngine#put(byte[], byte[])
 * @see Segment#LOAD_FACTOR_THRESHOLD
 */
public class SegmentAtCapacityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new SegmentAtCapacityException with the specified message.
     *
     * @param message The error message
     */
    public SegmentAtCapacityException(String message) {
        super(message);
    }

    /**
     * Creates a new SegmentAtCapacityException with the specified message and cause.
     *
     * @param message The error message
     * @param cause The underlying cause
     */
    public SegmentAtCapacityException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Checks if a throwable is or wraps a SegmentAtCapacityException.
     *
     * <p>This is useful for detecting capacity issues in nested exception chains.</p>
     *
     * @param throwable The throwable to check
     * @return true if the throwable or any of its causes is a SegmentAtCapacityException
     */
    public static boolean isCapacityException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SegmentAtCapacityException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
