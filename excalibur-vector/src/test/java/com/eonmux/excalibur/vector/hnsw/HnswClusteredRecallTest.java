package com.eonmux.excalibur.vector.hnsw;

import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.distance.L2Distance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recall@10 at the default configuration on corpora that have structure.
 *
 * <h2>Why these corpora</h2>
 * <p>Isotropic random vectors are the one shape of data on which choosing a node's
 * neighbours by distance alone and choosing them by the paper's heuristic
 * (Malkov &amp; Yashunin, Algorithm 4) behave alike. Everything an index is actually asked to
 * hold - embeddings of documents that fall into topics - is clustered, and on clustered data
 * the two part company: nearest-only selection spends every edge of a node inside its own
 * cluster, so no edge leads out of it, and a search that enters the graph in one cluster can
 * never leave. These tests index corpora of that shape and require the default configuration
 * to meet the ≥99% recall@10 it is documented to deliver.</p>
 *
 * <h2>The two corpora</h2>
 * <ul>
 *   <li><b>Separated clusters</b> - well-separated Gaussian blobs, each of full dimension. The
 *       harshest test of whether a search can move between clusters at all.</li>
 *   <li><b>Embedding-like</b> - a Gaussian mixture in a low-dimensional latent space mapped
 *       linearly into the full dimension with a little isotropic noise, the shape learned
 *       embeddings have: many directions, few of which carry variance.</li>
 * </ul>
 *
 * <p>Both corpora are fifteen times larger than the default {@code efSearch}, so the layer-0
 * beam is selective rather than an exhaustive walk that would score perfectly on any edge
 * structure.</p>
 */
@DisplayName("HNSW recall@10 on clustered corpora")
class HnswClusteredRecallTest {

    private static final double TARGET_RECALL = 0.99;
    private static final int K = 10;
    private static final int DIMENSION = 64;
    private static final int DATASET_SIZE = 3000;
    private static final int QUERY_COUNT = 100;

    private final DistanceFunction distance = L2Distance.getInstance();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Default config reaches ≥99% recall@10 across well-separated clusters")
    void defaultConfigSearchesAcrossSeparatedClusters() throws IOException {
        int clusters = DATASET_SIZE / 100;
        float[][] centres = unitGaussian(new Random(7L), clusters, DIMENSION);
        float[][] corpus = separatedClusters(new Random(42L), centres, DATASET_SIZE);
        float[][] queries = separatedClusters(new Random(43L), centres, QUERY_COUNT);

        assertThat(recallAt10(corpus, queries))
            .as("recall@10 over %d separated clusters", clusters)
            .isGreaterThanOrEqualTo(TARGET_RECALL);
    }

    @Test
    @DisplayName("Default config reaches ≥99% recall@10 on an embedding-like corpus")
    void defaultConfigSearchesEmbeddingLikeCorpus() throws IOException {
        EmbeddingModel model = EmbeddingModel.create(new Random(9L), 50, 24, DIMENSION);
        float[][] corpus = model.sample(new Random(42L), DATASET_SIZE);
        float[][] queries = model.sample(new Random(43L), QUERY_COUNT);

        assertThat(recallAt10(corpus, queries))
            .as("recall@10 on a 24-dimensional mixture embedded in %d dimensions", DIMENSION)
            .isGreaterThanOrEqualTo(TARGET_RECALL);
    }

    /**
     * Builds a default-configuration graph over the corpus and scores it against brute force.
     */
    private double recallAt10(float[][] corpus, float[][] queries) throws IOException {
        HnswConfig config = HnswConfig.create(DIMENSION);
        try (HnswGraph graph = new HnswGraph(
                tempDir.resolve("clustered.hnsw"), config, distance, corpus.length * 2L)) {
            for (float[] vector : corpus) {
                graph.insert(vector);
            }

            long found = 0;
            for (float[] query : queries) {
                Set<Long> returned = new HashSet<>();
                for (HnswSearchResult result : graph.search(query, K)) {
                    returned.add(result.nodeId());
                }
                for (long expected : trueTopK(corpus, query)) {
                    if (returned.contains(expected)) {
                        found++;
                    }
                }
            }
            return (double) found / ((long) queries.length * K);
        }
    }

    private List<Long> trueTopK(float[][] corpus, float[] query) {
        List<Long> ids = new ArrayList<>(corpus.length);
        for (long id = 0; id < corpus.length; id++) {
            ids.add(id);
        }
        ids.sort(Comparator.comparingDouble(id -> distance.distance(query, corpus[(int) (long) id])));
        return ids.subList(0, K);
    }

    /**
     * Unit vectors, each a centre plus Gaussian noise whose norm is 0.6 of the centre's.
     */
    private static float[][] separatedClusters(Random random, float[][] centres, int count) {
        int dimension = centres[0].length;
        double sigma = 0.6 / Math.sqrt(dimension);
        float[][] vectors = new float[count][dimension];
        for (int i = 0; i < count; i++) {
            float[] centre = centres[random.nextInt(centres.length)];
            for (int d = 0; d < dimension; d++) {
                vectors[i][d] = centre[d] + (float) (sigma * random.nextGaussian());
            }
            normalize(vectors[i]);
        }
        return vectors;
    }

    private static float[][] unitGaussian(Random random, int count, int dimension) {
        float[][] vectors = new float[count][dimension];
        for (int i = 0; i < count; i++) {
            for (int d = 0; d < dimension; d++) {
                vectors[i][d] = (float) random.nextGaussian();
            }
            normalize(vectors[i]);
        }
        return vectors;
    }

    private static void normalize(float[] vector) {
        double sum = 0.0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        float norm = (float) Math.sqrt(sum);
        for (int d = 0; d < vector.length; d++) {
            vector[d] /= norm;
        }
    }

    /**
     * A Gaussian mixture in a latent space, mapped linearly into the full dimension.
     *
     * @param centres Mixture component means in the latent space
     * @param projection Latent-to-full linear map, one row per latent axis
     */
    private record EmbeddingModel(float[][] centres, float[][] projection) {

        private static final double COMPONENT_SPREAD = 0.5;
        private static final double AMBIENT_NOISE = 0.02;

        static EmbeddingModel create(Random random, int components, int latentDimension, int dimension) {
            float[][] centres = new float[components][latentDimension];
            for (float[] centre : centres) {
                for (int a = 0; a < latentDimension; a++) {
                    centre[a] = (float) random.nextGaussian();
                }
            }
            float[][] projection = new float[latentDimension][dimension];
            for (float[] row : projection) {
                for (int d = 0; d < dimension; d++) {
                    row[d] = (float) random.nextGaussian();
                }
            }
            return new EmbeddingModel(centres, projection);
        }

        float[][] sample(Random random, int count) {
            int latentDimension = projection.length;
            int dimension = projection[0].length;
            double noise = AMBIENT_NOISE * Math.sqrt(latentDimension);
            float[][] vectors = new float[count][dimension];
            for (int i = 0; i < count; i++) {
                float[] centre = centres[random.nextInt(centres.length)];
                float[] latent = new float[latentDimension];
                for (int a = 0; a < latentDimension; a++) {
                    latent[a] = centre[a] + (float) (COMPONENT_SPREAD * random.nextGaussian());
                }
                for (int d = 0; d < dimension; d++) {
                    float value = 0.0f;
                    for (int a = 0; a < latentDimension; a++) {
                        value += latent[a] * projection[a][d];
                    }
                    vectors[i][d] = value + (float) (noise * random.nextGaussian());
                }
                normalize(vectors[i]);
            }
            return vectors;
        }
    }
}
