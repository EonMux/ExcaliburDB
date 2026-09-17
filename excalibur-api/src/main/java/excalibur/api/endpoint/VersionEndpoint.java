package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.api.protocol.JsonText;
import excalibur.api.protocol.ResponseBody;

import java.io.IOException;

/**
 * HTTP endpoint for {@code GET /v1/version}: which build is answering.
 *
 * <p>See {@link BuildInfo} for why a process that cannot name its own build makes both an
 * incident and a rollback guesswork. Uptime is served alongside because the second question
 * after "which build" is "did this one restart", and the two are read together.</p>
 *
 * <h2>Response Format</h2>
 * <pre>{@code
 * {
 *   "name": "ExcaliburDB",
 *   "version": "1.0.0-SNAPSHOT",
 *   "jvm": "21.0.5",
 *   "startedAtMs": 1736899200000,
 *   "uptimeMs": 43200000
 * }
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless and thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public final class VersionEndpoint implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            ResponseBody.sendError(exchange, 405, "Method Not Allowed - Use GET");
            return;
        }
        ResponseBody.sendJson(exchange, 200, "{\"name\": \""
            + JsonText.escape(BuildInfo.title()) + "\", \"version\": \""
            + JsonText.escape(BuildInfo.version()) + "\", \"jvm\": \""
            + JsonText.escape(BuildInfo.jvm()) + "\", \"startedAtMs\": "
            + BuildInfo.startedAtMs() + ", \"uptimeMs\": "
            + BuildInfo.uptimeMs() + "}");
    }
}
