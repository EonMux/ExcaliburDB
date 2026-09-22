package com.eonmux.excalibur.api.retrieval;

import com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex;
import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.retrieval.expand.SearchFormulation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval over a corpus that was never embedded.
 *
 * <p>These pin the property the whole lexical path exists for: a chunk becomes findable by the
 * words in it, under an identifier a caller can resolve, with no vector anywhere in the process.
 */
@DisplayName("Lexical query service")
class LexicalQueryServiceTest {

    private static ChunkLexicalIndex indexed(Path dir) throws Exception {
        ChunkLexicalIndex index = ChunkLexicalIndex.open(dir, 8.0);
        index.add("kb:oom.txt:chunk:0",
            "the kernel OOM killer sent SIGKILL after the cgroup memory limit was exceeded");
        index.add("kb:tls.txt:chunk:0",
            "the TLS handshake failed because the certificate chain could not be verified");
        index.add("kb:doi.txt:chunk:0",
            "10.1002/sici Detection of new spider toxins from a Nephilengys borbonica venom gland");
        index.refresh();
        return index;
    }

    /** The same corpus on the engine's own index, published at epoch 1 and read at epoch 2. */
    private static com.eonmux.excalibur.ingest.lexical.NativeChunkIndex nativeIndexed(Path dir,
            java.util.concurrent.atomic.AtomicLong epoch) throws Exception {
        com.eonmux.excalibur.ingest.lexical.NativeChunkIndex index =
            com.eonmux.excalibur.ingest.lexical.NativeChunkIndex.open(dir, epoch::get, 4,
                com.eonmux.excalibur.retrieval.lexical.native_.CompactionPlanner.standard());
        index.add("kb:oom.txt:chunk:0",
            "the kernel OOM killer sent SIGKILL after the cgroup memory limit was exceeded");
        index.add("kb:tls.txt:chunk:0",
            "the TLS handshake failed because the certificate chain could not be verified");
        index.add("kb:doi.txt:chunk:0",
            "10.1002/sici Detection of new spider toxins from a Nephilengys borbonica venom gland");
        index.refresh();
        epoch.incrementAndGet();
        return index;
    }

    @Nested
    @DisplayName("the native engine")
    class NativeEngine {

        @Test
        @DisplayName("a hit survives fusion and is still reported under its chunk id")
        void hitsResolveThroughFusion(@TempDir Path dir) throws Exception {
            // The name has to survive the round trip through ranking and fusion, which carry a
            // 64-bit id and nothing else. A break here reports nothing rather than the wrong
            // thing - unresolvable hits are dropped - so it would read as an empty corpus.
            java.util.concurrent.atomic.AtomicLong epoch =
                new java.util.concurrent.atomic.AtomicLong(1);
            try (com.eonmux.excalibur.ingest.lexical.NativeChunkIndex index = nativeIndexed(dir, epoch)) {
                LexicalQueryService service = new LexicalQueryService(index, null, List.of());

                assertThat(service.search("certificate chain verified", 3))
                    .isNotEmpty()
                    .first()
                    .satisfies(hit -> {
                        assertThat(hit.chunkId()).isEqualTo("kb:tls.txt:chunk:0");
                        assertThat(hit.score()).isGreaterThan(0.0);
                    });
                assertThat(service.search("10.1002/sici", 3))
                    .as("an exact identifier is what BM25 answers well and a vector blurs")
                    .first()
                    .satisfies(hit -> assertThat(hit.chunkId()).isEqualTo("kb:doi.txt:chunk:0"));
                assertThat(service.indexedChunks()).isEqualTo(3);
            }
        }

