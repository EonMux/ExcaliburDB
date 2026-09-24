package com.eonmux.excalibur.graph.browse;

import com.eonmux.excalibur.graph.dictionary.LabelDictionary;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.erasure.ScopedStagedErasure;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphBuilder;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.store.MappedEdgeStore;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading a served graph by entity: who is in it, under which id, and what it says about them.
 */
@DisplayName("Graph catalog")
class GraphCatalogTest {

    private static Provenance document(long id) {
        return Provenance.of(SourceType.DOCUMENT, id, 0.9, 1);
    }

    private static ExtractedTriple fact(String subject, String relation, String object) {
        return ExtractedTriple.alwaysValid(subject, relation, object, document(1));
    }

    /** A built graph and a catalog over it, closed together. */
    private record Opened(MappedEdgeStore edges, LabelDictionary entities,
                          LabelDictionary relations, GraphCatalog catalog)
            implements AutoCloseable {

        static Opened at(Path graph) throws IOException {
            MappedEdgeStore edges = MappedEdgeStore.open(graph);
            LabelDictionary entities =
                LabelDictionary.open(graph, GraphBuilder.ENTITY_DICTIONARY, "E");
            LabelDictionary relations =
                LabelDictionary.open(graph, GraphBuilder.RELATION_DICTIONARY, "R");
            return new Opened(edges, entities, relations,
                new GraphCatalog(edges, entities, relations));
        }

        @Override
        public void close() {
            edges.close();
            entities.close();
            relations.close();
        }
    }

