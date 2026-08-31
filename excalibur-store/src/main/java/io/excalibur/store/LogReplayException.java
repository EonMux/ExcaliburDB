package io.excalibur.store;

/**
 * Thrown when a write-ahead log replay handler fails to apply a recovered entry.
 *
 * <p>This is deliberately distinct from a corrupt-record failure. A record that fails
 * its CRC32 check is a storage-level problem that recovery can skip; a handler that
 * rejects a structurally valid record means the segment cannot be brought back to the
 * state its log describes, and the remaining log must not be silently discarded.</p>
 *
 * <p>Callers must treat this as fatal to the affected segment: the segment's committed
 * state is unknown, so serving it would return wrong results rather than an error.</p>
 *
 * @author ExcaliburDB
 * @since 2.0
 * @see WriteLog#recover(java.util.function.Consumer)
 */
public class LogReplayException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new replay exception.
     *
     * @param message The error message
     * @param cause   The failure thrown by the replay handler
     */
    public LogReplayException(String message, Throwable cause) {
        super(message, cause);
    }
}