        @Test
        @DisplayName("a query for words the corpus lacks returns nothing, not something")
        void absentWordsFindNothing(@TempDir Path dir) throws Exception {
            java.util.concurrent.atomic.AtomicLong epoch =
                new java.util.concurrent.atomic.AtomicLong(1);
            try (com.eonmux.excalibur.ingest.lexical.NativeChunkIndex index = nativeIndexed(dir, epoch)) {
                assertThat(new LexicalQueryService(index, null, List.of())
                    .search("zzabsent zzmissing", 3)).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("searching")
    class Searching {

        @Test
        @DisplayName("a hit is reported under the chunk id, not an internal number")
        void hitsResolveToChunkIds(@TempDir Path dir) throws Exception {
            try (ChunkLexicalIndex index = indexed(dir)) {
                List<LexicalQueryService.Hit> hits =
                    new LexicalQueryService(index, null, List.of())
                        .search("certificate chain verified", 3);

                assertThat(hits).isNotEmpty();
                assertThat(hits.get(0).chunkId()).isEqualTo("kb:tls.txt:chunk:0");
                assertThat(hits.get(0).score()).isGreaterThan(0.0);
            }
        }

        @Test
        @DisplayName("a rare identifier is found exactly, which is what a vector blurs")
        void rareIdentifiersAreFirstClass(@TempDir Path dir) throws Exception {
            try (ChunkLexicalIndex index = indexed(dir)) {
                List<LexicalQueryService.Hit> hits =
                    new LexicalQueryService(index, null, List.of())
                        .search("Nephilengys borbonica", 3);

                assertThat(hits.get(0).chunkId()).isEqualTo("kb:doi.txt:chunk:0");
            }
        }

        @Test
        @DisplayName("an expander widens what is reachable")
        void expansionAddsRecall(@TempDir Path dir) throws Exception {
            try (ChunkLexicalIndex index = indexed(dir)) {
                // The query shares no terms with the document; the expansion does.
                QueryExpander expander = query -> List.of(
                    SearchFormulation.original(query),
                    new SearchFormulation("OOM killer SIGKILL", 0.9));

                List<LexicalQueryService.Hit> plain =
                    new LexicalQueryService(index, null, List.of())
                        .search("why did my service get terminated", 3);
                List<LexicalQueryService.Hit> expanded =
                    new LexicalQueryService(index, expander, List.of())
                        .search("why did my service get terminated", 3);

                assertThat(plain).as("plain BM25 cannot bridge the vocabulary").isEmpty();
                assertThat(expanded).isNotEmpty();
                assertThat(expanded.get(0).chunkId()).isEqualTo("kb:oom.txt:chunk:0");
            }
        }

        @Test
        @DisplayName("a blank or zero-limit query asks the index nothing")
        void degenerateQueriesReturnNothing(@TempDir Path dir) throws Exception {
            try (ChunkLexicalIndex index = indexed(dir)) {
                LexicalQueryService service = new LexicalQueryService(index, null, List.of());

                assertThat(service.search("", 5)).isEmpty();
                assertThat(service.search("   ", 5)).isEmpty();
                assertThat(service.search(null, 5)).isEmpty();
                assertThat(service.search("anything", 0)).isEmpty();
            }
        }

        @Test
        @DisplayName("it reports how much of the corpus it can search")
        void reportsIndexedCount(@TempDir Path dir) throws Exception {
            try (ChunkLexicalIndex index = indexed(dir)) {
                assertThat(new LexicalQueryService(index, null, List.of()).indexedChunks())
                    .isEqualTo(3);
            }
        }
    }

    @Nested
    @DisplayName("chunk identifiers")
    class Identifiers {

        @Test
        @DisplayName("the same chunk id always maps to the same engine id")
        void idsAreStable() {
            assertThat(ChunkLexicalIndex.idOf("kb:a.txt:chunk:0"))
                .isEqualTo(ChunkLexicalIndex.idOf("kb:a.txt:chunk:0"));
            assertThat(ChunkLexicalIndex.idOf("kb:a.txt:chunk:0"))
                .isNotEqualTo(ChunkLexicalIndex.idOf("kb:a.txt:chunk:1"));
        }

        @Test
        @DisplayName("an unknown engine id resolves to nothing rather than to a wrong chunk")
        void unknownIdsResolveToNull(@TempDir Path dir) throws Exception {
            try (ChunkLexicalIndex index = indexed(dir)) {
                assertThat(index.chunkIdOf(1234567L)).isNull();
            }
        }

        @Test
        @DisplayName("empty text is not indexed")
        void emptyTextIsSkipped(@TempDir Path dir) throws Exception {
            try (ChunkLexicalIndex index = ChunkLexicalIndex.open(dir, 8.0)) {
                index.add("kb:empty.txt:chunk:0", "");
                index.add("kb:null.txt:chunk:0", null);
                index.refresh();

                assertThat(index.indexedCount()).isZero();
            }
        }
    }
}
