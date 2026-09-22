package com.eonmux.excalibur.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The index and reclamation settings a segment is built and maintained under.
 *
 * <p>These decide the shape of what ends up on disk and how often it is rewritten. Several of
 * them are recorded in the files they produce, so changing one governs what is built next
 * rather than what already exists; the tests that matter here are the ones pinning that a
 * value outside its domain stops the node at startup, where it is one message, instead of at
 * the first segment, where it is a half-built index.</p>
 */
@DisplayName("Shard tuning configuration")
class ConfigShardTuningTest {

    private static ShardConfig shard(Map<String, String> props) {
        return new ShardConfig(new HashMap<>(props));
    }

    @Nested
    @DisplayName("HNSW connectivity")
    class Connectivity {

        @Test
        @DisplayName("a node keeps sixteen neighbours above layer 0 and twice that at it")
        void documentedDefaults() {
            ShardConfig settings = shard(Map.of());

            assertThat(settings.getMaxConnections()).isEqualTo(16);
            assertThat(settings.getMaxConnectionsLayer0()).isEqualTo(32);
        }

        @Test
        @DisplayName("layer 0 follows the configured connectivity when it is not named")
        void layerZeroDerivesFromM() {
            ShardConfig settings =
                shard(Map.of("excalibur.shard.default-max-connections", "24"));

            assertThat(settings.getMaxConnectionsLayer0()).isEqualTo(48);
        }

        @Test
        @DisplayName("layer 0 can be named explicitly")
        void layerZeroCanBeSet() {
            ShardConfig settings = shard(Map.of(
                "excalibur.shard.default-max-connections", "16",
                "excalibur.shard.default-max-connections-layer0", "64"));

            assertThat(settings.getMaxConnectionsLayer0()).isEqualTo(64);
        }

