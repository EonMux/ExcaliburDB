package com.eonmux.excalibur.query.executor;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A token for cooperative cancellation of query operations.
 *
 * <p>CancellationToken enables timeout handling and user-initiated cancellation
 * for query execution. It follows the cooperative cancellation pattern where
 * operations periodically check {@link #isCancelled()} or call {@link #checkCancelled()}
 * to detect cancellation requests.</p>
 *
 * <h2>Usage in Query Pipeline</h2>
 * <p>The token is used throughout the query pipeline stages:</p>
 * <pre>{@code
 * // Create token for a query
 * CancellationToken token = new CancellationToken();
 *
 * // Execute query with timeout
 * CompletableFuture<QueryResult> future = CompletableFuture.supplyAsync(() -> {
 *     token.checkCancelled(); // Stage 0: Epoch snapshot
 *     // ... epoch snapshot logic ...
 *
 *     token.checkCancelled(); // Stage 1: Cuckoo filter cascade
 *     // ... filter logic ...
 *
 *     token.checkCancelled(); // Stage 2: LSH candidates
 *     // ... LSH logic ...
 *
 *     // ... more stages ...
 *     return result;
 * });
 *
 * // Apply timeout
 * future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
 *       .whenComplete((result, error) -> {
 *           if (error instanceof TimeoutException) {
 *               token.cancel(); // Propagate cancellation
 *           }
 *       });
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is fully thread-safe.</strong></p>
 * <ul>
 *   <li>The cancelled flag is volatile for visibility across threads</li>
 *   <li>Cancellation listeners are called in a thread-safe manner</li>
 *   <li>All public methods can be called from any thread</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Once cancelled, a token cannot be uncancelled</li>
 *   <li>Cancellation is idempotent - calling cancel() multiple times has no additional effect</li>
 *   <li>Listeners are notified exactly once when cancellation occurs</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
public final class CancellationToken {

    /**
     * The cancelled flag. Volatile for visibility across threads.
     * Once set to true, it never goes back to false.
     */
    private volatile boolean cancelled;

    /**
     * List of listeners to be notified when cancellation occurs.
     * CopyOnWriteArrayList for thread-safe iteration and modification.
     */
    private final List<Runnable> cancellationListeners;

    /**
     * The reason for cancellation, if provided.
     */
    private volatile String cancellationReason;

    /**
     * Creates a new CancellationToken in non-cancelled state.
     */
    public CancellationToken() {
        this.cancelled = false;
        this.cancellationListeners = new CopyOnWriteArrayList<>();
        this.cancellationReason = null;
    }

    // ==================== Core Operations ====================

    /**
     * Requests cancellation of the operation.
     *
     * <p>This method is idempotent - calling it multiple times has no
     * additional effect after the first call. Listeners are notified
     * only on the first cancellation.</p>
     *
     * @return true if this call caused the cancellation, false if already cancelled
     */
    public boolean cancel() {
        return cancel(null);
    }

    /**
     * Requests cancellation of the operation with a reason.
     *
     * @param reason The reason for cancellation (may be null)
     * @return true if this call caused the cancellation, false if already cancelled
     */
    public boolean cancel(String reason) {
        if (cancelled) {
            return false; // Already cancelled
        }

        synchronized (this) {
            if (cancelled) {
                return false; // Double-check under lock
            }
            cancelled = true;
            cancellationReason = reason;
        }

        // Notify listeners outside of synchronized block
        notifyListeners();

        return true;
    }

    /**
     * Returns whether cancellation has been requested.
     *
     * <p>This is a non-blocking check that can be called frequently
     * during long-running operations.</p>
     *
     * @return true if cancellation has been requested
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Returns the reason for cancellation, if provided.
     *
     * @return The cancellation reason, or null if not cancelled or no reason provided
     */
    public String cancellationReason() {
        return cancellationReason;
    }

    /**
     * Throws a CancellationException if cancellation has been requested.
     *
     * <p>This is the primary method for cooperative cancellation in the
     * query pipeline. Operations call this method at appropriate checkpoints
     * to detect and respond to cancellation.</p>
     *
     * <p><strong>Usage:</strong></p>
     * <pre>{@code
     * public void performStage(CancellationToken token) {
     *     token.checkCancelled(); // Check before starting
     *
     *     for (Item item : items) {
     *         token.checkCancelled(); // Check periodically
     *         processItem(item);
     *     }
     * }
     * }</pre>
     *
     * @throws CancellationException if cancellation has been requested
     */
    public void checkCancelled() {
        if (cancelled) {
            throw new CancellationException(
                cancellationReason != null ? cancellationReason : "Operation was cancelled");
        }
    }

    /**
     * Throws a CancellationException with a custom message if cancelled.
     *
     * @param message The message to include in the exception
     * @throws CancellationException if cancellation has been requested
     */
    public void checkCancelled(String message) {
        if (cancelled) {
            String fullMessage = message;
            if (cancellationReason != null) {
                fullMessage = message + " (reason: " + cancellationReason + ")";
            }
            throw new CancellationException(fullMessage);
        }
    }

    // ==================== Listener Support ====================

    /**
     * Registers a listener to be called when cancellation occurs.
     *
     * <p>Listeners are called exactly once when cancellation is requested.
     * If the token is already cancelled, the listener is called immediately
     * on the calling thread.</p>
     *
     * <p><strong>Note:</strong> Listeners should be lightweight and non-blocking.
     * They are called synchronously during cancellation.</p>
     *
     * @param listener The listener to register (must not be null)
     * @throws NullPointerException if listener is null
     */
    public void addListener(Runnable listener) {
        Objects.requireNonNull(listener, "Listener cannot be null");

        // Synchronize on the same monitor used by cancel() so that the decision
        // "is cancelled already?" is atomic with "was this listener observable
        // to notifyListeners()?". Without this, a concurrent cancel() can both
        // iterate a CopyOnWriteArrayList snapshot that contains the listener
        // AND have remove() succeed here (CoW remove does not interfere with
        // in-flight snapshot iteration), causing double notification.
        boolean runImmediately;
        synchronized (this) {
            if (cancelled) {
                runImmediately = true;
            } else {
                cancellationListeners.add(listener);
                runImmediately = false;
            }
        }
        if (runImmediately) {
            // Run outside the monitor to avoid holding the cancel() lock
            // while executing arbitrary listener code.
            listener.run();
        }
    }

    /**
     * Removes a previously registered listener.
     *
     * @param listener The listener to remove
     * @return true if the listener was removed
     */
    public boolean removeListener(Runnable listener) {
        return cancellationListeners.remove(listener);
    }

    /**
     * Registers a consumer to receive the cancellation reason.
     *
     * <p>This is a convenience method for listeners that need the reason.</p>
     *
     * @param consumer The consumer to receive the cancellation reason
     */
    public void addReasonListener(Consumer<String> consumer) {
        addListener(() -> consumer.accept(cancellationReason));
    }

    /**
     * Notifies all registered listeners.
     */
    private void notifyListeners() {
        for (Runnable listener : cancellationListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                // Log but don't propagate - other listeners should still be notified
                System.getLogger(CancellationToken.class.getName())
                    .log(System.Logger.Level.WARNING,
                        "Cancellation listener threw exception", e);
            }
        }
    }

    // ==================== Factory Methods ====================

    /**
     * Creates a CancellationToken that is already cancelled.
     *
     * <p>This is useful for testing or for operations that should
     * be immediately cancelled.</p>
     *
     * @return A cancelled token
     */
    public static CancellationToken cancelled() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        return token;
    }

    /**
     * Creates a CancellationToken that is already cancelled with a reason.
     *
     * @param reason The cancellation reason
     * @return A cancelled token
     */
    public static CancellationToken cancelled(String reason) {
        CancellationToken token = new CancellationToken();
        token.cancel(reason);
        return token;
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        if (cancelled) {
            return "CancellationToken{cancelled=true, reason='" + cancellationReason + "'}";
        }
        return "CancellationToken{cancelled=false}";
    }
}
