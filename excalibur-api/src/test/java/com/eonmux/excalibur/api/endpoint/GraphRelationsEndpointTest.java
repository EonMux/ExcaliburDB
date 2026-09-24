package com.eonmux.excalibur.api.endpoint;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /v1/graph/relations} - the vocabulary relation-keyed settings and filters match.
 */
@DisplayName("Graph relations endpoint")
class GraphRelationsEndpointTest {

    private static final String PATH = GraphRelationsEndpoint.PATH;

    private GraphRouteFixture fixture;

    @BeforeEach
    void setUp(@TempDir Path root) throws IOException {
        fixture = new GraphRouteFixture(root);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private void stageAndBuild(String namespace) throws IOException {
        GraphRouteFixture.Response asserted = fixture.assertFacts("{\"facts\": ["
            + "{\"subject\": \"Ada Lovelace\", \"relation\": \"worked with\", "
            + "\"object\": \"Charles Babbage\"},"
            + "{\"subject\": \"Ada Lovelace\", \"relation\": \"born_in\", \"object\": \"London\"},"
            + "{\"subject\": \"Charles Babbage\", \"relation\": \"born_in\", "
            + "\"object\": \"London\"}"
            + "]}", namespace);
        assertThat(asserted.status()).isEqualTo(201);
        fixture.build(namespace);
    }

    @Test
    @DisplayName("lists every relation the served graph uses, with its edge count")
    void listsTheVocabulary() throws IOException {
        stageAndBuild(null);

        GraphRouteFixture.Response response = fixture.relations("GET", PATH, null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.string("namespace")).isEqualTo("shared");
        List<Map<String, Object>> relations = response.list("relations");
        assertThat(relations).extracting(r -> r.get("relation"))
            .containsExactly("born_in", "worked_with");
        assertThat(relations).extracting(r -> r.get("edges")).containsExactly(2L, 1L);
        assertThat(relations.get(0).get("relation_id")).isInstanceOf(Long.class);
        assertThat(response.at("next_cursor")).isNull();
    }

    @Test
    @DisplayName("pages by cursor, one relation at a time")
    void pages() throws IOException {
        stageAndBuild(null);

        List<Object> names = new ArrayList<>();
        String cursor = null;
        do {
            GraphRouteFixture.Response page = fixture.relations("GET",
                PATH + "?limit=1" + (cursor == null ? "" : "&cursor=" + cursor), null);
            assertThat(page.status()).isEqualTo(200);
            page.list("relations").forEach(r -> names.add(r.get("relation")));
            cursor = page.string("next_cursor");
        } while (cursor != null);

        assertThat(names).containsExactly("born_in", "worked_with");
    }

    @Test
    @DisplayName("reads the namespace the header names, and only that one")
    void isNamespaceScoped() throws IOException {
        stageAndBuild(null);
        assertThat(fixture.assertFacts("{\"subject\": \"a\", \"relation\": \"owns\", "
            + "\"object\": \"b\"}", "acme").status()).isEqualTo(201);
        fixture.build("acme");

        GraphRouteFixture.Response acme = fixture.relations("GET", PATH, "acme");

        assertThat(acme.list("relations")).extracting(r -> r.get("relation"))
            .containsExactly("owns");
    }

    @Test
    @DisplayName("no graph, or no namespace, is a 503 as the query answers it, creating nothing")
    void noGraph() throws IOException {
        GraphRouteFixture.Response unbuilt = fixture.relations("GET", PATH, null);
        GraphRouteFixture.Response missing = fixture.relations("GET", PATH, "nobody");

        assertThat(unbuilt.status()).isEqualTo(503);
        assertThat(unbuilt.string("code")).isEqualTo("graph_not_served");
        assertThat(missing.status()).isEqualTo(503);
        assertThat(fixture.tenants().describeNamespaces(null, 10).namespaces())
            .extracting(summary -> summary.id().value()).doesNotContain("nobody");
    }

    @Test
    @DisplayName("a bad limit, a foreign cursor or an unusable namespace is a 400")
    void refusals() throws IOException {
        stageAndBuild(null);

        for (String query : List.of("?limit=0", "?limit=501", "?limit=many", "?cursor=%25%25")) {
            assertThat(fixture.relations("GET", PATH + query, null).status()).as(query)
                .isEqualTo(400);
        }
        assertThat(fixture.relations("GET", PATH, "Not A Namespace!").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("any method but GET is a 405")
    void refusesOtherMethods() throws IOException {
        assertThat(fixture.relations("POST", PATH, null).status()).isEqualTo(405);
    }
}
