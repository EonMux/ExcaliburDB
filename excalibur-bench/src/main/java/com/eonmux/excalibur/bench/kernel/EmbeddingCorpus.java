package com.eonmux.excalibur.bench.kernel;

import java.util.Objects;
import java.util.Random;

/**
 * A synthetic corpus with the shape of learned text embeddings: unit vectors drawn from a
 * Gaussian mixture in a low-dimensional latent space and mapped linearly into the full
 * dimension, plus a little isotropic noise.
 *
 * <h2>Why not isotropic random vectors</h2>
 * <p>An ANN recall figure is a property of the index <em>and</em> the data. Isotropic Gaussian
 * vectors have an intrinsic dimension equal to their ambient one - the worst case for every
 * proximity graph, because in 128 dimensions a point's nearest neighbours are barely nearer
 * than a random point. On 10,000 such vectors in 128 dimensions a textbook HNSW (M=16,
 * efConstruction=200) recovers 92-96% of the true top ten at efSearch=200, depending on how
 * many neighbours a new node takes at layer 0, so a gate on that data measures the data rather
 * than the index. Real embeddings spread their variance over a few dozen directions and fall
 * into topics; both properties are what this corpus reproduces, and the clustering is also
 * what exposes a graph whose edges never leave a cluster.</p>
 *
 * <p>The latent dimension and spread are set so the corpus is not trivially easy: over 10,000
 * vectors in 128 dimensions a textbook HNSW finds about 97% of the true top ten at
 * efSearch=50 and all of it by efSearch=200, so the recall-against-efSearch curve the
 * benchmark exists to show is still there to see.</p>
 *
 * <h2>Determinism</h2>
 * <p>The mixture - its component means and the latent-to-full map - is fixed by one seed, so
 * a corpus and its queries sampled from the same model with different seeds share structure
 * while never sharing points.</p>
 */
public final class EmbeddingCorpus {

    /**
     * Mixture components, the corpus's topics.
     */
    public static final int COMPONENTS = 50;

    /**
     * Directions carrying variance before the map into the full dimension.
     */
    public static final int LATENT_DIMENSION = 48;

    /**
     * Standard deviation of a point around its component mean, per latent axis, relative to
     * the unit spread of the means themselves.
     */
    private static final double COMPONENT_SPREAD = 2.0;

    /**
     * Isotropic noise added in the full dimension, relative to a latent axis's scale.
     */
    private static final double AMBIENT_NOISE = 0.02;

    private final float[][] means;
    private final float[][] projection;

    private EmbeddingCorpus(float[][] means, float[][] projection) {
        this.means = means;
        this.projection = projection;
    }

    /**
     * Creates the mixture for one dimension.
     *
     * @param structureSeed Seed fixing the component means and the latent-to-full map
     * @param dimension The full dimension of the vectors to produce
     * @return The mixture model
     * @throws IllegalArgumentException if {@code dimension} is not positive
     */
    public static EmbeddingCorpus create(long structureSeed, int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive, got " + dimension);
        }
        Random random = new Random(structureSeed);
        return new EmbeddingCorpus(
            gaussian(random, COMPONENTS, LATENT_DIMENSION),
            gaussian(random, LATENT_DIMENSION, dimension));
    }

    /**
     * Draws unit vectors from the mixture.
     *
     * @param random The source of the draw
     * @param count How many vectors to draw
     * @return {@code count} unit vectors of the model's dimension
     * @throws IllegalArgumentException if {@code count} is not positive
     */
    public float[][] sample(Random random, int count) {
        Objects.requireNonNull(random, "random");
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive, got " + count);
        }
        float[][] vectors = new float[count][];
        for (int i = 0; i < count; i++) {
            vectors[i] = Vectors.normalized(embed(latentPoint(random), random));
        }
        return vectors;
    }

    /**
     * A point near a randomly chosen component mean, in the latent space.
     */
    private float[] latentPoint(Random random) {
        float[] mean = means[random.nextInt(means.length)];
        float[] point = new float[mean.length];
        for (int a = 0; a < point.length; a++) {
            point[a] = mean[a] + (float) (COMPONENT_SPREAD * random.nextGaussian());
        }
        return point;
    }

    /**
     * Maps a latent point into the full dimension and adds the ambient noise.
     */
    private float[] embed(float[] latent, Random random) {
        int dimension = projection[0].length;
        double noise = AMBIENT_NOISE * Math.sqrt(latent.length);
        float[] vector = new float[dimension];
        for (int d = 0; d < dimension; d++) {
            double value = 0.0;
            for (int a = 0; a < latent.length; a++) {
                value += (double) latent[a] * projection[a][d];
            }
            vector[d] = (float) (value + noise * random.nextGaussian());
        }
        return vector;
    }

    private static float[][] gaussian(Random random, int rows, int columns) {
        float[][] matrix = new float[rows][columns];
        for (float[] row : matrix) {
            for (int c = 0; c < columns; c++) {
                row[c] = (float) random.nextGaussian();
            }
        }
        return matrix;
    }
}
