package excalibur.api.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.api.protocol.JsonText;
import excalibur.api.protocol.ResponseBody;
import excalibur.api.security.RequestGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The combinators every route on this node is built from.
 *
 * <p>{@code HttpServer} offers prefix dispatch and nothing else: no exact matching, no method
 * routing, no error handling around a handler that throws. Each of those is supplied here, as a
 * handler wrapping a handler, so that the route table reads as one list of paths rather than as
 * the same three guards written out per context.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless. Every method returns a handler that closes over its arguments alone, so the
 * handlers are as thread-safe as what they delegate to.</p>
 *
 * @author ExcaliburDB
 */
final class HttpRouting {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpRouting.class);

    private HttpRouting() {
    }

    /**
     * Serves a context only for the exact path it was registered under.
     *
     * <p>{@code HttpServer} dispatches on the longest matching <em>prefix</em>, so a context at
     * {@code /v1/stats} also receives {@code /v1/statsfoo}, and {@code /health} receives
     * {@code /healthz}. A client with a typo in its URL would be answered as though it had
     * none - a liveness probe pointed at the wrong path reading back "healthy" is the worst
     * case, and the documented contract is that anything this server does not serve answers
     * 404. One trailing slash is the same path, because that is what a browser and most HTTP
     * clients produce for a bare route.</p>
     *
     * @param context  The path this context is registered under
     * @param delegate What serves the path itself
     * @param notFound What answers every other path reaching this context
     * @return A handler that answers 404 for every other path under the context
     */
    static HttpHandler exactPath(String context, HttpHandler delegate,
                                 HttpHandler notFound) {
        return exchange -> {
            if (isExactly(context, exchange.getRequestURI().getPath())) {
                delegate.handle(exchange);
            } else {
                notFound.handle(exchange);
            }
        };
    }

    /**
     * Serves a context for its own path and for the paths directly beneath it.
     *
     * <p>For {@code /v1/query}, whose cancel route carries the query id in the path. Anything
     * deeper than one segment, or under a longer prefix that merely starts the same way, is
     * still 404.</p>
     *
     * @param context  The path this context is registered under
     * @param delegate What serves the path and its children
     * @param notFound What answers every other path reaching this context
     * @return A handler that answers 404 for paths outside the context
     */
    static HttpHandler pathOrChild(String context, HttpHandler delegate,
                                   HttpHandler notFound) {
        return exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (isExactly(context, path) || path.startsWith(context + "/")) {
                delegate.handle(exchange);
            } else {
                notFound.handle(exchange);
            }
        };
    }

    /** Whether a request path is a context's own path, with or without one trailing slash. */
    private static boolean isExactly(String context, String path) {
        return context.equals(path) || (context + "/").equals(path);
    }

    /**
     * Wraps a handler with logging and error handling.
     *
     * @param handler The original handler
     * @return A wrapped handler with logging
     */
    static HttpHandler wrapHandler(HttpHandler handler) {
        return exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            long start = System.currentTimeMillis();

            try {
                LOGGER.debug("Request: {} {} from {}", method, path,
                    exchange.getRemoteAddress().getAddress().getHostAddress());

                handler.handle(exchange);

                long duration = System.currentTimeMillis() - start;
                LOGGER.debug("Response: {} {} - {}ms", method, path, duration);
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - start;
                LOGGER.error("Error handling {} {}: {} ({}ms)", method, path, e.getMessage(), duration, e);

                if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
                    sendError(exchange, 500, "Internal Server Error");
                }
            } finally {
                exchange.close();
            }
        };
    }

    /**
     * Routes one context by request method.
     *
     * <p>Two operations on one noun rather than two nouns: registering a source and stopping one
     * are the same resource, and inventing a second path for the second verb is what HTTP methods
     * exist to avoid.</p>
     *
     * <p>Every branch is a whole handler, the fall-through included, so each one carries its own
     * {@link RequestGuard} decision. A router that wrote the 405 itself would answer it below the
     * guard: an anonymous caller could then spend a path's worth of work per request, at line rate,
     * on a method the route does not serve. Logging, error handling and closing the exchange are
     * {@link #wrapHandler}'s, as they are for every other context.</p>
     *
     * @param firstMethod  The method the first handler serves
     * @param first        The handler for {@code firstMethod}
     * @param secondMethod The method the second handler serves
     * @param second       The handler for {@code secondMethod}
     * @param otherwise    The handler for every other method
     * @return A handler that dispatches on the request method
     */
    static HttpHandler createMethodRouter(String firstMethod, HttpHandler first,
                                          String secondMethod, HttpHandler second,
                                          HttpHandler otherwise) {
        return exchange -> {
            String method = exchange.getRequestMethod();
            if (firstMethod.equalsIgnoreCase(method)) {
                first.handle(exchange);
            } else if (secondMethod.equalsIgnoreCase(method)) {
                second.handle(exchange);
            } else {
                otherwise.handle(exchange);
            }
        };
    }

    /**
     * A handler that answers 405 and nothing else.
     *
     * @param message The reason, naming the methods the route does serve
     * @return A handler for the methods a route does not serve
     */
    static HttpHandler methodNotAllowed(String message) {
        return exchange -> sendError(exchange, 405, message);
    }

    /**
     * A handler that answers 503 and names what the node was started without.
     *
     * <p>Used where a route exists but one half of it needs a component this deployment does not
     * have. Serving 404 instead would say the route is not part of the API, which sends the
     * caller looking for a different version rather than at its own configuration.</p>
     *
     * @param message What is missing, and for which operation
     * @return A handler for a method this node cannot serve
     */
    static HttpHandler unavailable(String message) {
        return exchange -> sendError(exchange, 503, message);
    }

    /**
     * Handles requests to unknown paths with 404.
     */
    static void handleNotFound(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        LOGGER.debug("404 Not Found: {}", path);
        sendError(exchange, 404, "Not Found");
    }

    /**
     * Sends an error response.
     */
    private static void sendError(HttpExchange exchange, int statusCode, String message)
            throws IOException {
        String json = String.format("{\"error\": \"%s\"}", JsonText.escape(message));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        ResponseBody.send(exchange, statusCode, bytes);
    }
}
