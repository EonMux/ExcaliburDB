package excalibur.ingest.source;

/**
 * Thrown when a crawl stopped before it had listed the source.
 *
 * <h2>Why this is not just a shorter list</h2>
 * <p>Everything downstream reads a crawl as the source's current contents. Change detection
 * subtracts it from the registry and treats the difference as documents the source no longer
 * serves, and the reindex then tombstones them. Over a complete listing that is the whole point;
 * over a prefix of one it deletes every document the crawl had not reached yet, which for a walk
 * cut off near its start is most of the corpus.</p>
 *
 * <p>Unchecked because it is raised while the stream is being consumed rather than by the call
 * that opened it, and {@link java.util.stream.Stream} has nowhere for a checked exception to go.
 * Callers that have a checked contract of their own translate it at their boundary.</p>
 */
public class IncompleteCrawlException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message What stopped the crawl, and that what it listed is not the whole source
     */
    public IncompleteCrawlException(String message) {
        super(message);
    }

    /**
     * Creates the exception from an underlying failure.
     *
     * @param message What stopped the crawl, and that what it listed is not the whole source
     * @param cause   The failure that stopped it
     */
    public IncompleteCrawlException(String message, Throwable cause) {
        super(message, cause);
    }
}
