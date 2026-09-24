package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.bootstrap.GraphTenant;
import com.eonmux.excalibur.graph.format.EdgeColumn;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /v1/graph/entities} and the routes under it, over graphs built from what was staged.
 */
@DisplayName("Graph entities endpoint")
class GraphEntitiesEndpointTest {

    private static final String PATH = GraphEntitiesEndpoint.PATH;

    private GraphRouteFixture fixture;

    /** The fact id {@code POST /v1/graph/facts} gave "Ada Lovelace worked_with Charles Babbage". */
    private long workedWith;

    @BeforeEach
    void setUp(@TempDir Path root) throws IOException {
        fixture = new GraphRouteFixture(root);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    /**
     * Six entities, asserted and extracted: Ada Lovelace is the subject of three facts, one of
     * them dated, and the object of two, one of them extracted from a document.
     */
    private void stageAndBuild() throws IOException {
        GraphRouteFixture.Response asserted = fixture.assertFacts("{\"facts\": ["
            + "{\"subject\": \"Ada Lovelace\", \"relation\": \"worked with\", "
            + "\"object\": \"Charles Babbage\"},"
            + "{\"subject\": \"Ada Lovelace\", \"relation\": \"born_in\", \"object\": \"London\"},"
            + "{\"subject\": \"Charles Babbage\", \"relation\": \"born_in\", "
            + "\"object\": \"London\"},"
            + "{\"subject\": \"Ada Lovelace\", \"relation\": \"lived_in\", "
            + "\"object\": \"Marylebone\", \"valid_from\": \"1835-01-01\", "
            + "\"valid_to\": \"1852-11-27\", \"confidence\": 0.8},"
            + "{\"subject\": \"Ada Byron\", \"relation\": \"same_as\", "
            + "\"object\": \"Ada Lovelace\"}"
            + "]}");
        assertThat(asserted.status()).isEqualTo(201);
        workedWith = (Long) asserted.list("facts").get(0).get("fact_id");
        try (NamespaceRegistry.Lease<GraphTenant> lease = fixture.borrow(null)) {
            lease.resource().staging().append(ExtractedTriple.alwaysValid(
                "Lord Byron", "father_of", "Ada Lovelace",
                new Provenance(SourceType.DOCUMENT, 7, 90, 3)));
            lease.resource().staging().flush();
        }
        fixture.build(null);
    }

    private static List<String> names(GraphRouteFixture.Response response) {
        return response.list("entities").stream().map(e -> (String) e.get("name")).toList();
    }

    private static List<String> rendered(List<Map<String, Object>> edges) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            out.add(edge.get("direction") + " " + name(edge, "subject") + " "
                + edge.get("relation") + " " + name(edge, "object"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String name(Map<String, Object> edge, String end) {
        return (String) ((Map<String, Object>) edge.get(end)).get("name");
    }

    /** Every page of a listing, following next_cursor from the first. */
    private List<Map<String, Object>> allPages(String uri, String field) throws IOException {
        List<Map<String, Object>> items = new ArrayList<>();
        String cursor = null;
        String separator = uri.contains("?") ? "&" : "?";
        do {
            GraphRouteFixture.Response page = fixture.get(
                cursor == null ? uri : uri + separator + "cursor=" + cursor);
            assertThat(page.status()).isEqualTo(200);
            items.addAll(page.list(field));
            cursor = page.string("next_cursor");
        } while (cursor != null);
        return items;
    }

    @Nested
    @DisplayName("finding entities")
    class Finding {

        @Test
        @DisplayName("every entity is listed in name order, and paging yields exactly one page's "
            + "worth")
        void listsEveryEntity() throws IOException {
            stageAndBuild();

            GraphRouteFixture.Response whole = fixture.get(PATH);
            assertThat(whole.status()).isEqualTo(200);
            assertThat(names(whole)).containsExactly("Ada Byron", "Ada Lovelace",
                "Charles Babbage", "London", "Lord Byron", "Marylebone");
            assertThat(whole.at("next_cursor")).isNull();
            assertThat(whole.string("namespace")).isEqualTo(NamespaceId.SHARED.value());

            assertThat(allPages(PATH + "?limit=2", "entities"))
                .isEqualTo(whole.list("entities"));
        }

        @Test
        @DisplayName("by exact name ignoring case, or by prefix")
        void byNameOrPrefix() throws IOException {
            stageAndBuild();

            GraphRouteFixture.Response named = fixture.get(PATH + "?name=%20ada%20LOVELACE%20");
            assertThat(names(named)).containsExactly("Ada Lovelace");
            Map<String, Object> ada = named.list("entities").get(0);
            assertThat(ada.get("out_degree")).isEqualTo(3L);
            assertThat(ada.get("in_degree")).isEqualTo(2L);

            assertThat(names(fixture.get(PATH + "?prefix=ADA"))).containsExactly(
                "Ada Byron", "Ada Lovelace");
            assertThat(names(fixture.get(PATH + "?name=Ada"))).isEmpty();
        }

        @Test
        @DisplayName("an entity is described with its edges counted by relation")
        void oneEntity() throws IOException {
            stageAndBuild();
            long ada = fixture.idOf("Ada Lovelace");

            GraphRouteFixture.Response found = fixture.get(PATH + "/" + ada);

            assertThat(found.status()).isEqualTo(200);
            Map<String, Object> entity = found.object("entity");
            assertThat(entity.get("id")).isEqualTo(ada);
            assertThat(entity.get("name")).isEqualTo("Ada Lovelace");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> relations =
                (List<Map<String, Object>>) entity.get("relations");
            assertThat(relations).extracting(r -> r.get("relation") + " " + r.get("out") + "/"
                    + r.get("in"))
                .containsExactlyInAnyOrder("worked_with 1/0", "born_in 1/0", "lived_in 1/0",
                    "same_as 0/1", "father_of 0/1");
        }

        @Test
        @DisplayName("an id the served graph does not hold is a 404; one that is not an id is a "
            + "400; a path that is no route is a 404")
        void notFound() throws IOException {
            stageAndBuild();
            long ada = fixture.idOf("Ada Lovelace");

            assertThat(fixture.get(PATH + "/999999").status()).isEqualTo(404);
            assertThat(fixture.get(PATH + "/999999/edges").status()).isEqualTo(404);
            assertThat(fixture.get(PATH + "/ada").status()).isEqualTo(400);
            assertThat(fixture.get(PATH + "/-1").status()).isEqualTo(400);
            assertThat(fixture.get(PATH + "/" + ada + "/friends").status()).isEqualTo(404);
            assertThat(fixture.get(PATH + "/" + ada + "/edges/more").status()).isEqualTo(404);
        }

        @Test
        @DisplayName("name with prefix, a bad limit and a foreign cursor are refused")
        void refusals() throws IOException {
            stageAndBuild();

            for (String query : List.of("?name=a&prefix=a", "?limit=0", "?limit=501",
                    "?limit=many", "?cursor=bm90LWEtY3Vyc29y")) {
                GraphRouteFixture.Response refused = fixture.get(PATH + query);
                assertThat(refused.status()).as(query).isEqualTo(400);
                assertThat(refused.string("error")).as(query).isNotBlank();
            }
        }

        @Test
        @DisplayName("only GET is served")
        void onlyGet() throws IOException {
            assertThat(fixture.entities("POST", PATH).status()).isEqualTo(405);
            assertThat(fixture.entities("DELETE", PATH + "/1").status()).isEqualTo(405);
        }
    }

    @Nested
    @DisplayName("an entity's edges")
    class Edges {

        @Test
        @DisplayName("outgoing, incoming, or both with outgoing first")
        void directions() throws IOException {
            stageAndBuild();
            long ada = fixture.idOf("Ada Lovelace");
            String edges = PATH + "/" + ada + "/edges";

            assertThat(rendered(fixture.get(edges + "?direction=out").list("edges")))
                .containsExactlyInAnyOrder("out Ada Lovelace worked_with Charles Babbage",
                    "out Ada Lovelace born_in London", "out Ada Lovelace lived_in Marylebone");
            assertThat(rendered(fixture.get(edges + "?direction=IN").list("edges")))
                .containsExactlyInAnyOrder("in Ada Byron same_as Ada Lovelace",
                    "in Lord Byron father_of Ada Lovelace");
            List<String> both = rendered(fixture.get(edges).list("edges"));
            assertThat(both).hasSize(5);
            assertThat(both.subList(0, 3)).allMatch(edge -> edge.startsWith("out "));
            assertThat(both.subList(3, 5)).allMatch(edge -> edge.startsWith("in "));
            assertThat(fixture.get(edges + "?direction=up").status()).isEqualTo(400);
        }

        @Test
        @DisplayName("narrowed to one relation, spelled as it would be staged")
        void byRelation() throws IOException {
            stageAndBuild();
            String edges = PATH + "/" + fixture.idOf("Ada Lovelace") + "/edges";

            assertThat(rendered(fixture.get(edges + "?relation=Worked%20With").list("edges")))
                .containsExactly("out Ada Lovelace worked_with Charles Babbage");
            GraphRouteFixture.Response unknown = fixture.get(edges + "?relation=taught");
            assertThat(unknown.status()).isEqualTo(200);
            assertThat(unknown.list("edges")).isEmpty();
            assertThat(unknown.at("next_cursor")).isNull();
        }

        @Test
        @DisplayName("as_of admits the edges holding on that day; absent or \"all\" is all of "
            + "valid time")
        void asOf() throws IOException {
            stageAndBuild();
            String out = PATH + "/" + fixture.idOf("Ada Lovelace") + "/edges?direction=out";

            assertThat(fixture.get(out + "&as_of=1840-01-01").list("edges")).hasSize(3);
            assertThat(rendered(fixture.get(out + "&as_of=1860-01-01T12:00:00Z").list("edges")))
                .as("she had left Marylebone by 1860")
                .doesNotContain("out Ada Lovelace lived_in Marylebone")
                .hasSize(2);
            assertThat(fixture.get(out + "&as_of=all").list("edges")).hasSize(3);
            assertThat(fixture.get(out).list("edges")).hasSize(3);
            assertThat(fixture.get(out + "&as_of=last%20week").status()).isEqualTo(400);
        }

        @Test
        @DisplayName("each edge carries its interval, confidence and provenance, and an asserted "
            + "one its fact id")
        void carriesProvenance() throws IOException {
            stageAndBuild();
            long ada = fixture.idOf("Ada Lovelace");
            List<Map<String, Object>> edges =
                fixture.get(PATH + "/" + ada + "/edges").list("edges");

            Map<String, Object> worked = edge(edges, "worked_with");
            assertThat(worked.get("fact_id")).isEqualTo(workedWith);
            assertThat(worked.get("valid_from")).isNull();
            assertThat(worked.get("valid_to")).isNull();
            assertThat(worked.get("confidence")).isEqualTo(1.0);
            assertThat(provenance(worked)).containsEntry("source_type", "USER_ASSERTED")
                .containsEntry("source_id", workedWith)
                .containsEntry("extractor_version", 0L);

            Map<String, Object> lived = edge(edges, "lived_in");
            assertThat(lived.get("valid_from")).isEqualTo("1835-01-01");
            assertThat(lived.get("valid_to")).isEqualTo("1852-11-27");
            assertThat(lived.get("confidence")).isEqualTo(0.8);

            Map<String, Object> father = edge(edges, "father_of");
            assertThat(father).doesNotContainKey("fact_id");
            assertThat(provenance(father)).containsEntry("source_type", "DOCUMENT")
                .containsEntry("source_id", 7L)
                .containsEntry("extractor_version", 3L);
            @SuppressWarnings("unchecked")
            Map<String, Object> subject = (Map<String, Object>) father.get("subject");
            assertThat(subject.get("id")).isEqualTo(fixture.idOf("Lord Byron"));
        }

        @Test
        @DisplayName("a page at a time yields exactly what one page does")
        void pagingIsStable() throws IOException {
            stageAndBuild();
            String edges = PATH + "/" + fixture.idOf("Ada Lovelace") + "/edges";

            List<Map<String, Object>> whole = fixture.get(edges).list("edges");
            for (int limit = 1; limit <= 5; limit++) {
                assertThat(allPages(edges + "?limit=" + limit, "edges"))
                    .as("limit %d", limit)
                    .isEqualTo(whole);
            }
            assertThat(fixture.get(edges + "?cursor=%25%25").status()).isEqualTo(400);
        }

        private static Map<String, Object> edge(List<Map<String, Object>> edges,
                                                String relation) {
            return edges.stream().filter(e -> relation.equals(e.get("relation")))
                .findFirst().orElseThrow();
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> provenance(Map<String, Object> edge) {
            return (Map<String, Object>) edge.get("provenance");
        }
    }

    @Nested
    @DisplayName("namespaces and the graph being served")
    class Serving {

        @Test
        @DisplayName("each namespace reads its own graph, and a missing one creates nothing")
        void namespacesAreIsolated() throws IOException {
            stageAndBuild();
            assertThat(fixture.assertFacts("{\"subject\": \"Grace Hopper\", "
                + "\"relation\": \"wrote\", \"object\": \"FLOW-MATIC\"}", "tenant-a").status())
                .isEqualTo(201);
            fixture.build("tenant-a");

            assertThat(names(fixture.get(PATH, "tenant-a")))
                .containsExactly("FLOW-MATIC", "Grace Hopper");
            assertThat(names(fixture.get(PATH + "?prefix=grace"))).isEmpty();
            assertThat(names(fixture.get(PATH + "?name=Ada%20Lovelace", "tenant-a"))).isEmpty();

            GraphRouteFixture.Response missing = fixture.get(PATH, "nobody-here");
            assertThat(missing.status()).isEqualTo(503);
            assertThat(fixture.tenants().namespacesOnDisk())
                .doesNotContain(NamespaceId.of("nobody-here"));
            assertThat(fixture.get(PATH, "../escape").status()).isEqualTo(400);
        }

        @Test
        @DisplayName("staged but never built is answered as the query answers it")
        void neverBuilt() throws IOException {
            assertThat(fixture.assertFacts("{\"subject\": \"A\", \"relation\": \"r\", "
                + "\"object\": \"B\"}").status()).isEqualTo(201);

            GraphRouteFixture.Response response = fixture.get(PATH);

            assertThat(response.status()).isEqualTo(503);
            assertThat(response.string("error")).isEqualTo(GraphQueryEndpoint.NO_GRAPH);
        }

        @Test
        @DisplayName("a graph withdrawn from service is not read")
        void withdrawn() throws IOException {
            stageAndBuild();
            try (NamespaceRegistry.Lease<GraphTenant> lease = fixture.borrow(null)) {
                assertThat(lease.resource().holder().withdraw()).isTrue();
            }

            assertThat(fixture.get(PATH).status()).isEqualTo(503);
            assertThat(fixture.get(PATH + "/1/edges").status()).isEqualTo(503);
        }

        @Test
        @DisplayName("a damaged graph is not read")
        void damaged() throws IOException {
            stageAndBuild();
            Path directory;
            try (NamespaceRegistry.Lease<GraphTenant> lease = fixture.borrow(null)) {
                directory = lease.resource().directory();
            }

            fixture.restart(root -> {
                Path column = directory.resolve(EdgeColumn.EDGES_FWD.fileName());
                byte[] bytes = Files.readAllBytes(column);
                bytes[bytes.length / 2] ^= 0x20;
                Files.write(column, bytes);
            });

            GraphRouteFixture.Response response = fixture.get(PATH + "?name=Ada%20Lovelace");
            assertThat(response.status()).isEqualTo(503);
            assertThat(response.string("error")).isEqualTo(GraphQueryEndpoint.NO_GRAPH);
        }
    }

    @Nested
    @DisplayName("error codes")
    class ErrorCodes {

        @Test
        @DisplayName("no graph being served is graph_not_served, as the query says it")
        void noGraph() throws IOException {
            GraphRouteFixture.Response response = fixture.get(PATH);

            assertThat(response.status()).isEqualTo(503);
            assertThat(response.string("code")).isEqualTo("graph_not_served");
        }

        @Test
        @DisplayName("an id the served graph does not hold is entity_not_found")
        void unknownEntity() throws IOException {
            stageAndBuild();

            for (String uri : List.of(PATH + "/999999", PATH + "/999999/edges")) {
                GraphRouteFixture.Response response = fixture.get(uri);
                assertThat(response.status()).as(uri).isEqualTo(404);
                assertThat(response.string("code")).as(uri).isEqualTo("entity_not_found");
            }
        }

        @Test
        @DisplayName("a malformed namespace is invalid_namespace, a malformed query "
            + "invalid_request, and a path naming no route not_found")
        void refusals() throws IOException {
            stageAndBuild();

            GraphRouteFixture.Response namespace = fixture.get(PATH, "Not A Namespace");
            assertThat(namespace.status()).isEqualTo(400);
            assertThat(namespace.string("code")).isEqualTo("invalid_namespace");

            GraphRouteFixture.Response query = fixture.get(PATH + "?name=a&prefix=b");
            assertThat(query.status()).isEqualTo(400);
            assertThat(query.string("code")).isEqualTo("invalid_request");

            GraphRouteFixture.Response route = fixture.get(PATH + "/1/friends");
            assertThat(route.status()).isEqualTo(404);
            assertThat(route.string("code")).isEqualTo("not_found");

            GraphRouteFixture.Response method = fixture.entities("POST", PATH);
            assertThat(method.status()).isEqualTo(405);
            assertThat(method.string("code")).isEqualTo("method_not_allowed");
        }
    }
}
