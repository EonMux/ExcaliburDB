package com.eonmux.excalibur.api.embedded;

import com.eonmux.excalibur.api.bootstrap.ChunkIndexFactory;
import com.eonmux.excalibur.api.bootstrap.NodeAssembly;
import com.eonmux.excalibur.core.datadir.DataDirectoryLock;
import com.eonmux.excalibur.core.datadir.DataDirectoryLockedException;
import com.eonmux.excalibur.core.datadir.LockHolder;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.source.SourceDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The embedded facade, over a real data directory.
 *
 * <p>What it promises is that an embedded node is the node the server runs: documents it is given
 * survive a restart, a document supplied again replaces its previous version, an erased one is
 * gone from every signal, and a query takes the route {@code POST /v1/query} would take on the
 * same configuration - degrading to the surviving signal rather than failing.</p>
 */
@DisplayName("Embedded node")
@Timeout(120)
class ExcaliburEmbeddedTest {

    private static final int DIMENSION = 128;

    private static final String TIERING_CHUNK = "handbook:tiering.txt:chunk:0";

    @TempDir
    Path dataDir;

    @TempDir
    Path configDir;

    /** A node with no model configured: the hosted default and no key. */
    private ExcaliburEmbedded.Builder lexicalNode() {
        return ExcaliburEmbedded.builder(dataDir);
    }

    /** A node embedding through a keyless local endpoint. */
    private ExcaliburEmbedded.Builder vectorNode(FakeEmbeddingEndpoint embeddings) {
        return ExcaliburEmbedded.builder(dataDir)
            .property("excalibur.llm.embedding-base-url", embeddings.baseUrl())
            .property("excalibur.llm.embedding-model", "hashed-words")
            .property("excalibur.llm.embeddings-dimensions", String.valueOf(DIMENSION))
            .property("excalibur.query.vector-dimensions", String.valueOf(DIMENSION))
            .property("excalibur.llm.chat-enabled", "false");
    }

    /**
     * Supplies the same document under {@code team} and under {@code team:wiki}, whose id extends
     * it, plus one under {@code other} - so only attribution, not the query, tells their hits
     * apart.
     */
    private static void supplyOverlappingSources(ExcaliburEmbedded db) throws Exception {
        List<EmbeddedDocument> same = List.of(EmbeddedDocument.of("tiering.txt",
            "Cold segments are moved to cheaper disks by the tiering policy."));
        db.supply("team", same);
        db.supply("team:wiki", same);
        db.supply("other", same);
    }

    /** Asserts every hit of a scoped search is the {@code team} source's own. */
    private static void assertOnlyTeam(SearchAnswer answer) {
        assertThat(answer.hits()).extracting(SearchHit::chunkId)
            .containsExactly("team:tiering.txt:chunk:0");
    }

    private static List<EmbeddedDocument> handbook() {
        return List.of(
            EmbeddedDocument.of("tiering.txt",
                "Cold segments are moved to cheaper disks by the tiering policy."),
            EmbeddedDocument.of("hnsw.txt",
                "The HNSW graph links every vector to its nearest neighbours."),
            EmbeddedDocument.of("backup.txt",
                "Backups copy the write log and the catalog to object storage."));
    }

    @Nested
    @DisplayName("with no model")
    class Lexical {

        @Test
        @DisplayName("answers from BM25, with each hit's text")
        void answersLexically() throws Exception {
            try (ExcaliburEmbedded db = lexicalNode().open()) {
                assertThat(db.route()).contains(SearchRoute.LEXICAL);

                SupplyOutcome supplied = db.supply("handbook", handbook());
                assertThat(supplied.documentsIngested()).isEqualTo(3);
                assertThat(supplied.documentsFailed()).isZero();
                assertThat(supplied.stopped()).isFalse();
                assertThat(supplied.sourceId()).isEqualTo("handbook");

                SearchAnswer answer = db.query("which policy moves cold segments", 3);

                assertThat(answer.route()).isEqualTo(SearchRoute.LEXICAL);
                assertThat(answer.timedOut()).isFalse();
                assertThat(answer.hits()).isNotEmpty();
                SearchHit best = answer.hits().getFirst();
                assertThat(best.chunkId()).isEqualTo(TIERING_CHUNK);
                assertThat(best.signals()).containsExactly("bm25");
                assertThat(best.text()).contains("tiering policy");
            }
        }

