package excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import excalibur.api.bootstrap.GraphBuildService;
import excalibur.api.bootstrap.GraphTenant;
import excalibur.api.bootstrap.GraphTenants;
import excalibur.api.protocol.JsonText;
import excalibur.api.protocol.ResponseBody;
import io.excalibur.tenancy.NamespaceId;
import io.excalibur.tenancy.NamespaceRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

/**
 * {@code POST /v1/graph/build} - turns the facts ingestion has staged into the graph being served.
 *
 * <p>A build is a step rather than a consequence of ingesting, because the edge store is produced
 * whole: it is a sorted CSR, and the property that makes traversal bounded is exactly the one that
 * makes an append impossible. Rebuilding after every document would cost the whole graph per
 * document, so ingestion stages facts and this publishes them.</p>
 *
 * <p>Answers with what the build did, including the case where there was nothing to do. A node that
 * has staged nothing keeps serving the graph it already had - replacing it with an empty one would
 * turn every question into a refusal.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the service it calls serialises builds.</p>
 *
 * @author ExcaliburDB
 */
public class GraphBuildEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphBuildEndpoint.class);

    private final GraphTenants tenants;

    /**
     * Creates the endpoint.
     *
     * @param tenants The namespaces whose graphs can be built
     */
    public GraphBuildEndpoint(GraphTenants tenants) {
        this.tenants = Objects.requireNonNull(tenants, "tenants cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendJson(exchange, 405,
                "{\"error\": \"Method not allowed; use POST\"}");
            return;
        }

        NamespaceId namespace;
        try {
            namespace = GraphTenants.namespaceOf(
                exchange.getRequestHeaders().getFirst(GraphTenants.NAMESPACE_HEADER));
        } catch (IllegalArgumentException e) {
            ResponseBody.sendJson(exchange, 400,
                "{\"error\": \"" + JsonText.escape(e.getMessage()) + "\"}");
            return;
        }

        try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(namespace)) {
            GraphBuildService.BuildOutcome outcome = lease.resource().buildService().build();
            // The directory has been replaced, so the stack every other namespace-scoped request
            // would take is describing what was there before. Dropping it makes the next request
            // reopen what was just published.
            tenants.invalidate(namespace);

            ResponseBody.sendJson(exchange, 200,
                "{"
                    + "\"published\": " + outcome.published() + ", "
                    + "\"edges\": " + outcome.edges() + ", "
                    + "\"entities\": " + outcome.entities() + ", "
                    + "\"relations\": " + outcome.relations() + ", "
                    + "\"entities_with_text\": " + outcome.indexed() + ", "
                    + "\"entities_with_vectors\": " + outcome.embedded() + ", "
                    + "\"staged_facts\": " + outcome.staged() + ", "
                    + "\"namespace\": \"" + JsonText.escape(namespace.value()) + "\""
                    + "}");
        } catch (IOException | RuntimeException e) {
            // The build failed; whatever was being served is still being served, because the
            // directory is only replaced by a rename after the whole thing is assembled.
            //
            // RuntimeException is not defensive padding: a namespace that cannot be opened
            // arrives here as an UncheckedIOException from the registry, and catching only
            // IOException would let it escape the handler with no response sent at all.
            //
            // Logged with detail, answered without it: the message names the path the node
            // failed on, which a client has no business seeing.
            LOGGER.error("Graph build failed", e);
            ResponseBody.sendJson(exchange, 500, "{\"error\": \"Internal Server Error\"}");
        }
    }
}
