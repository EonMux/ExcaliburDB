package com.eonmux.excalibur.api.retrieval;

import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A search that withholds documents still answers as deep as it was asked.
 *
 * <p>Callers read a short answer as the end of the matches: the query route reports an exact
 * total from it and a caller paging by offset stops there. So withholding a hit is not allowed
 * to shorten the answer while visible matches remain below it - the search reaches past what it
 * withheld - and an answer is short only when the index itself ran out.</p>
 */
@DisplayName("Lexical search withholding documents")
class LexicalVisibilityPagingTest {

    /** Documents in the order BM25 ranks them: {@code doc00} matches best. */
    private static final int DOCUMENTS = 12;

    @TempDir
    Path dataDir;

    private ChunkLexicalIndex index;
    private DocumentVisibilityTracker tracker;
    private LexicalQueryService service;

    @BeforeEach
    void setUp() throws Exception {
        index = ChunkLexicalIndex.open(dataDir.resolve("lexical"), 8.0);
        for (int d = 0; d < DOCUMENTS; d++) {
            index.add(chunkOf(d), textRanked(d));
        }
        index.refresh();
        DocumentRegistry registry = new DocumentRegistry(dataDir.resolve("document-registry"));
        tracker = new DocumentVisibilityTracker(registry,
            new VisibilityTracker(new SegmentCatalog(dataDir.resolve("catalog"))));
        service = new LexicalQueryService(index, null, List.of())
            .withVisibility(new DocumentVisibilityTrackerAdapter(tracker));
    }

    @AfterEach
    void tearDown() throws Exception {
        index.close();
    }

    @Test
    @DisplayName("fills the answer from below what it withheld, keeping the ranking's order")
    void refillsToTheLimit() {
        hide(0, 1, 3);
        publish(2, 4);

        assertThat(ids(service.search("shared", 4)))
            .containsExactly(chunkOf(2), chunkOf(4), chunkOf(5), chunkOf(6));
    }

    @Test
    @DisplayName("reaches as far past the withheld hits as it has to")
    void reachesPastManyWithheldHits() {
        hide(0, 1, 2, 3, 4, 5, 6, 7, 8);

        assertThat(ids(service.search("shared", 2)))
            .containsExactly(chunkOf(9), chunkOf(10));
    }

    /**
     * Reaching deeper re-ranks the same query, not a new one: a second expansion would be a
     * second model call per round, and could search each round for different terms.
     */
    @Test
    @DisplayName("expands the query once however many rounds it reaches through")
    void expandsOnceAcrossRounds() {
        java.util.concurrent.atomic.AtomicInteger calls =
            new java.util.concurrent.atomic.AtomicInteger();
        LexicalQueryService widening = new LexicalQueryService(index,
            com.eonmux.excalibur.retrieval.expand.QueryExpander.infallible(query -> {
                calls.incrementAndGet();
                return List.of(com.eonmux.excalibur.retrieval.expand.SearchFormulation
                    .original(query));
            }), List.of())
            .withVisibility(new DocumentVisibilityTrackerAdapter(tracker));
        hide(0, 1, 2, 3, 4, 5, 6, 7, 8);

        assertThat(ids(widening.search("shared", 2)))
            .containsExactly(chunkOf(9), chunkOf(10));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("is short only when the index has no more visible matches")
    void shortOnlyWhenExhausted() {
        hide(1, 5, 9);

        List<String> all = ids(service.search("shared", DOCUMENTS));

        assertThat(all).hasSize(DOCUMENTS - 3)
            .doesNotContain(chunkOf(1), chunkOf(5), chunkOf(9));
        assertThat(ids(service.search("shared", 3)))
            .as("each page is a prefix of the whole answer")
            .containsExactlyElementsOf(all.subList(0, 3));
    }

    @Test
    @DisplayName("withholds a published document whose new version is in flight")
    void withholdsARepublishedDocument() {
        publish(0);
        hide(0);

        assertThat(ids(service.search("shared", 3)))
            .containsExactly(chunkOf(1), chunkOf(2), chunkOf(3));
    }

    @Test
    @DisplayName("answers as before when no document is withheld")
    void unchangedWithNothingWithheld() {
        LexicalQueryService unfiltered = new LexicalQueryService(index, null, List.of());

        assertThat(ids(service.search("shared", 5)))
            .containsExactlyElementsOf(ids(unfiltered.search("shared", 5)));
    }

    private void hide(int... documents) {
        for (int d : documents) {
            tracker.beginIngestion(keyOf(d), 1);
        }
    }

    private void publish(int... documents) {
        for (int d : documents) {
            tracker.registerDocument(keyOf(d), Set.of(chunkOf(d)), tracker.advanceEpoch());
        }
    }

    private static List<String> ids(List<LexicalQueryService.Hit> hits) {
        return hits.stream().map(LexicalQueryService.Hit::chunkId).toList();
    }

    private static String keyOf(int document) {
        return ChunkIdentifiers.documentKey("docs", String.format("doc%02d", document));
    }

    private static String chunkOf(int document) {
        return ChunkIdentifiers.chunkId("docs", String.format("doc%02d", document), 0);
    }

    /** Twelve words, of which fewer are the query term the lower the document ranks. */
    private static String textRanked(int document) {
        StringBuilder text = new StringBuilder();
        int matches = DOCUMENTS - document;
        for (int w = 0; w < DOCUMENTS; w++) {
            text.append(w < matches ? "shared" : "filler" + (char) ('a' + document)
                + (char) ('a' + w)).append(' ');
        }
        return text.toString().trim();
    }
}
