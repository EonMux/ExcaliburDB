package com.eonmux.excalibur.ingest.pipeline;

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
 * <p>The type alone is what says the run was stopped. It carries no interrupt, and the thread
 * that throws it is left with its interrupt flag clear: the stop was asked for through the run's
 * {@link com.eonmux.excalibur.core.util.StopSignal}, and a flag left set would close the next
 * channel the thread writes through - on a pooled or scheduled thread, some other run's.</p>
 *
 * @see ReindexCancellation
 */
public final class ReindexCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the signal.
     *
     * @param message Where the run stopped, for the log
     */
    public ReindexCancelledException(String message) {
        super(message);
    }
}
