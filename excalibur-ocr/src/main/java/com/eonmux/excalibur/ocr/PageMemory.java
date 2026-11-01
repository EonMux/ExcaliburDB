package com.eonmux.excalibur.ocr;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * How much heap the pages being read may hold at once, so that many cores reading large pages
 * together wait for one another rather than exhaust the heap and end the process.
 *
 * <p>A page's need is estimated from the pixels it is rendered at,
 * {@value #BYTES_PER_PIXEL} bytes each: the gray render and its turned and straightened copies,
 * PDFBox's drawing buffers, and the page's own images decoded at about the same resolution. The
 * budget is a share of the maximum heap; a page that needs more than all of it is read alone.
 * Waiting counts against the conversion's deadline.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe.</p>
 */
final class PageMemory {

    /** Heap a page holds per rendered pixel while it is read. */
    static final int BYTES_PER_PIXEL = 8;

    /** The share of the maximum heap pages may hold at once. */
    static final double HEAP_SHARE = 0.5;

    private static final long KIB = 1024;

    /** Permits in KiB, so a heap of up to 2 TiB counts in an int; null when unbounded. */
    private final Semaphore permits;
    private final int total;

    private PageMemory(long budgetBytes) {
        long kib = Math.max(1, budgetBytes / KIB);
        this.total = (int) Math.min(Integer.MAX_VALUE, kib);
        this.permits = new Semaphore(total, true);
    }

    /**
     * The budget for this process: {@link #HEAP_SHARE} of the maximum heap.
     *
     * @return The budget
     */
    static PageMemory forHeap() {
        return ofBytes((long) (Runtime.getRuntime().maxMemory() * HEAP_SHARE));
    }

    /**
     * A budget of a given size.
     *
     * @param bytes The heap pages may hold at once, positive
     * @return The budget
     * @throws IllegalArgumentException if the size is not positive
     */
    static PageMemory ofBytes(long bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("a page memory budget must be positive, got "
                + bytes);
        }
        return new PageMemory(bytes);
    }

    /**
     * The heap a page rendered at so many pixels is expected to hold.
     *
     * @param pixels The rendered page's pixels
     * @return Its need in bytes
     */
    static long need(double pixels) {
        return (long) Math.ceil(Math.max(0, pixels) * BYTES_PER_PIXEL);
    }

    /**
     * Takes a page's share of the budget, waiting while other pages hold it.
     *
     * @param bytes    The page's need; more than the whole budget takes all of it
     * @param deadline When the conversion must be done by
     * @return The share, to be given back with {@link Lease#close()}
     * @throws ConversionTimeoutException if the deadline passes while waiting
     * @throws InterruptedException       if the thread is interrupted while waiting
     */
    Lease acquire(long bytes, Deadline deadline)
            throws ConversionTimeoutException, InterruptedException {
        int n = (int) Math.max(1, Math.min(total, (bytes + KIB - 1) / KIB));
        if (deadline.nanoTime() == Long.MAX_VALUE) {
            permits.acquire(n);
        } else if (!permits.tryAcquire(n, Math.max(0, deadline.nanoTime() - System.nanoTime()),
                TimeUnit.NANOSECONDS)) {
            throw new ConversionTimeoutException("ran past its deadline waiting for memory to "
                + "read a page");
        }
        return new Lease(n);
    }

    /**
     * The budget not held by any page, in KiB.
     *
     * @return The free share
     */
    int availableKib() {
        return permits.availablePermits();
    }

    /** A page's share of the budget. */
    final class Lease implements AutoCloseable {

        private final int n;
        private boolean closed;

        private Lease(int n) {
            this.n = n;
        }

        /** Gives the share back; a second call does nothing. */
        @Override
        public void close() {
            if (!closed) {
                closed = true;
                permits.release(n);
            }
        }
    }
}
