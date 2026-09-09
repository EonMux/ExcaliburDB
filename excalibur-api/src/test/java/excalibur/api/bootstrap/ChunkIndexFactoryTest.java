package excalibur.api.bootstrap;

import excalibur.ingest.lexical.ChunkIndex;

import io.excalibur.config.Config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which chunk index a node opens, and where it puts it.
 *
 * <p>This is the one bootstrap decision whose failure mode is silence. The two engines write to
 * different directories and neither reads the other's, so a node that opened the wrong one finds
 * an empty index and answers every query with nothing - which reads exactly like a corpus that
 * does not contain what was asked for. The tests below pin the engine, the directory and the
 * compaction floor, because none of the three announces itself when it is wrong.
 */
@DisplayName("Chunk index factory")
class ChunkIndexFactoryTest {

    private static Config config(Map<String, String> overrides) {
        Map<String, String> props = new HashMap<>();
        props.put("excalibur.graph.ram-buffer-mb", "4.0");
        props.putAll(overrides);
        return new Config(props);
    }

    @Nested
    @DisplayName("Engine selection")
    class Engines {

        @Test
        @DisplayName("the default engine is the native one, in its own directory")
        void defaultsToNative(@TempDir Path dir) throws Exception {
            try (ChunkIndex index = ChunkIndexFactory.open(config(Map.of()), dir, () -> 1L)) {
                index.add("kb:a.txt:chunk:0", "memory pressure on the host");
                index.refresh();

                assertThat(index.engine()).isEqualTo("native");
                assertThat(Files.isDirectory(dir.resolve(ChunkIndexFactory.NATIVE_DIRECTORY)))
                    .isTrue();
                assertThat(Files.exists(dir.resolve(ChunkIndexFactory.LUCENE_DIRECTORY)))
                    .as("the engine that was not chosen writes nothing at all")
                    .isFalse();
            }
        }

        @Test
        @DisplayName("Lucene is opened when it is asked for, and nothing else is")
        void luceneIsSelectable(@TempDir Path dir) throws Exception {
            Config lucene = config(Map.of("excalibur.ingest.lexical-engine", "lucene"));
            try (ChunkIndex index = ChunkIndexFactory.open(lucene, dir, () -> 1L)) {
                index.add("kb:a.txt:chunk:0", "memory pressure on the host");
                index.refresh();

                assertThat(index.engine()).isEqualTo("lucene");
                assertThat(Files.isDirectory(dir.resolve(ChunkIndexFactory.LUCENE_DIRECTORY)))
                    .isTrue();
                assertThat(Files.exists(dir.resolve(ChunkIndexFactory.NATIVE_DIRECTORY)))
                    .isFalse();
            }
        }

        @Test
        @DisplayName("either engine indexes and answers through the same interface")
        void bothEnginesAnswer(@TempDir Path dir) throws Exception {
            for (String engine : new String[] {"native", "lucene"}) {
                Path root = Files.createDirectories(dir.resolve(engine));
                Config settings = config(Map.of("excalibur.ingest.lexical-engine", engine));
                // A moving epoch, as the node's tick scheduler provides. The native engine
                // publishes a run at the epoch after the current one, so a frozen clock would
                // never reach it - which is the visibility rule working, not a fault.
                java.util.concurrent.atomic.AtomicLong epoch =
                    new java.util.concurrent.atomic.AtomicLong(9);
                try (ChunkIndex index = ChunkIndexFactory.open(settings, root, epoch::get)) {
                    index.add("kb:oom.txt:chunk:0",
                        "the OOM killer terminated the process under memory pressure");
                    index.refresh();
                    epoch.incrementAndGet();

                    var ranked = index.ranker(io.excalibur.retrieval.expand.QueryExpander.identity())
                        .rank(io.excalibur.retrieval.rank.RetrievalQuery.ofText("memory"), 5);

                    assertThat(ranked.documents()).as(engine + " found the chunk").isNotEmpty();
                    assertThat(index.chunkIdOf(ranked.documents().get(0).documentId()))
                        .as(engine + " resolves a hit to its chunk id")
                        .isEqualTo("kb:oom.txt:chunk:0");
                }
            }
        }

        @Test
        @DisplayName("required collaborators are refused rather than defaulted")
        void nullsAreRefused(@TempDir Path dir) {
            assertThatThrownBy(() -> ChunkIndexFactory.open(null, dir, () -> 1L))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ChunkIndexFactory.open(config(Map.of()), null, () -> 1L))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ChunkIndexFactory.open(config(Map.of()), dir, null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("The compaction floor")
    class Floor {

        @Test
        @DisplayName("it is the query timeout measured in epochs")
        void derivedFromTheQueryTimeout() {
            // A query cannot outlive its timeout, so this is how stale the oldest reader's
            // snapshot can be. 30s of queries at a 100ms tick is 300 epochs.
            assertThat(ChunkIndexFactory.readerLagEpochs(config(Map.of(
                "excalibur.visibility.query-timeout-ms", "30000",
                "excalibur.visibility.epoch-tick-ms", "100")))).isEqualTo(300);

            assertThat(ChunkIndexFactory.readerLagEpochs(config(Map.of(
                "excalibur.visibility.query-timeout-ms", "60000",
                "excalibur.visibility.epoch-tick-ms", "100"))))
                .as("raising the timeout raises the floor with it, without a second setting")
                .isEqualTo(600);
        }

        @Test
        @DisplayName("it is never zero, however short the timeout")
        void neverZero() {
            // A floor at the current epoch would let a merge retire a segment a query captured a
            // moment ago, which is the one thing the floor exists to prevent.
            assertThat(ChunkIndexFactory.readerLagEpochs(config(Map.of(
                "excalibur.visibility.query-timeout-ms", "50",
                "excalibur.visibility.epoch-tick-ms", "1000")))).isEqualTo(1);
        }
    }
}
