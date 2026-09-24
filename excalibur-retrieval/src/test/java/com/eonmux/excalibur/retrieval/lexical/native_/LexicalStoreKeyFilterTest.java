package com.eonmux.excalibur.retrieval.lexical.native_;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A filtered search fills its page from the documents the filter admits, ranked as they would be
 * without it - not by cutting the page from every document and discarding the refused ones.
 */
@DisplayName("Lexical store key filter")
class LexicalStoreKeyFilterTest {

    @Test
    @DisplayName("fills the page from admitted documents when the best matches are refused")
    void fillsThePageFromAdmittedDocuments(@TempDir Path dir) throws Exception {
        Map<String, String> docs = new LinkedHashMap<>();
        // The refused documents say "kernel" three times; the admitted ones once.
        for (int i = 0; i < 20; i++) {
            docs.put("other:" + i, "kernel kernel kernel panic");
        }
        docs.put("mine:a", "kernel module");
        docs.put("mine:b", "a kernel parameter and a long tail of unrelated words");
        try (LexicalStore store = LexicalStore.open(dir)) {
            store.add(docs, 1);

            List<LexicalStore.Hit> hits = store.search(new String[] {"kernel"},
                new double[] {1.0}, 2, 99, key -> key.startsWith("mine:"));

            assertThat(hits).extracting(LexicalStore.Hit::key)
                .as("twenty better matches are refused, and the page is still full")
                .containsExactly("mine:a", "mine:b");
        }
    }

    @Test
    @DisplayName("ranks admitted documents in the order an unfiltered search gives them")
    void keepsTheUnfilteredOrder(@TempDir Path dir) throws Exception {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("a:1", "raft consensus");
        docs.put("b:1", "raft raft consensus");
        docs.put("a:2", "raft raft raft consensus leader");
        docs.put("b:2", "paxos");
        try (LexicalStore store = LexicalStore.open(dir)) {
            store.add(docs, 1);
            String[] terms = {"raft"};
            double[] weights = {1.0};

            List<String> unfiltered = store.search(terms, weights, 10, 99).stream()
                .map(LexicalStore.Hit::key).filter(key -> key.startsWith("a:")).toList();
            List<String> filtered = store.search(terms, weights, 10, 99,
                key -> key.startsWith("a:")).stream().map(LexicalStore.Hit::key).toList();

            assertThat(filtered).isEqualTo(unfiltered).containsExactly("a:2", "a:1");
        }
    }

    @Test
    @DisplayName("refuses tombstoned documents and filtered ones alike")
    void composesWithErasure(@TempDir Path dir) throws Exception {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("a:1", "lighthouse");
        docs.put("a:2", "lighthouse keeper");
        docs.put("b:1", "lighthouse");
        try (LexicalStore store = LexicalStore.open(dir)) {
            store.add(docs, 1);
            store.erase(Set.of("a:1"));

            assertThat(store.search(new String[] {"lighthouse"}, new double[] {1.0}, 10, 99,
                    key -> key.startsWith("a:")))
                .extracting(LexicalStore.Hit::key)
                .containsExactly("a:2");
        }
    }
}
