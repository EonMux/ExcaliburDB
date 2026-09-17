package excalibur.ingest.pipeline;

/**
 * Signals that a reindex stopped because someone asked it to.
 *
 * <p>Not a failure, and the distinction is the whole reason this is its own type. A run that was
 * stopped left the corpus exactly as far along as it got: every document it finished is
 * published and registered, and every document it had not reached is untouched and will be
 * picked up by the next run. Reporting that as an outage would send an operator looking for a
 * broken source; reporting it as a completed reindex would say the corpus now follows its source
 * when it does not.</p>
 *
 * <p>Unchecked, so the entry points keep the signatures they had. It is caught by the runner that
 * asked for the stop, and by nothing in between - a {@code catch (Exception)} that filed it as
 * one document's bad luck is precisely what made a cancelled reindex walk the rest of the
 * crawl.</p>
 *
 * <p>Its cause is always an {@link InterruptedException}, and the interrupt flag is set again
 * before it is thrown. The endpoint cancels by interrupting the run thread and classifies the
 * outcome from whichever of those two survived, so a stop has to carry both: a blocking call
 * that reported the interrupt by throwing cleared the flag, and one that reported it by setting
 * the flag threw nothing.</p>
 *
 * @see ReindexCancellation
 */
public final class ReindexCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the signal.
     *
     * @param message Where the run stopped, for the log
     * @param cause   The interrupt that asked for the stop
     */
    public ReindexCancelledException(String message, InterruptedException cause) {
        super(message, cause);
    }
}
