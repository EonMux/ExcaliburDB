package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A reindexed document reaches the lexical index whole.
 *
 * <p>Runs over different sources share one lexical index and refresh it on their own schedules,
 * so a refresh can land between any two calls a reindex makes into it. Added one chunk at a time,
 * a document is then searchable in part: the chunks added before the refresh, without the ones
 * after. These tests make every add be followed by such a refresh and count, each time, how many
 * of the document's chunks a search finds.</p>
 */
@DisplayName("A reindexed document reaches the lexical index whole")
class ReindexLexicalAtomicityTest {

    private static final String SOURCE = "docs";
    private static final int CHUNK_TOKENS = 8;

    @TempDir
    Path tempDir;

    private ChunkLexicalIndex lexical;
    private final Map<String, String> storage = new ConcurrentHashMap<>();
    private final List<Integer> searchableAfterEachRefresh = new ArrayList<>();
    private List<String> words;

    @BeforeEach
    void setUp() throws Exception {
        lexical = ChunkLexicalIndex.open(tempDir.resolve("lexical"), 4.0);
    }

    @AfterEach
    void tearDown() {
        lexical.close();
    }

    @Test
    @DisplayName("a refresh landing mid-document publishes all of its chunks or none")
    void aConcurrentRefreshNeverPublishesPartOfADocument() {
        words = new ArrayList<>();
        for (int i = 0; i < 6 * CHUNK_TOKENS; i++) {
            words.add("word" + (char) ('a' + i / 26) + (char) ('a' + i % 26));
        }
        DocumentRegistry registry = new DocumentRegistry();
        MapWriter writer = new MapWriter();
        DocumentReindexer reindexer = new DocumentReindexer(new TextChunker(CHUNK_TOKENS, 0),
            null, new UpsertHandler(registry, writer), registry, writer);
        reindexer.setLexicalIndex(refreshingAfterEveryAdd(lexical), storage::get);

        DocumentReindexer.DocumentResult result = reindexer.reindexDocument(SOURCE, "a.txt",
            new FetchedDocument("a.txt", String.join(" ", words).getBytes(StandardCharsets.UTF_8),
                "text/plain", System.currentTimeMillis()));

        assertThat(result.getChunkIds()).as("precondition: the document spans chunks")
            .hasSizeGreaterThan(2);
        assertThat(searchableAfterEachRefresh)
            .as("after every refresh the document is either not searchable or wholly searchable")
            .isNotEmpty()
            .allSatisfy(found -> assertThat(found).isIn(0, words.size()));
        assertThat(searchableAfterEachRefresh.get(searchableAfterEachRefresh.size() - 1))
            .isEqualTo(words.size());
    }

    /** The index, with a refresh from some other run following every chunk or document added. */
    private ChunkIndex refreshingAfterEveryAdd(ChunkIndex delegate) {
        return (ChunkIndex) Proxy.newProxyInstance(ChunkIndex.class.getClassLoader(),
            new Class<?>[] {ChunkIndex.class}, (proxy, method, args) -> {
                Object answer;
                try {
                    answer = method.invoke(delegate, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
                if (method.getName().equals("add") || method.getName().equals("addDocument")) {
                    delegate.refresh();
                    searchableAfterEachRefresh.add(searchableWords());
                }
                return answer;
            });
    }

    /** How many of the document's words a search finds. */
    private int searchableWords() {
        int found = 0;
        for (String word : words) {
            if (!lexical.ranker(QueryExpander.identity())
                    .rank(RetrievalQuery.ofText(word), 10).documents().isEmpty()) {
                found++;
            }
        }
        return found;
    }

    /** Writes to {@link #storage}. */
    private final class MapWriter implements DocumentReindexer.StorageWriter,
            UpsertHandler.StorageAdapter {

        @Override
        public boolean writeChunk(String chunkId, String content, float[] embedding) {
            storage.put(chunkId, content);
            return true;
        }

        @Override
        public boolean delete(byte[] key) {
            return storage.remove(new String(key, StandardCharsets.UTF_8)) != null;
        }
    }
}
