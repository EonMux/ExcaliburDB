package com.eonmux.excalibur.api.embedded;

import com.eonmux.excalibur.api.bootstrap.DocumentRegistryFactory;
import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An embedded node never answers with a document it was part way through rewriting.
 *
 * <p>A node that stops while a document's new version is being written over its old one holds
 * part of each in storage, and journals the document as in flight so that the restarted node
 * keeps it hidden until it is written whole. The restarted node has published nothing yet, so
 * its tracker holds that mark and no committed document at all - which is exactly the state in
 * which a filter that runs only once something has been published lets the mark through. Every
 * route an embedder reads by is covered: BM25 alone, the two signals fused, and a chunk read
 * back by id.</p>
 */
@DisplayName("An embedded node over a document left in flight")
@Timeout(120)
class EmbeddedVisibilityTest {

    private static final int DIMENSION = 128;

    private static final String TIERING_CHUNK = "handbook:tiering.txt:chunk:0";
    private static final String HNSW_CHUNK = "handbook:hnsw.txt:chunk:0";

    @TempDir
    Path dataDir;

    private FakeEmbeddingEndpoint embeddings;

    @BeforeEach
    void startEmbeddings() throws Exception {
        embeddings = FakeEmbeddingEndpoint.start(DIMENSION);
    }

    @AfterEach
    void stopEmbeddings() {
        embeddings.close();
    }

    private ExcaliburEmbedded.Builder vectorNode() {
        return ExcaliburEmbedded.builder(dataDir)
            .property("excalibur.llm.embedding-base-url", embeddings.baseUrl())
            .property("excalibur.llm.embedding-model", "hashed-words")
            .property("excalibur.llm.embeddings-dimensions", String.valueOf(DIMENSION))
            .property("excalibur.query.vector-dimensions", String.valueOf(DIMENSION))
            .property("excalibur.llm.chat-enabled", "false");
    }

    private static List<EmbeddedDocument> handbook() {
        return List.of(
            EmbeddedDocument.of("tiering.txt",
                "Cold segments are moved to cheaper disks by the tiering policy."),
            EmbeddedDocument.of("hnsw.txt",
                "The HNSW graph links every vector to its nearest neighbours."));
    }

    /** Journals a document as in flight, as a node stopping part way through writing it does. */
    private void leaveInFlight(String sourceId, String documentId) {
        DocumentVisibilityTracker tracker = new DocumentVisibilityTracker(
            new DocumentRegistry(dataDir.resolve(DocumentRegistryFactory.FILE)));
        tracker.beginIngestion(ChunkIdentifiers.documentKey(sourceId, documentId), 1);
    }

    @Test
    @DisplayName("keeps it out of a BM25 answer and out of a read by id")
    void lexicalRoute() throws Exception {
        try (ExcaliburEmbedded db = ExcaliburEmbedded.builder(dataDir).open()) {
            db.supply("handbook", handbook());
        }
        leaveInFlight("handbook", "tiering.txt");

        try (ExcaliburEmbedded db = ExcaliburEmbedded.builder(dataDir).open()) {
            SearchAnswer answer = db.query("cold segments cheaper disks tiering", 5);

            assertThat(answer.route()).isEqualTo(SearchRoute.LEXICAL);
            assertThat(answer.hits()).extracting(SearchHit::chunkId)
                .doesNotContain(TIERING_CHUNK);
            assertThat(db.fetch(TIERING_CHUNK)).isEmpty();
            assertThat(db.query("HNSW graph nearest neighbours", 5).hits())
                .as("a document nobody was writing is still answered, with its text")
                .anySatisfy(hit -> {
                    assertThat(hit.chunkId()).isEqualTo(HNSW_CHUNK);
                    assertThat(hit.text()).contains("nearest neighbours");
                });
        }
    }

    @Test
    @DisplayName("keeps it out of both halves of a fused answer")
    void hybridRoute() throws Exception {
        try (ExcaliburEmbedded db = vectorNode().open()) {
            db.supply("handbook", handbook());
        }
        leaveInFlight("handbook", "tiering.txt");

        try (ExcaliburEmbedded db = vectorNode().open()) {
            SearchAnswer answer = db.query("cold segments cheaper disks tiering", 5);

            assertThat(answer.route()).isEqualTo(SearchRoute.HYBRID);
            assertThat(answer.hits()).extracting(SearchHit::chunkId)
                .doesNotContain(TIERING_CHUNK);
            assertThat(db.fetch(TIERING_CHUNK)).isEmpty();
            assertThat(db.query("HNSW graph nearest neighbours", 5).hits())
                .extracting(SearchHit::chunkId).contains(HNSW_CHUNK);
        }
    }
}
