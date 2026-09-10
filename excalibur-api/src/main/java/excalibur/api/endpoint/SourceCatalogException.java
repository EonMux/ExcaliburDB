package excalibur.api.endpoint;

/**
 * Signals that a change to the set of registered sources could not be written down.
 *
 * <p>Registering a source and deregistering one both promise something about the next restart:
 * that the source will still be crawled, or that it will not. Neither promise can be kept if the
 * catalog under the data directory cannot be updated, and neither failure shows up later as
 * anything but a node crawling the wrong set of sources.</p>
 *
 * <p>So the operation is refused rather than half-applied, and the caller is told the node could
 * not record it - a retryable server fault, not a bad request. The message is fixed: a disk that
 * is full, read-only or missing is described in the node's log, not in an HTTP response.</p>
 */
public class SourceCatalogException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** The status this failure maps to: the node is temporarily unable to record the change. */
    public static final int STATUS_CODE = 503;

    /** The response text, which describes no path and no filesystem state. */
    public static final String CLIENT_MESSAGE = "Source registry could not be updated";

    /**
     * Creates the failure.
     *
     * @param message Server-side detail for the log
     * @param cause   What the filesystem reported
     */
    public SourceCatalogException(String message, Throwable cause) {
        super(message, cause);
    }
}
