package com.eonmux.excalibur.api.endpoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether an operator has taken this node out of rotation.
 *
 * <h2>Why a node needs to be able to say "not me"</h2>
 * <p>There was no way to. The only thing that stopped this node answering was
 * {@code ExcaliburDBServer.stop(int)}, reachable from the SIGTERM hook and nowhere else, so a
 * rolling restart or a manual compaction began with the load balancer still routing to a node
 * that was about to stop - and the in-flight requests at that moment were dropped. Draining is
 * the half of a graceful shutdown that has to happen before the shutdown: the node reports
 * itself unready, the load balancer stops choosing it, its in-flight work finishes, and only
 * then is it safe to stop.</p>
 *
 * <p>It is a readiness flag and nothing more. A drained node keeps serving every request it is
 * sent, because the traffic still arriving during a drain is precisely the traffic that has
 * not noticed yet, and refusing it would turn a graceful handover into the outage the drain
 * exists to avoid.</p>
 *
 * <h2>Not persisted</h2>
 * <p>A restart clears it. A node that came up drained would need a second call to join the
 * pool it was restarted into, and the common case - drain, restart, serve - would then need an
 * operator to remember the undrain. The uncommon case, a node that must stay out after a
 * restart, is the orchestrator's to hold, not this process's.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. The whole state is one immutable record swapped atomically, so a reader
 * never sees a drain with the reason of the previous one.</p>
 *
 * @author ExcaliburDB
 */
public final class ServiceReadiness {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceReadiness.class);

    /**
     * The drain state, as one value.
     *
     * @param draining Whether the node is asking not to be sent traffic
     * @param sinceMs  When the drain began, or 0 while serving
     * @param reason   What an operator gave as the reason, or null
     */
    private record State(boolean draining, long sinceMs, String reason) {
    }

    private static final State SERVING = new State(false, 0, null);

    private final AtomicReference<State> state = new AtomicReference<>(SERVING);

    /**
     * Whether this node is asking not to be sent traffic.
     *
     * @return true once a drain has begun and until it ends
     */
    public boolean isDraining() {
        return state.get().draining();
    }

    /**
     * When the current drain began.
     *
     * @return Epoch milliseconds, or 0 while serving
     */
    public long drainingSinceMs() {
        return state.get().sinceMs();
    }

    /**
     * Why the node was drained.
     *
     * @return The reason an operator gave, or null when none was given or none is in force
     */
    public String reason() {
        return state.get().reason();
    }

    /**
     * Takes the node out of rotation.
     *
     * <p>Idempotent, and deliberately so: a retried request must not restart the clock, or the
     * "drained for N seconds" an operator is watching before they restart would never advance.
     * The first reason is the one kept, for the same reason.</p>
     *
     * @param reason Why, for the log and for the readiness response; may be null
     * @return true if this call began the drain, false if one was already in force
     */
    public boolean beginDrain(String reason) {
        State began = new State(true, System.currentTimeMillis(), reason);
        State previous = state.compareAndExchange(SERVING, began);
        if (previous.draining()) {
            return false;
        }
        LOGGER.warn("Node drained{}: readiness will report unready until it is undrained",
            reason == null ? "" : " (" + reason + ")");
        return true;
    }

    /**
     * Puts the node back into rotation.
     *
     * @return true if this call ended a drain, false if the node was already serving
     */
    public boolean endDrain() {
        State previous = state.getAndSet(SERVING);
        if (!previous.draining()) {
            return false;
        }
        LOGGER.info("Node undrained after {}ms: readiness will report ready again",
            System.currentTimeMillis() - previous.sinceMs());
        return true;
    }
}
