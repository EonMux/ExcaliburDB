package io.excalibur.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shape a segment's product quantiser is trained to.
 *
 * <p>Three numbers decide it: how many subspaces a vector is split into, how many centroids
 * each subspace is clustered to, and how many vectors the clustering is allowed to look at.
 * Together they fix the codebook's memory, its recall and how long a segment spends warming.
 * A deployment with 384-dimensional embeddings and one with 3072-dimensional embeddings do not
 * want the same split, and a node that must finish warming quickly does not want to cluster a
 * hundred thousand vectors per segment.</p>
 */
@DisplayName("Product quantiser training configuration")
class ConfigShardPqTest {

    private static ShardConfig shard(Map<String, String> props) {
        return new ShardConfig(new HashMap<>(props));
    }

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("an unconfigured node trains the shape it has always trained")
        void documentedDefaults() {
            ShardConfig settings = shard(Map.of());

            assertThat(settings.getPqSubspaces()).isEqualTo(8);
            assertThat(settings.getPqCentroids()).isEqualTo(256);
            assertThat(settings.getPqTrainingSamples()).isEqualTo(100_000);
        }
    }

    @Nested
    @DisplayName("subspaces")
    class Subspaces {

        @Test
        @DisplayName("the configured split is the one reported")
        void configured() {
            assertThat(shard(Map.of("excalibur.shard.pq-subspaces", "16")).getPqSubspaces())
                .isEqualTo(16);
        }

        @Test
        @DisplayName("a split of zero is refused rather than dividing by it later")
        void zeroRefused() {
            assertThatThrownBy(() -> shard(Map.of("excalibur.shard.pq-subspaces", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excalibur.shard.pq-subspaces");
        }

        @Test
        @DisplayName("a split past what the quantiser encodes is refused at startup")
        void aboveMaximumRefused() {
            assertThatThrownBy(() -> shard(Map.of("excalibur.shard.pq-subspaces", "129")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excalibur.shard.pq-subspaces");
        }
    }

    @Nested
    @DisplayName("centroids")
    class Centroids {

        @Test
        @DisplayName("the configured codebook size is the one reported")
        void configured() {
            assertThat(shard(Map.of("excalibur.shard.pq-centroids", "64")).getPqCentroids())
                .isEqualTo(64);
        }

        @Test
        @DisplayName("a single centroid is refused: every vector would quantise the same")
        void oneRefused() {
            assertThatThrownBy(() -> shard(Map.of("excalibur.shard.pq-centroids", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excalibur.shard.pq-centroids");
        }

        @Test
        @DisplayName("more centroids than a code byte holds is refused at startup")
        void aboveMaximumRefused() {
            assertThatThrownBy(() -> shard(Map.of("excalibur.shard.pq-centroids", "257")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excalibur.shard.pq-centroids");
        }
    }

    @Nested
    @DisplayName("training samples")
    class TrainingSamples {

        @Test
        @DisplayName("the configured sample ceiling is the one reported")
        void configured() {
            assertThat(shard(Map.of("excalibur.shard.pq-training-samples", "20000"))
                .getPqTrainingSamples()).isEqualTo(20_000);
        }

        @Test
        @DisplayName("zero means every vector in the segment rather than none of them")
        void zeroMeansEveryVector() {
            assertThat(shard(Map.of("excalibur.shard.pq-training-samples", "0"))
                .getPqTrainingSamples()).isZero();
        }

        @Test
        @DisplayName("a negative ceiling is refused")
        void negativeRefused() {
            assertThatThrownBy(() -> shard(Map.of("excalibur.shard.pq-training-samples", "-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excalibur.shard.pq-training-samples");
        }

        @Test
        @DisplayName("fewer samples than centroids is refused: k-means cannot fill the codebook")
        void fewerSamplesThanCentroidsRefused() {
            assertThatThrownBy(() -> shard(Map.of(
                "excalibur.shard.pq-centroids", "256",
                "excalibur.shard.pq-training-samples", "100")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excalibur.shard.pq-training-samples")
                .hasMessageContaining("excalibur.shard.pq-centroids");
        }
    }

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("all three keys are known, so none is warned about as a typo")
        void keysAreRegistered() {
            assertThat(ConfigKeys.isKnown("excalibur.shard.pq-subspaces")).isTrue();
            assertThat(ConfigKeys.isKnown("excalibur.shard.pq-centroids")).isTrue();
            assertThat(ConfigKeys.isKnown("excalibur.shard.pq-training-samples")).isTrue();
        }
    }
}
