package com.eonmux.excalibur.api.protocol;

import com.sun.net.httpserver.HttpExchange;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Values that belong to one request, kept beside its exchange for the length of it.
 *
 * <h2>Why not the exchange's own attributes</h2>
 * <p>{@link HttpExchange#setAttribute} reads as though it stores on the exchange, and on the JDK
 * this node runs on it does not: the JDK's exchange hands every attribute through to its
 * {@code HttpContext}, which is shared by every request on the route. A value one request set is
 * therefore what the next request on the same route reads, and a "set once, then reuse" value -
 * a request id, the client the guard resolved - is settled by the first request a route ever
 * served and repeated for every one after it. The state here is keyed by the exchange itself, so
 * nothing one request stores is visible to another.</p>
 *
 * <h2>Lifetime</h2>
 * <p>The outermost handler of every route calls {@link #clear} once the exchange is finished,
 * which is what normally releases a request's state. The keys are held weakly as well, so an
 * exchange handled by something that never clears - a handler driven directly, in a test - is
 * released with the exchange rather than kept for the life of the process.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use. An exchange is handled on one thread at a time, and the map of
 * exchanges is synchronised.</p>
 *
 * @author ExcaliburDB
 */
public final class RequestState {

    private static final Map<HttpExchange, Map<String, Object>> STATE =
        new WeakHashMap<>();

    private RequestState() {
    }

    /**
     * Reads a value stored for a request.
     *
     * @param exchange The request's exchange
     * @param name     The value's name
     * @return The value, or null if this request stored none under that name
     * @throws NullPointerException if either argument is null
     */
    public static Object get(HttpExchange exchange, String name) {
        Objects.requireNonNull(exchange, "exchange must not be null");
        Objects.requireNonNull(name, "name must not be null");
        synchronized (STATE) {
            Map<String, Object> values = STATE.get(exchange);
            return values == null ? null : values.get(name);
        }
    }

    /**
     * Stores a value for a request, replacing any stored under the same name.
     *
     * @param exchange The request's exchange
     * @param name     The value's name
     * @param value    The value; null removes it
     * @throws NullPointerException if exchange or name is null
     */
    public static void put(HttpExchange exchange, String name, Object value) {
        Objects.requireNonNull(exchange, "exchange must not be null");
        Objects.requireNonNull(name, "name must not be null");
        synchronized (STATE) {
            if (value == null) {
                Map<String, Object> values = STATE.get(exchange);
                if (values != null) {
                    values.remove(name);
                }
                return;
            }
            STATE.computeIfAbsent(exchange, key -> new HashMap<>()).put(name, value);
        }
    }

    /**
     * Forgets everything stored for a request, once it is finished.
     *
     * @param exchange The request's exchange
     * @throws NullPointerException if exchange is null
     */
    public static void clear(HttpExchange exchange) {
        Objects.requireNonNull(exchange, "exchange must not be null");
        synchronized (STATE) {
            STATE.remove(exchange);
        }
    }
}
