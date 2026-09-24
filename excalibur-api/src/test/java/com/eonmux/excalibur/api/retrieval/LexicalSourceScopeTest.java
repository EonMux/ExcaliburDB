package com.eonmux.excalibur.api.retrieval;

import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.core.text.SourceScope;
import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex;
import com.eonmux.excalibur.ingest.lexical.NativeChunkIndex;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionPlanner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A lexical search confined to some sources, on both engines.
 *
 * <p>The corpus is built so the confined source's documents rank below every other source's:
 * a search that cut its page from the whole ranking and filtered it afterwards would come back
 * short or empty, so a full page proves the filter is applied where the ranking is decided.
 * {@code team:wiki} extends {@code team}, so its chunk ids begin with {@code team:} as well.</p>
 */
@DisplayName("Lexical search within sources")
class LexicalSourceScopeTest {

    /** Documents per source. */
    private static final int PER_SOURCE = 4;

    @TempDir
    Path dataDir;

    private ChunkIndex index;

    @AfterEach
    void tearDown() throws Exception {
        if (index != null) {
            index.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"lucene", "native"})
    @DisplayName("fills a page from one source's chunks when every other source outranks them")
    void fillsThePageFromTheScopedSource(String engine) throws Exception {
        LexicalQueryService service = service(engine);

        List<String> hits = ids(service.search("shared", 3, SourceScope.of(List.of("team"))));

        assertThat(hits).containsExactly(chunk("team", 0), chunk("team", 1), chunk("team", 2));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"lucene", "native"})
    @DisplayName("tells a source from another whose id extends it, both ways")
    void separatesALongerSourceId(String engine) throws Exception {
        LexicalQueryService service = service(engine);

        assertThat(ids(service.search("shared", 20, SourceScope.of(List.of("team")))))
            .hasSize(PER_SOURCE)
            .allMatch(id -> id.startsWith("team:doc"));
        assertThat(ids(service.search("shared", 20, SourceScope.of(List.of("team:wiki")))))
            .hasSize(PER_SOURCE)
            .allMatch(id -> id.startsWith("team:wiki:doc"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"lucene", "native"})
    @DisplayName("ranks a scoped page as the unscoped ranking orders those chunks")
    void keepsTheUnscopedOrder(String engine) throws Exception {
        LexicalQueryService service = service(engine);
        SourceScope scope = SourceScope.of(List.of("team", "other"));

        List<String> unscoped = ids(service.search("shared", 3 * PER_SOURCE));
        List<String> expected = new ArrayList<>(unscoped);
        expected.removeIf(id -> id.startsWith("team:wiki:"));

        assertThat(ids(service.search("shared", 3 * PER_SOURCE, scope)))
            .containsExactlyElementsOf(expected);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"lucene", "native"})
    @DisplayName("answers nothing for a source the node does not hold")
    void unknownSourceAnswersNothing(String engine) throws Exception {
        LexicalQueryService service = service(engine);

        assertThat(service.search("shared", 5, SourceScope.of(List.of("nobody")))).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"lucene", "native"})
    @DisplayName("refuses a scoped search when nothing attributes chunks to sources")
    void refusesWithoutAttribution(String engine) throws Exception {
        LexicalQueryService service = service(engine).withChunkSources(null);

        assertThatThrownBy(() -> service.search("shared", 5, SourceScope.of(List.of("team"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("[team]");
        assertThat(service.search("shared", 2)).hasSize(2);
    }

    /**
     * An index over three sources, with the registry attributing each document.
     *
     * <p>{@code other} ranks best, then {@code team:wiki}, then {@code team}; within a source,
     * document 0 ranks best.</p>
     */
    private LexicalQueryService service(String engine) throws Exception {
        AtomicLong epoch = new AtomicLong(1);
        index = "native".equals(engine)
            ? NativeChunkIndex.open(dataDir.resolve("native"), epoch::get, 4,
                CompactionPlanner.standard())
            : ChunkLexicalIndex.open(dataDir.resolve("lucene"), 8.0);
        DocumentRegistry registry = new DocumentRegistry();
        String[] sources = {"other", "team:wiki", "team"};
        for (int s = 0; s < sources.length; s++) {
            for (int d = 0; d < PER_SOURCE; d++) {
                String chunkId = chunk(sources[s], d);
                index.add(chunkId, textRanked(s * PER_SOURCE + d));
                registry.register(DocumentMetadata.builder()
                    .sourceId(sources[s])
                    .documentId("doc" + d)
                    .chunkIds(List.of(chunkId))
                    .build());
            }
        }
        index.refresh();
        epoch.incrementAndGet();
        return new LexicalQueryService(index, null, List.of())
            .withChunkSources(registry::sourceOfChunk);
    }

    private static String chunk(String sourceId, int document) {
        return ChunkIdentifiers.chunkId(sourceId, "doc" + document, 0);
    }

    private static List<String> ids(List<LexicalQueryService.Hit> hits) {
        return hits.stream().map(LexicalQueryService.Hit::chunkId).toList();
    }

    /** A fixed-length text holding fewer of the query term the lower the rank. */
    private static String textRanked(int rank) {
        int total = 3 * PER_SOURCE;
        StringBuilder text = new StringBuilder();
        for (int w = 0; w < total; w++) {
            text.append(w < total - rank ? "shared" : "filler" + (char) ('a' + rank)
                + (char) ('a' + w)).append(' ');
        }
        return text.toString().trim();
    }
}
