package excalibur.api.protocol;

import com.sun.net.httpserver.HttpExchange;
import io.excalibur.metrics.ResponseCounters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes an HTTP response body.
 *
 * <p>The one place that decides how a body reaches the client, so that HEAD is handled the
 * same way everywhere. A HEAD response must carry the headers its GET would carry and no
 * body at all; the JDK's server enforces the second half by handing back a closed stream,
 * so an endpoint that writes unconditionally throws {@code IOException: stream closed}
 * after having already answered 200. The exception unwinds into the handler wrapper and is
 * logged as an error, which turns a load balancer's health probe into a steady stream of
 * errors from a server that is working.</p>
 *
 * <h2>Where responses are counted</h2>
 * <p>Being the one exit also makes this the one place a response can be counted without a
 * route having to remember to. Before that, the node published a latency histogram over the
 * queries that succeeded and nothing at all about the ones that did not: a rate-limit
 * rejection, an authentication failure and an unhandled handler exception each wrote a log
 * line and were then gone, so neither the request rate nor the error rate could be derived
 * from anything the process exported. See {@link ResponseCounters}.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless and thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public final class ResponseBody {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResponseBody.class);

    private ResponseBody() {
        // Utility class
    }

    /**
     * Sends a response, omitting the body for HEAD requests.
     *
     * @param exchange The exchange to respond on
     * @param statusCode The HTTP status code
     * @param body The body bytes; sent only when the request has one
     * @throws IOException if the response cannot be written
     */
    public static void send(HttpExchange exchange, int statusCode, byte[] body) throws IOException {
        // Counted before the write, not after it. A response whose body fails half way to the
        // client was still a response the node decided on, and counting only the ones that
        // completed would make a network fault look like a drop in traffic. The HEAD path
        // below returns early, so a load balancer's probes would otherwise be invisible.
        countResponse(statusCode);
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            // Set the length as a header rather than through sendResponseHeaders: the JDK
            // refuses a content length on a HEAD response and logs a warning for one, but
            // a HEAD is supposed to report the length its GET would have returned.
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
            return;
        }

        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /**
     * Records one response, without letting the metrics sink affect the response.
     *
     * <p>A counter that cannot take a value must not turn a served request into a failed one:
     * the node's ability to answer does not depend on its ability to report that it did. The
     * failure is swallowed deliberately and only here, where the alternative is strictly
     * worse than the missing count.</p>
     *
     * @param statusCode The status being sent
     */
    private static void countResponse(int statusCode) {
        try {
            ResponseCounters.record(statusCode);
        } catch (RuntimeException e) {
            LOGGER.debug("Could not count a {} response: {}", statusCode, e.toString());
        }
    }

    /**
     * Sends a UTF-8 JSON response, omitting the body for HEAD requests.
     *
     * @param exchange The exchange to respond on
     * @param statusCode The HTTP status code
     * @param json The JSON text
     * @throws IOException if the response cannot be written
     */
    public static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        send(exchange, statusCode, json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Sends the common error envelope.
     *
     * <p>The message is escaped rather than trusted: an error naming a field the caller
     * supplied carries the caller's own text back out, and an unescaped quote in it would
     * produce a body no client can parse.</p>
     *
     * @param exchange   The exchange to answer
     * @param statusCode The HTTP status
     * @param message    The reason, in a form a client can act on
     * @throws IOException if the response cannot be written
     */
    public static void sendError(HttpExchange exchange, int statusCode, String message)
            throws IOException {
        sendJson(exchange, statusCode, "{\"error\": \"" + JsonText.escape(message) + "\"}");
    }
}