        @Test
        @DisplayName("a graph with fewer than two neighbours per node is not a graph")
        void connectivityHasAFloor() {
            assertThatThrownBy(() ->
                shard(Map.of("excalibur.shard.default-max-connections", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default-max-connections");
        }

        @Test
        @DisplayName("connectivity past what the graph format holds is refused at startup")
        void connectivityHasACeiling() {
            // The graph refuses a wider node when the first one is built. Catching it here
            // makes it a message about a setting instead of a failure on the first document.
            assertThatThrownBy(() ->
                shard(Map.of("excalibur.shard.default-max-connections", "300")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default-max-connections")
                .hasMessageContaining("256");
            assertThatThrownBy(() -> shard(Map.of(
                "excalibur.shard.default-max-connections", "16",
                "excalibur.shard.default-max-connections-layer0", "600")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("512");
        }

        @Test
        @DisplayName("a connectivity that would double past the ceiling stops at it")
        void derivedLayerZeroStopsAtTheCeiling() {
            ShardConfig settings =
                shard(Map.of("excalibur.shard.default-max-connections", "256"));

            assertThat(settings.getMaxConnectionsLayer0()).isEqualTo(512);
        }

        @Test
        @DisplayName("layer 0 narrower than the layers above it is refused as a typo")
        void layerZeroCannotBeNarrower() {
            // Layer 0 is where the walk becomes exhaustive; starving it is never the tuning
            // someone means, and it costs recall exactly where the answer is decided.
            assertThatThrownBy(() -> shard(Map.of(
                "excalibur.shard.default-max-connections", "32",
                "excalibur.shard.default-max-connections-layer0", "16")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("default-max-connections-layer0");
        }
    }

    @Nested
    @DisplayName("Compaction policy")
    class CompactionPolicy {

        @Test
        @DisplayName("the shipped policy is the one the workers were built with")
        void documentedDefaults() {
            ShardConfig settings = shard(Map.of());

            assertThat(settings.getCompactionIntervalMs()).isEqualTo(5000L);
            assertThat(settings.getCompactionTombstoneRatio()).isEqualTo(0.2);
            assertThat(settings.getCompactionWriteLogMb()).isEqualTo(32);
            assertThat(settings.getCompactionMaxConcurrent()).isEqualTo(4);
            assertThat(settings.getHnswCompactionIntervalMs()).isEqualTo(60000L);
            assertThat(settings.getHnswCompactionTombstoneRatio()).isEqualTo(0.2);
        }

        @Test
        @DisplayName("a ratio of zero would compact forever and one past whole is meaningless")
        void ratiosAreFractions() {
            assertThatThrownBy(() ->
                shard(Map.of("excalibur.shard.compaction-tombstone-ratio", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compaction-tombstone-ratio");
            assertThatThrownBy(() ->
                shard(Map.of("excalibur.shard.hnsw-compaction-tombstone-ratio", "1.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hnsw-compaction-tombstone-ratio");
        }

        @Test
        @DisplayName("a sweep interval has a floor, so a typo cannot spin the worker")
        void intervalsHaveAFloor() {
            assertThatThrownBy(() ->
                shard(Map.of("excalibur.shard.compaction-interval-ms", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compaction-interval-ms");
        }

        @Test
        @DisplayName("at least one segment must be compactable at a time")
        void concurrencyHasAFloor() {
            assertThatThrownBy(() ->
                shard(Map.of("excalibur.shard.compaction-max-concurrent", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compaction-max-concurrent");
        }
    }

    @Nested
    @DisplayName("LSH")
    class Lsh {

        @Test
        @DisplayName("the shipped index shape is fifteen tables of sixteen bits, three probes")
        void documentedDefaults() {
            ShardConfig settings = shard(Map.of());

            assertThat(settings.getLshHashTables()).isEqualTo(15);
            assertThat(settings.getLshHashFunctions()).isEqualTo(16);
            assertThat(settings.getLshProbes()).isEqualTo(3);
        }

        @Test
        @DisplayName("none of the three may be zero")
        void allHaveAFloor() {
            for (String key : new String[]{"excalibur.shard.lsh-hash-tables",
                                           "excalibur.shard.lsh-hash-functions",
                                           "excalibur.shard.lsh-probes"}) {
                assertThatThrownBy(() -> shard(Map.of(key, "0")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(key);
            }
        }

        /**
         * 64 hash functions cannot be packed into the long the bucket key is held in, so the
         * index refuses it per segment. Accepting it here would start the node, echo the
         * value, and then blame every segment's on-disk shape for a configuration mistake -
         * sending the operator to reindex a corpus that was never the problem.
         */
        @Test
        @DisplayName("a bucket key wider than the long it is packed into is refused at startup")
        void hashFunctionsAreCappedAtTheFormatCeiling() {
            assertThat(shard(Map.of("excalibur.shard.lsh-hash-functions", "63"))
                .getLshHashFunctions()).isEqualTo(63);
            assertThatThrownBy(
                () -> shard(Map.of("excalibur.shard.lsh-hash-functions", "64")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excalibur.shard.lsh-hash-functions");
        }

        /**
         * Tables multiply hash functions by dimensions by every segment on the node. A
         * mistyped value asks for terabytes, and the OutOfMemoryError lands on an ingestion
         * thread rather than on the start that caused it.
         */
        @Test
        @DisplayName("a table count that would size an impossible allocation is refused")
        void tablesAreCapped() {
            assertThatThrownBy(
                () -> shard(Map.of("excalibur.shard.lsh-hash-tables", "100000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excalibur.shard.lsh-hash-tables");
            assertThatThrownBy(() -> shard(Map.of("excalibur.shard.lsh-probes", "65")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excalibur.shard.lsh-probes");
        }
    }

    @Nested
    @DisplayName("Volumes")
    class Volumes {

        @Test
        @DisplayName("a node keeps everything in its data directory unless told otherwise")
        void defaultsToNone() {
            assertThat(shard(Map.of()).getVolumes()).isEmpty();
        }

        @Test
        @DisplayName("volumes are read in the order given, with blanks dropped")
        void parsesAList() {
            ShardConfig settings =
                shard(Map.of("excalibur.shard.volumes", " /mnt/a , ,/mnt/b "));

            assertThat(settings.getVolumes()).containsExactly("/mnt/a", "/mnt/b");
        }
    }

    @Nested
    @DisplayName("Product quantisation")
    class ProductQuantisation {

        @Test
        @DisplayName("the quantised first pass is off until a deployment asks for it")
        void offByDefault() {
            ShardConfig settings = shard(Map.of());

            assertThat(settings.isPqSearchEnabled()).isFalse();
            assertThat(settings.getPqOversample()).isEqualTo(4);
        }

        @Test
        @DisplayName("a quantised pass that shortlists nothing per result is refused")
        void oversampleHasAFloor() {
            assertThatThrownBy(() -> shard(Map.of("excalibur.shard.pq-oversample", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pq-oversample");
        }
    }
}