        @Test
        @DisplayName("reads back chunks and lists documents in order")
        void readsBack() throws Exception {
            try (ExcaliburEmbedded db = lexicalNode().open()) {
                db.supply("handbook", handbook());
                db.supply("notes", List.of(EmbeddedDocument.of("a.md", "An unrelated note.")));

                assertThat(db.fetch(TIERING_CHUNK)).get().asString().contains("cheaper disks");
                assertThat(db.fetch("handbook:missing.txt:chunk:0")).isEmpty();
                assertThat(db.documents())
                    .extracting(document -> document.sourceId() + "/" + document.documentId())
                    .containsExactly("handbook/backup.txt", "handbook/hnsw.txt",
                        "handbook/tiering.txt", "notes/a.md");
                assertThat(db.documents("handbook")).hasSize(3).first()
                    .satisfies(document -> {
                        assertThat(document.chunkIds()).containsExactly("handbook:backup.txt:chunk:0");
                        assertThat(document.contentLength()).isPositive();
                        assertThat(document.indexedAt()).isNotNull();
                    });
                assertThat(db.documents("nothing")).isEmpty();
                assertThat(db.dataDirectory()).isEqualTo(dataDir.toAbsolutePath());
            }
        }

        @Test
        @DisplayName("searches only the sources it is asked to, telling team from team:wiki")
        void searchesWithinSources() throws Exception {
            try (ExcaliburEmbedded db = lexicalNode().open()) {
                supplyOverlappingSources(db);

                assertThat(db.query("cold segments cheaper disks", 5).hits()).hasSize(3);
                assertOnlyTeam(db.query("cold segments cheaper disks", 5, Duration.ofSeconds(10),
                    List.of("team")));
                assertThat(db.query("cold segments cheaper disks", 5, Duration.ofSeconds(10),
                        List.of("team:wiki", "other")).hits())
                    .extracting(SearchHit::chunkId)
                    .containsExactlyInAnyOrder("team:wiki:tiering.txt:chunk:0",
                        "other:tiering.txt:chunk:0");
                assertThat(db.query("cold segments cheaper disks", 5, Duration.ofSeconds(10),
                    List.of("nobody")).hits()).isEmpty();
                assertThatThrownBy(() -> db.query("cold", 5, Duration.ofSeconds(10), List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("refuses the source lists POST /v1/query refuses, naming what is wrong")
        void sourceListBounds() throws Exception {
            try (ExcaliburEmbedded db = lexicalNode().open()) {
                Duration budget = Duration.ofSeconds(10);
                assertThatThrownBy(() -> db.query("cold", 5, budget, List.of("team", " ")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
                assertThatThrownBy(() -> db.query("cold", 5, budget, List.of("s".repeat(257))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at most 256 characters");
                assertThatThrownBy(() -> db.query("cold", 5, budget,
                        java.util.stream.IntStream.range(0, 1_001).mapToObj(i -> "s" + i)
                            .toList()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at most 1000 sources");
                assertThatThrownBy(() -> db.query("cold", 5, budget,
                        java.util.Arrays.asList("team", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null");
            }
        }

        @Test
        @DisplayName("keeps what it was given across a restart, and replaces and erases it after")
        void survivesARestart() throws Exception {
            try (ExcaliburEmbedded db = lexicalNode().open()) {
                db.supply("handbook", handbook());
            }

            try (ExcaliburEmbedded db = lexicalNode().open()) {
                assertThat(db.documents("handbook")).hasSize(3);
                assertThat(db.query("cold segments cheaper disks", 3).hits())
                    .extracting(SearchHit::chunkId).contains(TIERING_CHUNK);

                db.supply("handbook", List.of(EmbeddedDocument.of("tiering.txt",
                    "Warm replicas are promoted when a primary fails.")));
                assertThat(db.fetch(TIERING_CHUNK)).get().asString().contains("Warm replicas");
                assertThat(db.query("cold segments cheaper disks", 3).hits())
                    .extracting(SearchHit::chunkId).doesNotContain(TIERING_CHUNK);

                ErasureOutcome erased = db.erase("handbook", List.of("tiering.txt", "never.txt"));
                assertThat(erased.erasedDocumentIds()).containsExactly("tiering.txt");
                assertThat(erased.documentsNotFound()).isEqualTo(1);
                assertThat(erased.documentsIncomplete()).isZero();
                assertThat(erased.chunksNotRemoved()).isZero();
            }

            try (ExcaliburEmbedded db = lexicalNode().open()) {
                assertThat(db.documents("handbook")).extracting(StoredDocument::documentId)
                    .containsExactly("backup.txt", "hnsw.txt");
                assertThat(db.fetch(TIERING_CHUNK)).isEmpty();
                assertThat(db.query("warm replicas promoted primary", 3).hits())
                    .extracting(SearchHit::chunkId).doesNotContain(TIERING_CHUNK);
            }
        }

        @Test
        @DisplayName("erases a whole source without naming its documents, and no other source")
        void erasesAWholeSource() throws Exception {
            try (ExcaliburEmbedded db = lexicalNode().open()) {
                db.supply("handbook", handbook());
                supplyOverlappingSources(db);

                ErasureOutcome erased = db.eraseSource("handbook");

                assertThat(erased.erasedDocumentIds())
                    .containsExactlyInAnyOrder("tiering.txt", "hnsw.txt", "backup.txt");
                assertThat(erased.documentsNotFound()).isZero();
                assertThat(erased.documentsIncomplete()).isZero();
                assertThat(erased.documentsRemaining()).isZero();
                assertThat(erased.chunksNotRemoved()).isZero();
                assertThat(erased.sourceDeregistered())
                    .as("a supplied source has no registration to drop")
                    .isFalse();
                assertThat(db.documents("handbook")).isEmpty();
                assertThat(db.fetch(TIERING_CHUNK)).isEmpty();
                assertThat(db.query("cold segments cheaper disks", 5).hits())
                    .extracting(SearchHit::chunkId)
                    .containsExactlyInAnyOrder("team:tiering.txt:chunk:0",
                        "team:wiki:tiering.txt:chunk:0", "other:tiering.txt:chunk:0");

                ErasureOutcome team = db.eraseSource("team");
                assertThat(team.erasedDocumentIds()).containsExactly("tiering.txt");
                assertThat(db.documents()).extracting(StoredDocument::sourceId)
                    .as("team:wiki extends team's id and is a different source")
                    .containsExactlyInAnyOrder("team:wiki", "other");

                assertThat(db.eraseSource("nobody").erasedDocumentIds()).isEmpty();
                db.supply("handbook", List.of(EmbeddedDocument.of("a.md", "Supplied again.")));
                assertThat(db.documents("handbook")).extracting(StoredDocument::documentId)
                    .containsExactly("a.md");
            }

            try (ExcaliburEmbedded db = lexicalNode().open()) {
                assertThat(db.documents()).extracting(StoredDocument::sourceId)
                    .as("the erasure survives a restart")
                    .containsExactlyInAnyOrder("handbook", "team:wiki", "other");
            }
        }

        @Test
        @DisplayName("erasing a whole source drops a crawl this directory registered under it")
        void erasingASourceDeregistersItsCrawl() throws Exception {
            SourceCatalog.open(dataDir).record(
                new SourceDescriptor("filesystem", "site", Map.of("path", "/srv/site")));

            try (ExcaliburEmbedded db = lexicalNode().open()) {
                ErasureOutcome erased = db.eraseSource("site");

                assertThat(erased.sourceDeregistered()).isTrue();
                assertThat(erased.erasedDocumentIds()).isEmpty();
                assertThat(db.eraseSource("site").sourceDeregistered())
                    .as("there is nothing left to deregister")
                    .isFalse();
                assertThat(db.supply("site", List.of(EmbeddedDocument.of("a", "text")))
                    .documentsIngested())
                    .as("the id is no longer a crawl's, so documents may be supplied under it")
                    .isEqualTo(1);
            }

            assertThat(SourceCatalog.open(dataDir).all())
                .extracting(SourceDescriptor::sourceId).doesNotContain("site");
        }
    }

    @Nested
    @DisplayName("with an embedding endpoint")
    class Vectors {

        private FakeEmbeddingEndpoint embeddings;

        @BeforeEach
        void startEndpoint() throws Exception {
            embeddings = FakeEmbeddingEndpoint.start(DIMENSION);
        }

        @AfterEach
        void stopEndpoint() {
            embeddings.close();
        }

        @Test
        @DisplayName("fuses both signals, as the query endpoint does by default")
        void fusesBothSignals() throws Exception {
            try (ExcaliburEmbedded db = vectorNode(embeddings).open()) {
                assertThat(db.route()).contains(SearchRoute.HYBRID);
                db.supply("handbook", handbook());

                SearchAnswer answer = db.query("which policy moves cold segments to cheaper disks",
                    3, Duration.ofSeconds(10));

                assertThat(answer.route()).isEqualTo(SearchRoute.HYBRID);
                assertThat(answer.hits().getFirst().chunkId()).isEqualTo(TIERING_CHUNK);
                assertThat(answer.hits().getFirst().signals()).contains("vector", "bm25");
            }
        }

        @Test
        @DisplayName("confines both fused signals to the sources it is asked to")
        void fusedSearchWithinSources() throws Exception {
            try (ExcaliburEmbedded db = vectorNode(embeddings).open()) {
                supplyOverlappingSources(db);

                SearchAnswer answer = db.query("cold segments cheaper disks", 5,
                    Duration.ofSeconds(10), List.of("team"));

                assertThat(answer.route()).isEqualTo(SearchRoute.HYBRID);
                assertOnlyTeam(answer);
                assertThat(answer.hits().getFirst().signals()).contains("vector", "bm25");
            }
        }

        @Test
        @DisplayName("confines vector search alone to the sources it is asked to")
        void vectorSearchWithinSources() throws Exception {
            try (ExcaliburEmbedded db = vectorNode(embeddings)
                    .property("excalibur.query.hybrid-retrieval", "false").open()) {
                supplyOverlappingSources(db);

                SearchAnswer answer = db.query("cold segments cheaper disks", 5,
                    Duration.ofSeconds(10), List.of("team"));

                assertThat(answer.route()).isEqualTo(SearchRoute.VECTOR);
                assertOnlyTeam(answer);
            }
        }

        @Test
        @DisplayName("answers from vectors alone when hybrid retrieval is off")
        void vectorsAlone() throws Exception {
            try (ExcaliburEmbedded db = vectorNode(embeddings)
                    .property("excalibur.query.hybrid-retrieval", "false").open()) {
                assertThat(db.route()).contains(SearchRoute.VECTOR);
                db.supply("handbook", handbook());

                SearchAnswer answer = db.query("which policy moves cold segments to cheaper disks", 3);

                assertThat(answer.route()).isEqualTo(SearchRoute.VECTOR);
                assertThat(answer.hits()).extracting(SearchHit::chunkId).contains(TIERING_CHUNK);
                assertThat(answer.hits()).allSatisfy(hit ->
                    assertThat(hit.signals()).containsExactly("vector"));
            }
        }

        @Test
        @DisplayName("falls back to BM25 when the embedding endpoint fails at query time")
        void degradesToLexical() throws Exception {
            try (ExcaliburEmbedded db = vectorNode(embeddings).open()) {
                db.supply("handbook", handbook());
                embeddings.fail(true);

                SearchAnswer answer = db.query("cold segments cheaper disks", 3);

                assertThat(answer.route()).isEqualTo(SearchRoute.LEXICAL);
                assertThat(answer.hits().getFirst().chunkId()).isEqualTo(TIERING_CHUNK);
            }
        }

        @Test
        @DisplayName("starts a BM25 search to fall back on when fusion is off")
        void degradesToLexicalWithoutFusion() throws Exception {
            try (ExcaliburEmbedded db = vectorNode(embeddings)
                    .property("excalibur.query.hybrid-retrieval", "false").open()) {
                db.supply("handbook", handbook());
                embeddings.fail(true);

                SearchAnswer answer = db.query("cold segments cheaper disks", 3);

                assertThat(answer.route()).isEqualTo(SearchRoute.LEXICAL);
                assertThat(answer.hits()).extracting(SearchHit::chunkId).contains(TIERING_CHUNK);
            }
        }

        @Test
        @DisplayName("fails when the vectors fail and there is no lexical index to fall back on")
        void failsWithNothingToFallBackOn() throws Exception {
            Files.writeString(dataDir.resolve(ChunkIndexFactory.NATIVE_DIRECTORY), "not a directory");
            try (ExcaliburEmbedded db = vectorNode(embeddings).open()) {
                assertThat(db.route()).contains(SearchRoute.VECTOR);
                db.supply("handbook", handbook());
                embeddings.fail(true);

                assertThatThrownBy(() -> db.query("cold segments", 3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no other signal");
            }
        }

        @Test
        @DisplayName("takes the environment over properties set here, and those over the file")
        void configurationPrecedence() throws Exception {
            Path file = Files.writeString(configDir.resolve("embedded.properties"),
                "excalibur.query.hybrid-retrieval=false\n");

            try (ExcaliburEmbedded db = vectorNode(embeddings).configFile(file).open()) {
                assertThat(db.route()).contains(SearchRoute.VECTOR);
            }
            try (ExcaliburEmbedded db = vectorNode(embeddings).configFile(file)
                    .properties(Map.of("excalibur.query.hybrid-retrieval", "true")).open()) {
                assertThat(db.route()).contains(SearchRoute.HYBRID);
            }
            try (ExcaliburEmbedded db = vectorNode(embeddings).configFile(file)
                    .property("excalibur.query.hybrid-retrieval", "true")
                    .environment(Map.of("EXCALIBUR_QUERY_HYBRID_RETRIEVAL", "false")).open()) {
                assertThat(db.route()).contains(SearchRoute.VECTOR);
            }
        }
    }

    @Nested
    @DisplayName("refuses")
    class Refusals {

        @Test
        @DisplayName("a query outside the node's bounds")
        void queryBounds() throws Exception {
            try (ExcaliburEmbedded db = lexicalNode().open()) {
                assertThatThrownBy(() -> db.query(" ", 3))
                    .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> db.query("x".repeat(100_000), 3))
                    .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> db.query("cold", 0))
                    .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> db.query("cold", 1_000_000))
                    .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> db.query("cold", 3, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("a supply with no usable source id or no documents")
        void supplyBounds() throws Exception {
            try (ExcaliburEmbedded db = lexicalNode().open()) {
                List<EmbeddedDocument> one = List.of(EmbeddedDocument.of("a", "text"));
                assertThatThrownBy(() -> db.supply(" ", one))
                    .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> db.supply("s".repeat(257), one))
                    .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> db.supply("notes", List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("a supply under the id of a crawl this directory has registered")
        void crawledSource() throws Exception {
            SourceCatalog.open(dataDir).record(
                new SourceDescriptor("filesystem", "site", Map.of("path", "/srv/site")));

            try (ExcaliburEmbedded db = lexicalNode().open()) {
                assertThatThrownBy(() -> db.supply("site",
                        List.of(EmbeddedDocument.of("a", "text"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("crawl");
            }
        }

        @Test
        @DisplayName("every call once closed, and a second close is harmless")
        void closed() throws Exception {
            ExcaliburEmbedded db = lexicalNode().open();
            db.close();

            assertThatCode(db::close).doesNotThrowAnyException();
            assertThatThrownBy(() -> db.query("cold", 3)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> db.supply("s", List.of(EmbeddedDocument.of("a", "b"))))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> db.fetch("x")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(db::documents).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> db.documents("s")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> db.erase("s", List.of("a")))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> db.eraseSource("s"))
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a second node on a directory one already holds, until the first closes")
        void heldDirectory() throws Exception {
            try (ExcaliburEmbedded db = lexicalNode().open()) {
                assertThat(DataDirectoryLock.probe(dataDir)).get()
                    .extracting(LockHolder::description)
                    .isEqualTo(NodeAssembly.EMBEDDED_LOCK_HOLDER);
                assertThatThrownBy(() -> lexicalNode().open())
                    .isInstanceOf(DataDirectoryLockedException.class)
                    .hasMessageContaining(NodeAssembly.EMBEDDED_LOCK_HOLDER);
                db.supply("handbook", handbook());
            }

            assertThat(DataDirectoryLock.probe(dataDir)).isEmpty();
            try (ExcaliburEmbedded db = lexicalNode().open()) {
                assertThat(db.documents()).hasSize(3);
            }
        }

        @Test
        @DisplayName("a directory another process's tool holds")
        void directoryHeldElsewhere() throws Exception {
            Files.createDirectories(dataDir);
            try (DataDirectoryLock ignored =
                     DataDirectoryLock.acquire(dataDir, "excalibur-fsck --repair")) {
                assertThatThrownBy(() -> lexicalNode().open())
                    .isInstanceOf(DataDirectoryLockedException.class)
                    .hasMessageContaining("excalibur-fsck --repair");
            }
        }

        @Test
        @DisplayName("graph extraction, which only a server can stage")
        void graphExtraction() {
            assertThatThrownBy(() -> lexicalNode()
                    .property("excalibur.graph.extraction-enabled", "true").open())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("excalibur.graph.extraction-enabled");
        }

        @Test
        @DisplayName("a configuration the server would refuse")
        void invalidConfiguration() {
            assertThatThrownBy(() -> lexicalNode()
                    .property("excalibur.query.vector-dimensions", "5").open())
                .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("every query on a node with no signal to search, saying why")
        void noSignal() throws Exception {
            Files.createDirectories(dataDir);
            Files.writeString(dataDir.resolve(ChunkIndexFactory.NATIVE_DIRECTORY), "not a directory");

            try (ExcaliburEmbedded db = lexicalNode().open()) {
                assertThat(db.route()).isEmpty();
                assertThatThrownBy(() -> db.query("cold", 3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("neither an embedding client nor a lexical index");
            }
        }

        @Test
        @DisplayName("nothing it half-opened: a failed open leaves the directory free to open")
        void failedOpenReleases() throws Exception {
            Files.createDirectories(dataDir.resolve(SourceCatalog.FILE));

            assertThatThrownBy(() -> lexicalNode().open())
                .isInstanceOf(SourceCatalog.CatalogLoadException.class);

            Files.delete(dataDir.resolve(SourceCatalog.FILE));
            try (ExcaliburEmbedded db = lexicalNode().open()) {
                db.supply("handbook", handbook());
                assertThat(db.documents()).hasSize(3);
            }
        }
    }
}
