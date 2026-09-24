package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import com.eonmux.excalibur.api.bootstrap.GraphTenant;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.mcp.Json;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;

import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real namespaces on disk, and requests to the structured graph routes over them.
 *
 * <p>Graphs are built the way a node builds them, from what staging holds, so what the routes
 * read is what {@code POST /v1/graph/build} would have published.</p>
 */
final class GraphRouteFixture implements AutoCloseable {

    private final Path root;
    private final Map<String, String> settings;
    private GraphTenants tenants;
    private GraphEntitiesEndpoint entities;
    private GraphFactsEndpoint facts;
    private GraphRelationsEndpoint relations;

    GraphRouteFixture(Path root) throws IOException {
        this(root, Map.of());
    }

    /**
     * Namespaces configured with settings beyond the defaults.
     *
     * @param root     Where the namespaces live
     * @param settings Configuration properties to add
     */
    GraphRouteFixture(Path root, Map<String, String> settings) throws IOException {
        this.root = root;
        this.settings = Map.copyOf(settings);
        open();
    }

    private void open() throws IOException {
        Map<String, String> properties = new HashMap<>(settings);
        properties.put("excalibur.graph.ram-buffer-mb", "4.0");
        Config config = new Config(properties);
        tenants = new GraphTenants(root, config, MappingBudget.detect(), 4);
        entities = new GraphEntitiesEndpoint(tenants);
        facts = new GraphFactsEndpoint(tenants);
        relations = new GraphRelationsEndpoint(tenants);
    }

    /** Something done to the files while no namespace is open. */
    interface WhileClosed {
        void run(Path root) throws IOException;
    }

    /** Closes every namespace, does something to the files, and opens them again. */
    void restart(WhileClosed between) throws IOException {
        tenants.close();
        between.run(root);
        open();
    }

    GraphTenants tenants() {
        return tenants;
    }

    /** Borrows a namespace, creating it. */
    NamespaceRegistry.Lease<GraphTenant> borrow(String namespace) {
        return tenants.borrow(namespace == null ? NamespaceId.SHARED : NamespaceId.of(namespace));
    }

    /** Builds a namespace's graph from what it has staged, and serves it. */
    void build(String namespace) throws IOException {
        assertThat(tenants.build(namespace == null ? NamespaceId.SHARED
            : NamespaceId.of(namespace), () -> false).published()).isTrue();
    }

    Response get(String uri) throws IOException {
        return call(entities, "GET", uri, "", null);
    }

    Response get(String uri, String namespace) throws IOException {
        return call(entities, "GET", uri, "", namespace);
    }

    Response relations(String method, String uri, String namespace) throws IOException {
        return call(relations, method, uri, "", namespace);
    }

    Response entities(String method, String uri) throws IOException {
        return call(entities, method, uri, "", null);
    }

    Response assertFacts(String body) throws IOException {
        return call(facts, "POST", GraphFactsEndpoint.PATH, body, null);
    }

    Response assertFacts(String body, String namespace) throws IOException {
        return call(facts, "POST", GraphFactsEndpoint.PATH, body, namespace);
    }

    Response facts(String method, String uri, String body, String namespace) throws IOException {
        return call(facts, method, uri, body, namespace);
    }

    Response retract(String uri) throws IOException {
        return call(facts, "DELETE", uri, "", null);
    }

    /** The id of the one entity called {@code name} in the shared namespace's served graph. */
    long idOf(String name) throws IOException {
        Response found = get(GraphEntitiesEndpoint.PATH + "?name="
            + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8));
        assertThat(found.status()).isEqualTo(200);
        List<Map<String, Object>> listed = found.list("entities");
        assertThat(listed).as("one entity is named %s", name).hasSize(1);
        return (Long) listed.get(0).get("id");
    }

    private static Response call(HttpHandler handler, String method, String uri, String body,
                                 String namespace) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Headers requestHeaders = new Headers();
        if (namespace != null) {
            requestHeaders.set(GraphTenants.NAMESPACE_HEADER, namespace);
        }
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create(uri));
        when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestBody()).thenReturn(
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseBody()).thenReturn(out);

        handler.handle(exchange);

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(exchange).sendResponseHeaders(status.capture(), anyLong());
        return new Response(status.getValue(), out.toString(StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        tenants.close();
    }

    /** One captured HTTP response. */
    record Response(int status, String body) {

        Object json() {
            return Json.parse(body);
        }

        String string(String field) {
            return Json.stringAt(json(), field);
        }

        Object at(String field) {
            return Json.at(json(), field);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list(String field) {
            return (List<Map<String, Object>>) Json.at(json(), field);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> object(String field) {
            return (Map<String, Object>) Json.at(json(), field);
        }
    }
}
