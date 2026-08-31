package excalibur.api.protocol;

import com.sun.net.httpserver.HttpExchange;

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
 * <h2>Thread Safety</h2>
 * <p>Stateless and thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public final class ResponseBody {

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
}
