package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.bootstrap.GraphErasureService;
import com.eonmux.excalibur.api.bootstrap.GraphTenant;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.StagedAssertion;
import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST} and {@code DELETE /v1/graph/facts}: facts staged under ids of their own, served
 * after the next build, and retracted through the erasure path.
 */
@DisplayName("Graph facts endpoint")
class GraphFactsEndpointTest {

    private static final String PATH = GraphFactsEndpoint.PATH;
    private static final String ADA_WORKED_WITH_BABBAGE = "{\"subject\": \"Ada Lovelace\", "
        + "\"relation\": \"Worked With\", \"object\": \"Charles Babbage\"}";

    private GraphRouteFixture fixture;
    private Path root;

    @BeforeEach
    void setUp(@TempDir Path root) throws IOException {
        this.root = root;
        fixture = new GraphRouteFixture(root);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private List<StagedAssertion> staged() throws IOException {
        try (NamespaceRegistry.Lease<GraphTenant> lease = fixture.borrow(null)) {
            return lease.resource().staging().assertions();
        }
    }

    private long assertOne(String body) throws IOException {
        GraphRouteFixture.Response response = fixture.assertFacts(body);
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return (Long) response.list("facts").get(0).get("fact_id");
    }

    /** The relations of the edges the served graph holds for an entity, in either direction. */
    private List<String> servedRelations(String entity) throws IOException {
        GraphRouteFixture.Response found = fixture.get(GraphEntitiesEndpoint.PATH + "?name="
            + java.net.URLEncoder.encode(entity, java.nio.charset.StandardCharsets.UTF_8));
        if (found.status() != 200 || found.list("entities").isEmpty()) {
            return List.of();
        }
        long id = (Long) found.list("entities").get(0).get("id");
        return fixture.get(GraphEntitiesEndpoint.PATH + "/" + id + "/edges").list("edges")
            .stream().map(edge -> (String) edge.get("relation")).toList();
    }

    @Nested
    @DisplayName("asserting")
    class Asserting {

        @Test
        @DisplayName("a request refused by validation creates no namespace")
        void aRefusedRequestCreatesNoNamespace() throws IOException {
            List<String> refused = List.of(
                "not json",
                "[]",
                "{\"subject\": \"A\", \"relation\": \"r\"}",
                "{\"facts\": []}",
                "{\"subject\": \"A\", \"relation\": \"r\", \"object\": \"B\", "
                    + "\"valid_from\": \"yesterday\"}",
                "{\"subject\": \"A\", \"relation\": \"r\", \"object\": \"B\", "
                    + "\"confidence\": 2}");
            for (String body : refused) {
                GraphRouteFixture.Response response = fixture.assertFacts(body, "fresh");

                assertThat(response.status()).as(body).isEqualTo(400);
                assertThat(root.resolve("fresh"))
                    .as("a request that fails leaves no tenant behind: %s", body)
                    .doesNotExist();
            }
            assertThat(fixture.facts("POST", GraphFactsEndpoint.PATH + "/7",
                ADA_WORKED_WITH_BABBAGE, "fresh").status()).isEqualTo(404);
            assertThat(root.resolve("fresh")).doesNotExist();

            assertThat(fixture.assertFacts(ADA_WORKED_WITH_BABBAGE, "fresh").status())
                .as("one that is accepted creates it").isEqualTo(201);
            assertThat(root.resolve("fresh")).isDirectory();
        }

        @Test
        @DisplayName("a fact is staged under a fact id, cleaned as extraction cleans, and served "
            + "only after the next build")
        void stagedThenServed() throws IOException {
            GraphRouteFixture.Response response = fixture.assertFacts(ADA_WORKED_WITH_BABBAGE);

            assertThat(response.status()).isEqualTo(201);
            assertThat(response.at("staged")).isEqualTo(1L);
            assertThat(response.at("served")).isEqualTo(Boolean.FALSE);
            assertThat(response.string("detail")).contains("/v1/graph/build");
            Map<String, Object> fact = response.list("facts").get(0);
            assertThat(fact).containsEntry("subject", "Ada Lovelace")
                .containsEntry("relation", "worked_with")
                .containsEntry("object", "Charles Babbage")
                .containsEntry("confidence", 1.0)
                .containsEntry("valid_from", null)
                .containsEntry("valid_to", null);
            long factId = (Long) fact.get("fact_id");
            assertThat(staged()).extracting(StagedAssertion::factId).containsExactly(factId);

            assertThat(fixture.get(GraphEntitiesEndpoint.PATH).status())
                .as("nothing is served before a build")
                .isEqualTo(503);
            fixture.build(null);
            assertThat(servedRelations("Ada Lovelace")).containsExactly("worked_with");

            assertOne("{\"subject\": \"Ada Lovelace\", \"relation\": \"born_in\", "
                + "\"object\": \"London\"}");
            assertThat(servedRelations("Ada Lovelace"))
                .as("a fact asserted after a build waits for the next one")
                .containsExactly("worked_with");
            fixture.build(null);
            assertThat(servedRelations("Ada Lovelace"))
                .containsExactlyInAnyOrder("worked_with", "born_in");
        }

        @Test
        @DisplayName("a batch is staged together, each fact under an id of its own, rising")
        void batch() throws IOException {
            GraphRouteFixture.Response response = fixture.assertFacts("{\"facts\": ["
                + "{\"subject\": \"A\", \"relation\": \"r\", \"object\": \"B\", "
                + "\"valid_from\": \"2001-02-03\", \"valid_to\": \"2004-05-06\", "
                + "\"confidence\": 0.25},"
                + "{\"subject\": \"B\", \"relation\": \"r\", \"object\": \"C\", "
                + "\"confidence\": 0}"
                + "]}");

            assertThat(response.status()).isEqualTo(201);
            List<Map<String, Object>> facts = response.list("facts");
            assertThat(facts).hasSize(2);
            assertThat((Long) facts.get(1).get("fact_id"))
                .isGreaterThan((Long) facts.get(0).get("fact_id"));
            assertThat(facts.get(0)).containsEntry("valid_from", "2001-02-03")
                .containsEntry("valid_to", "2004-05-06")
                .containsEntry("confidence", 0.25);
            assertThat(facts.get(1)).containsEntry("confidence", 0.0);
            assertThat(staged()).hasSize(2);
        }

        @Test
        @DisplayName("a request with any malformed fact stages none of them")
        void refusals() throws IOException {
            String tooLong = "x".repeat(201);
            StringBuilder tooMany = new StringBuilder("{\"facts\": [");
            for (int i = 0; i <= GraphFactsEndpoint.MAX_FACTS; i++) {
                tooMany.append(i == 0 ? "" : ",").append(ADA_WORKED_WITH_BABBAGE);
            }
            tooMany.append("]}");

            for (String body : List.of(
                    "{", "[]", "{\"facts\": []}", tooMany.toString(), "{\"facts\": [1]}",
                    "{\"facts\": {}}",
                    "{\"subject\": \"A\", \"relation\": \"r\"}",
                    "{\"subject\": 5, \"relation\": \"r\", \"object\": \"B\"}",
                    "{\"subject\": \"" + tooLong + "\", \"relation\": \"r\", \"object\": \"B\"}",
                    "{\"subject\": \"A\", \"relation\": \"" + tooLong + "\", \"object\": \"B\"}",
                    "{\"subject\": \"(erased 3)\", \"relation\": \"r\", \"object\": \"B\"}",
                    "{\"subject\": \"A\", \"relation\": \"  \", \"object\": \"B\"}",
                    "{\"subject\": \"A\", \"relation\": \"r\", \"object\": \"B\", "
                        + "\"valid_from\": \"yesterday\"}",
                    "{\"subject\": \"A\", \"relation\": \"r\", \"object\": \"B\", "
                        + "\"valid_from\": 20010203}",
                    "{\"subject\": \"A\", \"relation\": \"r\", \"object\": \"B\", "
                        + "\"valid_from\": \"2005-01-01\", \"valid_to\": \"2001-01-01\"}",
                    "{\"subject\": \"A\", \"relation\": \"r\", \"object\": \"B\", "
                        + "\"valid_from\": \"2005-01-01\", \"valid_to\": \"2005-01-01\"}",
                    "{\"subject\": \"A\", \"relation\": \"r\", \"object\": \"B\", "
                        + "\"confidence\": 1.5}",
                    "{\"subject\": \"A\", \"relation\": \"r\", \"object\": \"B\", "
                        + "\"confidence\": \"high\"}",
                    "{\"facts\": [" + ADA_WORKED_WITH_BABBAGE + ", {\"subject\": \"A\"}]}")) {
                GraphRouteFixture.Response refused = fixture.assertFacts(body);
                assertThat(refused.status()).as(body).isEqualTo(400);
                assertThat(refused.string("error")).as(body).isNotBlank();
            }
            assertThat(staged()).isEmpty();
            assertThat(fixture.assertFacts(ADA_WORKED_WITH_BABBAGE, "../escape").status())
                .isEqualTo(400);
        }

        @Test
        @DisplayName("facts are asserted into the namespace the header names, creating it")
        void intoANamespace() throws IOException {
            assertThat(fixture.assertFacts(ADA_WORKED_WITH_BABBAGE, "tenant-a").status())
                .isEqualTo(201);

            assertThat(fixture.tenants().namespacesOnDisk()).contains(NamespaceId.of("tenant-a"));
            assertThat(staged()).as("the shared namespace holds none of it").isEmpty();
        }

        @Test
        @DisplayName("only POST and DELETE are served, and POST only on the collection")
        void methods() throws IOException {
            assertThat(fixture.facts("PUT", PATH, ADA_WORKED_WITH_BABBAGE, null).status())
                .isEqualTo(405);
            assertThat(fixture.facts("POST", PATH + "/7", ADA_WORKED_WITH_BABBAGE, null)
                .status()).isEqualTo(404);
            assertThat(fixture.facts("POST", PATH + "/", ADA_WORKED_WITH_BABBAGE, null)
                .status()).isEqualTo(201);
        }
    }

    @Nested
    @DisplayName("retracting")
    class Retracting {

        @Test
        @DisplayName("by fact id, compacted now: gone from staging and from the served graph")
        void byIdCompacted() throws IOException {
            long worked = assertOne(ADA_WORKED_WITH_BABBAGE);
            long born = assertOne("{\"subject\": \"Ada Lovelace\", \"relation\": \"born_in\", "
                + "\"object\": \"London\"}");
            fixture.build(null);

            GraphRouteFixture.Response response = fixture.retract(PATH + "/" + worked
                + "?compact=true");

            assertThat(response.status()).as(response.body()).isEqualTo(202);
            assertThat(response.at("fact_ids")).isEqualTo(List.of(worked));
            assertThat(response.list("request_ids")).hasSize(1);
            assertThat(response.at("compacted")).isEqualTo(Boolean.TRUE);
            assertThat(response.at("edges_removed")).isEqualTo(1L);
            assertThat(response.at("outstanding")).isEqualTo(0L);
            assertThat(staged()).extracting(StagedAssertion::factId).containsExactly(born);
            assertThat(servedRelations("Ada Lovelace")).containsExactly("born_in");

            fixture.build(null);
            assertThat(servedRelations("Ada Lovelace"))
                .as("a rebuild does not bring it back")
                .containsExactly("born_in");
            assertThat(fixture.retract(PATH + "/" + worked).status())
                .as("once applied there is nothing left to retract")
                .isEqualTo(404);
        }

        @Test
        @DisplayName("by fact id, left to the policy: recorded now, served until compacted")
        void byIdLeftToThePolicy(@TempDir Path elsewhere) throws IOException {
            // A policy that one retraction out of two facts does not satisfy.
            fixture.close();
            fixture = new GraphRouteFixture(elsewhere,
                Map.of("excalibur.graph.erasure-tombstone-ratio", "0.9"));
            long worked = assertOne(ADA_WORKED_WITH_BABBAGE);
            assertOne("{\"subject\": \"Ada Lovelace\", \"relation\": \"born_in\", "
                + "\"object\": \"London\"}");
            fixture.build(null);

            GraphRouteFixture.Response response = fixture.retract(PATH + "/" + worked);

            assertThat(response.status()).isEqualTo(202);
            assertThat(response.at("compacted")).isEqualTo(Boolean.FALSE);
            assertThat(response.at("outstanding")).isEqualTo(1L);
            assertThat(response.string("detail")).contains("served until a compaction");
            assertThat(servedRelations("Ada Lovelace"))
                .containsExactlyInAnyOrder("worked_with", "born_in");

            try (NamespaceRegistry.Lease<GraphTenant> lease = fixture.borrow(null)) {
                assertThat(lease.resource().erasureService().compactNow().ran()).isTrue();
            }
            assertThat(servedRelations("Ada Lovelace")).containsExactly("born_in");
            assertThat(staged()).hasSize(1);
        }

        @Test
        @DisplayName("by subject, relation and object: every asserted fact stating it, and no "
            + "extracted one")
        void byTriple() throws IOException {
            long first = assertOne(ADA_WORKED_WITH_BABBAGE);
            long second = assertOne(ADA_WORKED_WITH_BABBAGE);
            long other = assertOne("{\"subject\": \"Ada Lovelace\", \"relation\": \"born_in\", "
                + "\"object\": \"London\"}");
            try (NamespaceRegistry.Lease<GraphTenant> lease = fixture.borrow(null)) {
                lease.resource().staging().append(ExtractedTriple.alwaysValid(
                    "Ada Lovelace", "worked_with", "Charles Babbage",
                    new Provenance(SourceType.DOCUMENT, 11, 90, 1)));
                lease.resource().staging().flush();
            }
            fixture.build(null);

            GraphRouteFixture.Response response = fixture.retract(PATH
                + "?subject=%20ada%20lovelace&relation=Worked%20With&object=Charles%20Babbage"
                + "&compact=true");

            assertThat(response.status()).as(response.body()).isEqualTo(404);
            response = fixture.retract(PATH
                + "?subject=Ada%20Lovelace&relation=Worked%20With&object=Charles%20Babbage"
                + "&compact=true");
            assertThat(response.status()).as(response.body()).isEqualTo(202);
            assertThat(response.at("fact_ids")).isEqualTo(List.of(first, second));
            assertThat(staged()).extracting(StagedAssertion::factId).containsExactly(other);
            assertThat(servedRelations("Ada Lovelace"))
                .as("the extracted statement of the same triple stays")
                .containsExactlyInAnyOrder("worked_with", "born_in");
        }

        @Test
        @DisplayName("nothing staged to retract is a 404, and records nothing")
        void notFound() throws IOException {
            long worked = assertOne(ADA_WORKED_WITH_BABBAGE);

            assertThat(fixture.retract(PATH + "/" + (worked + 1000)).status()).isEqualTo(404);
            assertThat(fixture.retract(PATH + "?subject=Ada%20Lovelace&relation=born_in"
                + "&object=London").status()).isEqualTo(404);
            assertThat(fixture.retract(PATH + "?subject=Nobody&relation=worked_with"
                + "&object=Charles%20Babbage").status()).isEqualTo(404);
            try (NamespaceRegistry.Lease<GraphTenant> lease = fixture.borrow(null)) {
                assertThat(lease.resource().erasureService().outstandingCount()).isZero();
            }

            assertThat(fixture.facts("DELETE", PATH + "/" + worked, "", "nobody-here").status())
                .isEqualTo(404);
            assertThat(fixture.tenants().namespacesOnDisk())
                .doesNotContain(NamespaceId.of("nobody-here"));
            assertThat(fixture.retract(PATH + "/" + worked + "/again").status()).isEqualTo(404);
        }

        @Test
        @DisplayName("a request that cannot be read exactly is refused")
        void refusals() throws IOException {
            long worked = assertOne(ADA_WORKED_WITH_BABBAGE);

            for (String uri : List.of(PATH + "/fact", PATH + "/-4",
                    PATH + "/" + worked + "?subject=Ada%20Lovelace",
                    PATH + "?subject=Ada%20Lovelace&relation=worked_with",
                    PATH, PATH + "/" + worked + "?compact=soon")) {
                GraphRouteFixture.Response refused = fixture.retract(uri);
                assertThat(refused.status()).as(uri).isEqualTo(400);
            }
            assertThat(staged()).hasSize(1);
        }
    }

    @Test
    @DisplayName("erasing an entity erases the facts asserted about it, and their ids are never "
        + "handed out again")
    void entityErasureCoversAssertions() throws IOException {
        long worked = assertOne(ADA_WORKED_WITH_BABBAGE);
        long born = assertOne("{\"subject\": \"Charles Babbage\", \"relation\": \"born_in\", "
            + "\"object\": \"London\"}");
        long kept = assertOne("{\"subject\": \"Grace Hopper\", \"relation\": \"wrote\", "
            + "\"object\": \"FLOW-MATIC\"}");
        fixture.build(null);
        long babbage = fixture.idOf("Charles Babbage");

        try (NamespaceRegistry.Lease<GraphTenant> lease = fixture.borrow(null)) {
            GraphErasureService erasure = lease.resource().erasureService();
            erasure.submit(new ErasureScope.Entity(babbage));
            assertThat(erasure.compactNow().ran()).isTrue();
        }

        assertThat(staged()).extracting(StagedAssertion::factId).containsExactly(kept);
        assertThat(servedRelations("Ada Lovelace")).isEmpty();
        assertThat(servedRelations("Grace Hopper")).containsExactly("wrote");
        assertThat(fixture.get(GraphEntitiesEndpoint.PATH + "?name=Charles%20Babbage")
            .list("entities")).isEmpty();

        GraphRouteFixture.Response again = fixture.assertFacts(ADA_WORKED_WITH_BABBAGE);
        assertThat(again.status()).isEqualTo(201);
        assertThat((Long) again.list("facts").get(0).get("fact_id"))
            .isGreaterThan(Math.max(worked, Math.max(born, kept)));
        assertThat(fixture.retract(PATH + "/" + worked).status())
            .as("the erased fact's id names nothing now")
            .isEqualTo(404);
    }

    @Nested
    @DisplayName("error codes")
    class ErrorCodes {

        @Test
        @DisplayName("a malformed namespace is invalid_namespace on both methods")
        void invalidNamespace() throws IOException {
            GraphRouteFixture.Response asserted =
                fixture.facts("POST", PATH, ADA_WORKED_WITH_BABBAGE, "Not A Namespace");
            GraphRouteFixture.Response retracted =
                fixture.facts("DELETE", PATH + "/1", "", "Not A Namespace");

            assertThat(asserted.status()).isEqualTo(400);
            assertThat(asserted.string("code")).isEqualTo("invalid_namespace");
            assertThat(retracted.status()).isEqualTo(400);
            assertThat(retracted.string("code")).isEqualTo("invalid_namespace");
        }

        @Test
        @DisplayName("a malformed fact is invalid_request")
        void malformedFact() throws IOException {
            GraphRouteFixture.Response response = fixture.assertFacts("{\"subject\": \"Ada\"}");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.string("code")).isEqualTo("invalid_request");
        }

        @Test
        @DisplayName("nothing staged to retract is fact_not_found")
        void nothingToRetract() throws IOException {
            long worked = assertOne(ADA_WORKED_WITH_BABBAGE);

            GraphRouteFixture.Response byId = fixture.retract(PATH + "/" + (worked + 1000));
            GraphRouteFixture.Response byTriple = fixture.retract(PATH
                + "?subject=Nobody&relation=worked_with&object=Charles%20Babbage");
            GraphRouteFixture.Response elsewhere =
                fixture.facts("DELETE", PATH + "/" + worked, "", "nobody-here");

            for (GraphRouteFixture.Response response : List.of(byId, byTriple, elsewhere)) {
                assertThat(response.status()).isEqualTo(404);
                assertThat(response.string("code")).isEqualTo("fact_not_found");
            }
        }
    }