    /** Stages the facts, builds them, and opens a catalog over the result. */
    private static Opened built(Path dir, List<ExtractedTriple> facts) throws IOException {
        try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("staging"))) {
            for (ExtractedTriple triple : facts) {
                staging.append(triple);
            }
            GraphBuilder.build(staging, dir.resolve("graph"));
        }
        return Opened.at(dir.resolve("graph"));
    }

    /** Every item of a listing, a page at a time. */
    private static <T> List<T> drain(Function<String, CatalogPage<T>> pages) {
        List<T> all = new ArrayList<>();
        String cursor = null;
        int guard = 0;
        do {
            CatalogPage<T> page = pages.apply(cursor);
            all.addAll(page.items());
            cursor = page.next().orElse(null);
            assertThat(++guard).isLessThan(1_000);
        } while (cursor != null);
        return all;
    }

    private static final List<ExtractedTriple> PEOPLE = List.of(
        fact("Ada Lovelace", "knows", "Charles Babbage"),
        fact("ada", "knows", "Mary Somerville"),
        fact("Charles Babbage", "built", "Difference Engine"),
        fact("Mary Somerville", "knows", "Ada Lovelace"),
        fact("Alan Turing", "admired", "Ada Lovelace"));

    @Nested
    @DisplayName("entities")
    class Entities {

        @Test
        @DisplayName("every entity is listed once, in name order, a page at a time")
        void listsEveryEntityInNameOrder(@TempDir Path dir) throws IOException {
            try (Opened graph = built(dir, PEOPLE)) {
                List<String> names = drain(cursor -> graph.catalog().allEntities(2, cursor))
                    .stream().map(CatalogEntity::name).toList();

                assertThat(names).containsExactly("ada", "Ada Lovelace", "Alan Turing",
                    "Charles Babbage", "Difference Engine", "Mary Somerville");
            }
        }

        @Test
        @DisplayName("a name matches exactly, ignoring case and surrounding space")
        void exactName(@TempDir Path dir) throws IOException {
            try (Opened graph = built(dir, PEOPLE)) {
                CatalogPage<CatalogEntity> page =
                    graph.catalog().entitiesNamed("  ADA LOVELACE ", 10, null);

                assertThat(page.items()).singleElement().satisfies(ada -> {
                    assertThat(ada.name()).isEqualTo("Ada Lovelace");
                    assertThat(ada.outDegree()).isEqualTo(1);
                    assertThat(ada.inDegree()).isEqualTo(2);
                    assertThat(ada.relations()).isEmpty();
                });
                assertThat(page.nextCursor()).isNull();
                assertThat(graph.catalog().entitiesNamed("Ada", 10, null).items())
                    .extracting(CatalogEntity::name).containsExactly("ada");
                assertThat(graph.catalog().entitiesNamed("Nobody", 10, null).items()).isEmpty();
            }
        }

        @Test
        @DisplayName("names differing only in case are distinct entities, in id order")
        void sharedFoldedName(@TempDir Path dir) throws IOException {
            try (Opened graph = built(dir, List.of(fact("Apple", "rival_of", "APPLE"),
                    fact("apple", "is_a", "fruit")))) {
                List<CatalogEntity> named =
                    drain(cursor -> graph.catalog().entitiesNamed("apple", 1, cursor));

                assertThat(named).extracting(CatalogEntity::name)
                    .containsExactly("Apple", "APPLE", "apple");
                assertThat(named).extracting(CatalogEntity::id).isSorted();
            }
        }

        @Test
        @DisplayName("a prefix lists the names that start with it, and stops after them")
        void prefix(@TempDir Path dir) throws IOException {
            try (Opened graph = built(dir, PEOPLE)) {
                List<String> names = drain(cursor ->
                    graph.catalog().entitiesStartingWith("a", 1, cursor))
                    .stream().map(CatalogEntity::name).toList();

                assertThat(names).containsExactly("ada", "Ada Lovelace", "Alan Turing");
                assertThat(graph.catalog().entitiesStartingWith("zz", 5, null).items())
                    .isEmpty();
            }
        }

        @Test
        @DisplayName("an entity is described with its edges divided by relation")
        void details(@TempDir Path dir) throws IOException {
            try (Opened graph = built(dir, PEOPLE)) {
                long ada = graph.catalog().entitiesNamed("Ada Lovelace", 1, null).items()
                    .get(0).id();

                CatalogEntity entity = graph.catalog().entity(ada).orElseThrow();

                assertThat(entity.name()).isEqualTo("Ada Lovelace");
                assertThat(entity.relations()).extracting(CatalogEntity.RelationDegree::relation,
                        CatalogEntity.RelationDegree::out, CatalogEntity.RelationDegree::in)
                    .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("knows", 1L, 1L),
                        org.assertj.core.groups.Tuple.tuple("admired", 0L, 1L));
                assertThat(entity.relations()).extracting(CatalogEntity.RelationDegree::relationId)
                    .isSorted();
            }
        }

        @Test
        @DisplayName("an id the graph does not hold is not found")
        void unknownIds(@TempDir Path dir) throws IOException {
            try (Opened graph = built(dir, PEOPLE)) {
                assertThat(graph.catalog().entity(-1)).isEmpty();
                assertThat(graph.catalog().entity(10_000)).isEmpty();
                assertThat(graph.catalog().holds(10_000)).isFalse();
                assertThat(graph.catalog().edges(10_000, null, EdgeDirection.BOTH, null, 5, null))
                    .isEmpty();
            }
        }

        @Test
        @DisplayName("an erased entity is neither listed nor found")
        void erasedEntities(@TempDir Path dir) throws IOException {
            int ada;
            try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("staging"))) {
                for (ExtractedTriple triple : PEOPLE) {
                    staging.append(triple);
                }
                ada = staging.findEntity("Ada Lovelace");
                staging.erase(ScopedStagedErasure.of(new ErasureScope.Entity(ada)));
                GraphBuilder.build(staging, dir.resolve("graph"));
            }
            try (Opened graph = Opened.at(dir.resolve("graph"))) {
                assertThat(graph.catalog().entity(ada)).isEmpty();
                assertThat(drain(cursor -> graph.catalog().allEntities(3, cursor)))
                    .extracting(CatalogEntity::id).doesNotContain((long) ada)
                    .hasSize(5);
                assertThat(graph.catalog().entitiesStartingWith("(erased", 5, null).items())
                    .isEmpty();
            }
        }

        @Test
        @DisplayName("a blank name, a bad limit and a foreign cursor are refused")
        void refusals(@TempDir Path dir) throws IOException {
            try (Opened graph = built(dir, PEOPLE)) {
                GraphCatalog catalog = graph.catalog();
                assertThatThrownBy(() -> catalog.entitiesNamed(" ", 5, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
                assertThatThrownBy(() -> catalog.entitiesStartingWith(null, 5, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("prefix");
                assertThatThrownBy(() -> catalog.allEntities(0, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
                assertThatThrownBy(() -> catalog.allEntities(GraphCatalog.MAX_LIMIT + 1, null))
                    .isInstanceOf(IllegalArgumentException.class);
                for (String cursor : List.of("%%%", encoded("x:1:a"), encoded("n:-4:a"),
                        encoded("n:one:a"), encoded("n:1"))) {
                    assertThatThrownBy(() -> catalog.allEntities(5, cursor))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("cursor");
                }
            }
        }
    }

    @Nested
    @DisplayName("edges")
    class Edges {

        private static final List<ExtractedTriple> HUB = List.of(
            fact("Hub", "a", "X"),
            fact("Hub", "b", "Y"),
            fact("Hub", "a", "Y"),
            new ExtractedTriple("Hub", "a", "X", 100, 200, document(2)),
            fact("Hub", "self", "Hub"),
            fact("Z", "a", "Hub"),
            fact("Y", "b", "Hub"));

        private static long hub(Opened graph) {
            return graph.catalog().entitiesNamed("Hub", 1, null).items().get(0).id();
        }

        private static List<CatalogEdge> all(Opened graph, long id, String relation,
                                             EdgeDirection direction, TemporalFilter asOf,
                                             int limit) {
            return drain(cursor -> graph.catalog()
                .edges(id, relation, direction, asOf, limit, cursor).orElseThrow());
        }

        private static List<String> rendered(List<CatalogEdge> edges) {
            return edges.stream().map(edge -> edge.direction().wireName() + " " + edge.subject()
                + " " + edge.relation() + " " + edge.object()).toList();
        }

        @Test
        @DisplayName("outgoing then incoming, each by relation and other endpoint")
        void order(@TempDir Path dir) throws IOException {
            try (Opened graph = built(dir, HUB)) {
                List<CatalogEdge> edges = all(graph, hub(graph), null, EdgeDirection.BOTH,
                    null, 50);

                assertThat(rendered(edges)).containsExactly(
                    "out Hub a X", "out Hub a X", "out Hub a Y", "out Hub b Y",
                    "out Hub self Hub", "in Z a Hub", "in Y b Hub");
            }
        }

        @Test
        @DisplayName("a page at a time yields exactly what one page does, whatever the size")
        void pagingIsStable(@TempDir Path dir) throws IOException {
            try (Opened graph = built(dir, HUB)) {
                long hub = hub(graph);
                List<CatalogEdge> whole = all(graph, hub, null, EdgeDirection.BOTH, null, 50);
                for (int limit = 1; limit <= 7; limit++) {
                    assertThat(all(graph, hub, null, EdgeDirection.BOTH, null, limit))
                        .as("limit %d", limit).containsExactlyElementsOf(whole);
                }
                CatalogPage<CatalogEdge> exact = graph.catalog()
                    .edges(hub, null, EdgeDirection.BOTH, null, whole.size(), null).orElseThrow();
                assertThat(exact.nextCursor()).as("a page that holds the rest ends").isNull();
            }
        }

        @Test
        @DisplayName("one direction, one relation, and an instant each narrow the edges")
        void filters(@TempDir Path dir) throws IOException {
            try (Opened graph = built(dir, HUB)) {
                long hub = hub(graph);

                assertThat(rendered(all(graph, hub, null, EdgeDirection.IN, null, 2)))
                    .as("a self-loop is incoming too when only incoming edges are asked for")
                    .containsExactly("in Z a Hub", "in Y b Hub", "in Hub self Hub");
                assertThat(rendered(all(graph, hub, " A ", EdgeDirection.OUT, null, 2)))
                    .containsExactly("out Hub a X", "out Hub a X", "out Hub a Y");
                assertThat(all(graph, hub, "missing", EdgeDirection.BOTH, null, 2)).isEmpty();
                assertThat(rendered(all(graph, hub, "a", EdgeDirection.OUT,
                    new TemporalFilter(50), 1)))
                    .as("the dated edge did not yet hold on day 50")
                    .containsExactly("out Hub a X", "out Hub a Y");
            }
        }

        @Test
        @DisplayName("each edge carries its interval and provenance as stored")
        void carriesWhatIsStored(@TempDir Path dir) throws IOException {
            try (Opened graph = built(dir, HUB)) {
                List<CatalogEdge> dated = all(graph, hub(graph), "a", EdgeDirection.OUT,
                    null, 10).stream().filter(edge -> edge.validFrom() == 100).toList();

                assertThat(dated).singleElement().satisfies(edge -> {
                    assertThat(edge.validTo()).isEqualTo(200);
                    assertThat(edge.provenance()).isEqualTo(document(2));
                    assertThat(edge.object()).isEqualTo("X");
                    assertThat(edge.objectId()).isEqualTo(
                        graph.catalog().entitiesNamed("X", 1, null).items().get(0).id());
                });
            }
        }

        @Test
        @DisplayName("a cursor carried across a rebuild resumes after the edge it was issued for")
        void cursorSurvivesARebuild(@TempDir Path dir) throws IOException {
            String cursor;
            try (GraphStagingStore staging = GraphStagingStore.open(dir.resolve("staging"))) {
                for (ExtractedTriple triple : HUB) {
                    staging.append(triple);
                }
                GraphBuilder.build(staging, dir.resolve("graph"));
                try (Opened graph = Opened.at(dir.resolve("graph"))) {
                    CatalogPage<CatalogEdge> first = graph.catalog().edges(hub(graph), null,
                        EdgeDirection.OUT, null, 3, null).orElseThrow();
                    assertThat(rendered(first.items()))
                        .containsExactly("out Hub a X", "out Hub a X", "out Hub a Y");
                    cursor = first.nextCursor();
                }
                // A fact sorting before the cursor, and one after it.
                staging.append(fact("Hub", "a", "X"));
                staging.append(fact("Hub", "c", "X"));
                GraphBuilder.build(staging, dir.resolve("graph"));
            }
            try (Opened graph = Opened.at(dir.resolve("graph"))) {
                CatalogPage<CatalogEdge> rest = graph.catalog().edges(hub(graph), null,
                    EdgeDirection.OUT, null, 10, cursor).orElseThrow();
                assertThat(rendered(rest.items()))
                    .containsExactly("out Hub b Y", "out Hub self Hub", "out Hub c X");
            }
        }

        @Test
        @DisplayName("a cursor from the incoming half reads nothing outgoing")
        void cursorFromTheIncomingHalf(@TempDir Path dir) throws IOException {
            try (Opened graph = built(dir, HUB)) {
                long hub = hub(graph);
                CatalogPage<CatalogEdge> first = graph.catalog()
                    .edges(hub, null, EdgeDirection.IN, null, 1, null).orElseThrow();

                assertThat(graph.catalog().edges(hub, null, EdgeDirection.OUT, null, 5,
                    first.nextCursor()).orElseThrow().items()).isEmpty();
                assertThat(rendered(graph.catalog().edges(hub, null, EdgeDirection.BOTH, null,
                    5, first.nextCursor()).orElseThrow().items()))
                    .as("resumed after the first incoming edge, which was Z's")
                    .containsExactly("in Y b Hub");
            }
        }

        @Test
        @DisplayName("an entity cursor, a damaged one, a bad limit and a blank relation are "
            + "refused")
        void foreignCursors(@TempDir Path dir) throws IOException {
            try (Opened graph = built(dir, HUB)) {
                long hub = hub(graph);
                String entityCursor = graph.catalog().allEntities(1, null).nextCursor();
                for (String cursor : List.of(entityCursor, encoded("e:x:1:1:1"),
                        encoded("e:o:1:1"), encoded("e:o:99999999999:1:1"))) {
                    assertThatThrownBy(() -> graph.catalog()
                        .edges(hub, null, EdgeDirection.BOTH, null, 5, cursor))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("cursor");
                }
                assertThatThrownBy(() -> graph.catalog()
                    .edges(hub, null, EdgeDirection.BOTH, null, 0, null))
                    .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> graph.catalog()
                    .edges(hub, "  ", EdgeDirection.BOTH, null, 5, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'relation'");
            }
        }
    }

    @Test
    @DisplayName("directions read as a request spells them")
    void directions() {
        assertThat(EdgeDirection.parse(" OUT ")).isEqualTo(EdgeDirection.OUT);
        assertThat(EdgeDirection.parse("in")).isEqualTo(EdgeDirection.IN);
        assertThat(EdgeDirection.BOTH.wireName()).isEqualTo("both");
        assertThatThrownBy(() -> EdgeDirection.parse("sideways"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("direction");
        assertThatThrownBy(() -> EdgeDirection.parse(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static String encoded(String text) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
