package com.eonmux.excalibur.ocr;

import java.time.Duration;
import java.util.Objects;

/**
 * When a conversion must be done by, checked between the steps of reading a page.
 *
 * <p>A model run cannot be stopped part way, so a conversion ends at the first check past its
 * deadline, or past an interruption of its thread: a step later at most, a few seconds on the
 * largest pages.</p>
 *
 * @param nanoTime The {@link System#nanoTime()} past which the conversion is given up
 */
public record Deadline(long nanoTime) {

    /** No deadline: only an interruption stops the conversion. */
    public static final Deadline NONE = new Deadline(Long.MAX_VALUE);

    /**
     * A deadline a duration from now.
     *
     * @param timeout How long the conversion may take; one past any reachable time is none
     * @return The deadline
     */
    public static Deadline after(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.compareTo(Duration.ofNanos(Long.MAX_VALUE / 2)) >= 0) {
            return NONE;
        }
        return new Deadline(System.nanoTime() + Math.max(0, timeout.toNanos()));
    }

    /**
     * Whether the deadline has passed.
     *
     * @return true once it has
     */
    public boolean passed() {
        return nanoTime != Long.MAX_VALUE && System.nanoTime() - nanoTime > 0;
    }

    /**
     * Ends the conversion if its deadline has passed or its thread was interrupted.
     *
     * @throws InterruptedException       if the thread was interrupted
     * @throws ConversionTimeoutException if the deadline has passed
     */
    public void check() throws InterruptedException, ConversionTimeoutException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("OCR stopped");
        }
        if (passed()) {
            throw new ConversionTimeoutException("ran past its deadline");
        }
    }
}
