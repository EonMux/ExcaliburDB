package com.eonmux.excalibur.core.util;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Tells a long-running piece of work to stop, without interrupting it where that is unsafe.
 *
 * <h2>Why not {@link Thread#interrupt()}</h2>
 * <p>An interrupt that reaches a thread inside - or on its way into - an operation on a
 * {@link java.nio.channels.FileChannel} closes that channel with
 * {@link java.nio.channels.ClosedByInterruptException}. Closed, not failed: every later
 * operation on the channel, from every thread, fails with {@code ClosedChannelException} until
 * the file is opened again, and nothing in this codebase opens one again. The storage engine's
 * write log is forced through one such channel on every append, a memory-mapped file grows
 * through one, and the files a reopened graph or index is served from are mapped through one,
 * so a stop delivered as an interrupt to a thread that happens to be writing breaks every
 * writer of that file for the life of the process.</p>
 *
 * <p>So the stop is a flag the work reads at the points where stopping is safe, and the
 * shutdown that wants it gone waits for the work to reach one.</p>
 *
 * <h2>Where an interrupt is still delivered</h2>
 * <p>Some of what long-running work waits on only an interrupt can cut short: a socket read in a
 * source fetch, an embedding request, a backpressure wait. Those touch no shared channel, so
 * work may mark them with {@link #interruptibly(Window)}. A {@link #request()} arriving while
 * the thread is inside such a window interrupts it, one arriving on either side of the window
 * does not, and the thread leaves the window with its interrupt flag cleared - so no interrupt
 * addressed to the window survives into the storage write that follows it. An interrupt from
 * anywhere else that the window absorbs is read as a request to stop, which is what an
 * interrupt conventionally is.</p>
 *
 * <h2>Pausing</h2>
 * <p>{@link #pause(long)} is the stoppable replacement for {@code Thread.sleep} in a loop that
 * sleeps between rounds of work: it returns early, and says so, once a stop is requested, which
 * is what let such loops be woken by an interrupt before.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. A signal is set once and never cleared. At most one thread may be inside
 * {@link #interruptibly(Window)} at a time, because the signal belongs to one piece of work and
 * that work runs on one thread.</p>
 *
 * @author ExcaliburDB
 */
public final class StopSignal implements BooleanSupplier {

    /**
     * Work that may be interrupted to stop it sooner.
     *
     * @param <T> What it returns
     * @param <E> What it throws
     */
    @FunctionalInterface
    public interface Window<T, E extends Exception> {

        /**
         * Runs the work.
         *
         * @return Its result
         * @throws E if the work fails
         */
        T run() throws E;
    }

    /** Guards {@link #interruptible} and the transition of {@link #requested}. */
    private final Object lock = new Object();

    /** Whether a stop has been requested. Written under {@link #lock}; read without it. */
    private volatile boolean requested;

    /** The thread inside {@link #interruptibly(Window)}, or null. Guarded by {@link #lock}. */
    private Thread interruptible;

    /**
     * Asks the work to stop.
     *
     * <p>Idempotent. Interrupts the work's thread only if it is inside an interruptible window
     * at this moment, and wakes a {@link #pause(long)} in progress.</p>
     */
    public void request() {
        synchronized (lock) {
            if (requested) {
                return;
            }
            requested = true;
            if (interruptible != null) {
                interruptible.interrupt();
            }
            lock.notifyAll();
        }
    }

    /**
     * Whether a stop has been requested.
     *
     * @return true once {@link #request()} has been called
     */
    public boolean isRequested() {
        return requested;
    }

    /**
     * As {@link #isRequested()}, so the signal can be handed to anything that polls a
     * {@link BooleanSupplier}.
     *
     * @return true once a stop has been requested
     */
    @Override
    public boolean getAsBoolean() {
        return requested;
    }

    /**
     * Waits up to the given time, returning early if a stop is requested.
     *
     * @param millis How long to wait at most
     * @return true if a stop has been requested, whether before the call or during it
     * @throws InterruptedException if the waiting thread is interrupted by someone else
     */
    public boolean pause(long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, millis));
        synchronized (lock) {
            while (!requested) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
            return true;
        }
    }

    /**
     * Runs work that a stop may interrupt, and leaves the thread uninterrupted afterwards.
     *
     * <p>Only for work that touches no channel another thread uses: a network call, a wait on a
     * queue, a read of a file this thread opened and alone holds. A stop already requested
     * interrupts the thread on entry, so the work is cut short at its first blocking call rather
     * than run in full. On exit the interrupt flag is cleared, and a flag found set - by this
     * signal or by anyone else - leaves the signal requested.</p>
     *
     * <p>A window entered from inside another on the same thread simply runs the work: the
     * outer window already covers it.</p>
     *
     * @param work The work
     * @param <T>  What it returns
     * @param <E>  What it throws
     * @return What the work returned
     * @throws E if the work throws, including because it was interrupted
     * @throws IllegalStateException if another thread is inside a window of this signal
     */
    public <T, E extends Exception> T interruptibly(Window<T, E> work) throws E {
        Thread current = Thread.currentThread();
        boolean nested;
        synchronized (lock) {
            nested = interruptible == current;
        }
        if (nested) {
            return work.run();
        }
        synchronized (lock) {
            if (interruptible != null) {
                throw new IllegalStateException("another thread (" + interruptible.getName()
                    + ") is already inside an interruptible window of this stop signal");
            }
            interruptible = current;
            if (requested) {
                current.interrupt();
            }
        }
        try {
            return work.run();
        } finally {
            synchronized (lock) {
                // From here no request() can reach this thread, so clearing the flag below cannot
                // be undone by a late one.
                interruptible = null;
            }
            if (Thread.interrupted()) {
                request();
            }
        }
    }

    @Override
    public String toString() {
        return "StopSignal[" + (requested ? "requested" : "running") + "]";
    }
}