    @Nested
    @DisplayName("namespace admission")
    class Admission {

        @Test
        @DisplayName("with explicit creation required, a namespace nobody created is refused "
            + "and nothing is written")
        void explicitCreationIsRequired(@TempDir Path root) throws IOException {
            fixture.close();
            fixture = new GraphRouteFixture(root,
                Map.of("excalibur.graph.require-explicit-namespaces", "true"));

            GraphRouteFixture.Response refused =
                fixture.assertFacts(ADA_WORKED_WITH_BABBAGE, "typo-tenant");

            assertThat(refused.status()).isEqualTo(404);
            assertThat(refused.string("code")).isEqualTo("namespace_not_found");
            assertThat(fixture.tenants().namespacesOnDisk())
                .doesNotContain(NamespaceId.of("typo-tenant"));

            fixture.tenants().create(NamespaceId.of("typo-tenant"));
            assertThat(fixture.assertFacts(ADA_WORKED_WITH_BABBAGE, "typo-tenant").status())
                .isEqualTo(201);
            assertThat(fixture.assertFacts(ADA_WORKED_WITH_BABBAGE).status())
                .as("the shared namespace always exists")
                .isEqualTo(201);
        }

        @Test
        @DisplayName("past the namespace cap, asserting into a new namespace is refused")
        void theCapHolds(@TempDir Path root) throws IOException {
            fixture.close();
            fixture = new GraphRouteFixture(root, Map.of("excalibur.graph.max-namespaces", "1"));

            assertThat(fixture.assertFacts(ADA_WORKED_WITH_BABBAGE, "tenant-a").status())
                .isEqualTo(201);
            GraphRouteFixture.Response refused =
                fixture.assertFacts(ADA_WORKED_WITH_BABBAGE, "tenant-b");

            assertThat(refused.status()).isEqualTo(429);
            assertThat(refused.string("code")).isEqualTo("namespace_limit_reached");
            assertThat(fixture.tenants().namespacesOnDisk())
                .doesNotContain(NamespaceId.of("tenant-b"));
        }
    }
}
