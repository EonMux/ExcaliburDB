package com.eonmux.excalibur.api.retrieval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * A BM25 answer given while a reindex rewrites a document shows one version of it or none.
 *
 * <p>The lexical index publishes a version's postings at a refresh and erases a retired chunk at
 * once, while storage is rewritten in place one chunk at a time. So a search that went by the
 * postings alone named the previous version's chunks while storage held part of each, returned
 * the shrunk version's surviving chunks once its tail was erased and before it was published,
 * and read back whichever text storage held at that moment. These stop the reindex at every
 * write, tombstone and publication, search there as a caller would - hits, then the text of
 * each - and require every answer to be part of one version, text included.</p>
 */
@DisplayName("A lexical answer during a reindex")
class LexicalReindexVisibilityTest {

    private static final int LIMIT = 100;

    @TempDir
    Path dataDir;

    private SteppedReindex node;
    private LexicalQueryService service;
    private Function<String, String> text;

    @BeforeEach
    void setUp() throws Exception {
        node = new SteppedReindex(dataDir);
        service = new LexicalQueryService(node.index(), null, List.of())
            .withVisibility(node.checker());
        text = VisibleChunkText.over(node.storedText(), node.checker());
    }

    @AfterEach
    void tearDown() throws Exception {
        node.close();
    }

    private Map<String, String> search() {
        Map<String, String> returned = new LinkedHashMap<>();
        for (LexicalQueryService.Hit hit : service.search(SteppedReindex.COMMON, LIMIT)) {
            returned.put(hit.chunkId(), text.apply(hit.chunkId()));
        }
        return returned;
    }

    @Test
    @DisplayName("never mixes the versions of a document that shrinks")
    void shrinkingDocument() throws Exception {
        node.put("a.txt", "olda", 7);
        node.put("b.txt", "bystander", 2);
        node.reindex();
        Map<String, String> old = node.storedVersionOf("a.txt");
        Map<String, String> bystander = node.storedVersionOf("b.txt");

        node.put("a.txt", "newa", 2);
        List<SteppedReindex.Observation> observations = node.observeReindex(this::search);
        Map<String, String> replacement = node.storedVersionOf("a.txt");

        assertThat(replacement.size()).as("precondition: the new version is shorter")
            .isLessThan(old.size());
        assertOneVersion(observations, "a.txt", old, replacement);
        for (SteppedReindex.Observation observation : observations) {
            assertThat(SteppedReindex.ofDocument("b.txt", observation.returned()))
                .as("a document nobody is rewriting is answered whole at '%s'",
                    observation.point())
                .isEqualTo(bystander);
        }
        assertThat(observations).as("the rewrite was hidden while it was in flight")
            .anySatisfy(o -> assertThat(SteppedReindex.ofDocument("a.txt", o.returned()))
                .isEmpty());
        assertThat(SteppedReindex.ofDocument("a.txt",
                observations.get(observations.size() - 1).returned()))
            .as("once indexed, the new version is answered whole").isEqualTo(replacement);
    }

    @Test
    @DisplayName("never mixes the versions of a document that grows")
    void growingDocument() throws Exception {
        node.put("a.txt", "olda", 2);
        node.reindex();
        Map<String, String> old = node.storedVersionOf("a.txt");

        node.put("a.txt", "newa", 6);
        List<SteppedReindex.Observation> observations = node.observeReindex(this::search);

        assertOneVersion(observations, "a.txt", old, node.storedVersionOf("a.txt"));
    }

    @Test
    @DisplayName("never answers with part of a document published for the first time")
    void newDocument() throws Exception {
        node.put("b.txt", "bystander", 2);
        node.reindex();

        node.put("a.txt", "newa", 4);
        List<SteppedReindex.Observation> observations = node.observeReindex(this::search);

        assertOneVersion(observations, "a.txt", Map.of(), node.storedVersionOf("a.txt"));
    }

    static void assertOneVersion(List<SteppedReindex.Observation> observations,
                                 String documentId, Map<String, String> old,
                                 Map<String, String> replacement) {
        assertThat(observations).as("the reindex stopped where a query could land")
            .hasSizeGreaterThan(4);
        for (SteppedReindex.Observation observation : observations) {
            Map<String, String> seen =
                SteppedReindex.ofDocument(documentId, observation.returned());
            if (!seen.isEmpty() && !SteppedReindex.partOf(seen, old)
                    && !SteppedReindex.partOf(seen, replacement)) {
                fail("At '%s' a query answered %s with part of each version:%n  seen: %s%n"
                        + "  old: %s%n  new: %s", observation.point(), documentId, seen,
                    new TreeMap<>(old), new TreeMap<>(replacement));
            }
        }
    }
}
