package io.excalibur.vector.quantization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Objects;

/**
 * Product Quantization for vector compression and approximate distance computation.
 *
 * <p>Product Quantization (PQ) compresses high-dimensional vectors by:</p>
 * <ol>
 *   <li>Splitting each vector into {@code numSubspaces} sub-vectors</li>
 *   <li>Quantizing each sub-vector using a codebook of {@code numCentroids} centroids</li>
 *   <li>Encoding each sub-vector as the index of its nearest centroid (typically 1 byte)</li>
 * </ol>
 *
 * <h2>Compression Example</h2>
 * <pre>
 * Original: 128-dim float vector = 512 bytes
 * PQ with 16 subspaces, 256 centroids: 16 bytes (32x compression)
 * </pre>
 *
 * <h2>Asymmetric Distance Computation</h2>
 * <p>PQ enables efficient approximate distance computation via:</p>
 * <ul>
 *   <li>Precomputing distances from query sub-vectors to all centroids</li>
 *   <li>Looking up precomputed distances using PQ codes</li>
 *   <li>Summing looked-up distances for approximate total distance</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>PQ codes are always {@code numSubspaces} bytes long</li>
 *   <li>Each code value is in [0, numCentroids-1]</li>
 *   <li>Asymmetric distances are always non-negative</li>
 *   <li>Compressed representation is exactly numSubspaces bytes per vector</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p><strong>This class is thread-safe for encoding and distance computation
 * after training is complete.</strong> The {@link #train(float[][])} method is NOT
 * thread-safe and should be called exactly once before use.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class ProductQuantizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProductQuantizer.class);

    /**
     * Default number of subspaces (compression factor: 32x for 128D vectors).
     */
    public static final int DEFAULT_NUM_SUBSPACES = 16;

    /**
     * Default number of centroids per subspace (256 = 1 byte per code).
     */
    public static final int DEFAULT_NUM_CENTROIDS = 256;

    /**
     * Maximum number of centroids (limited to byte encoding).
     */
    public static final int MAX_NUM_CENTROIDS = 256;

    // Configuration
    private final int originalDim;
    private final int numSubspaces;
    private final int subvectorDim;
    private final int numCentroids;

    // Trained codebooks: codebooks[subspace][centroid][dimension]
    // Volatile for safe publication after training
    private volatile float[][][] codebooks;

    // Training state
    private volatile boolean trained = false;
    private final CodebookTrainer trainer;

    /**
     * Creates a new ProductQuantizer with default settings.
     *
     * @param originalDim Original vector dimension (must be &gt; 0)
     * @throws IllegalArgumentException if dimension is invalid
     */
    public ProductQuantizer(int originalDim) {
        this(originalDim, DEFAULT_NUM_SUBSPACES, DEFAULT_NUM_CENTROIDS, null);
    }

    /**
     * Creates a new ProductQuantizer with specified configuration.
     *
     * @param originalDim Original vector dimension (must be &gt; 0)
     * @param numSubspaces Number of subspaces (originalDim must be divisible by this)
     * @param numCentroids Number of centroids per subspace (typically 256 for byte encoding)
     * @throws IllegalArgumentException if parameters are invalid
     */
    public ProductQuantizer(int originalDim, int numSubspaces, int numCentroids) {
        this(originalDim, numSubspaces, numCentroids, null);
    }

    /**
     * Creates a new ProductQuantizer with custom trainer.
     *
     * @param originalDim Original vector dimension
     * @param numSubspaces Number of subspaces
     * @param numCentroids Number of centroids per subspace
     * @param trainer Custom codebook trainer (null = use default)
     * @throws IllegalArgumentException if parameters are invalid
     */
    public ProductQuantizer(int originalDim, int numSubspaces, int numCentroids, CodebookTrainer trainer) {
        if (originalDim < 1) {
            throw new IllegalArgumentException("originalDim must be >= 1: " + originalDim);
        }
        if (numSubspaces < 1) {
            throw new IllegalArgumentException("numSubspaces must be >= 1: " + numSubspaces);
        }
        if (numCentroids < 2 || numCentroids > MAX_NUM_CENTROIDS) {
            throw new IllegalArgumentException(
                "numCentroids must be in [2, " + MAX_NUM_CENTROIDS + "]: " + numCentroids);
        }
        if (originalDim % numSubspaces != 0) {
            throw new IllegalArgumentException(
                "originalDim (" + originalDim + ") must be divisible by numSubspaces (" + numSubspaces + ")");
        }

        this.originalDim = originalDim;
        this.numSubspaces = numSubspaces;
        this.numCentroids = numCentroids;
        this.subvectorDim = originalDim / numSubspaces;
        this.trainer = trainer != null ? trainer : new CodebookTrainer();

        LOGGER.debug("ProductQuantizer configured: originalDim={}, numSubspaces={}, numCentroids={}, subvectorDim={}",
            originalDim, numSubspaces, numCentroids, subvectorDim);
    }

    /**
     * Trains the product quantizer on sample vectors.
     *
     * <p>This method must be called exactly once before encoding vectors.
     * Training uses K-means clustering on each subspace to generate codebooks.</p>
     *
     * <h3>Training Requirements</h3>
     * <ul>
     *   <li>Sample vectors must have dimension matching {@link #originalDim()}</li>
     *   <li>Sufficient samples for good clustering (recommended: numCentroids * 50)</li>
     *   <li>Representative samples covering the expected data distribution</li>
     * </ul>
     *
     * <h3>Thread Safety</h3>
     * <p>This method is NOT thread-safe. Call once during initialization.</p>
     *
     * @param sampleVectors Sample vectors for training (will be sampled if too large)
     * @throws IllegalArgumentException if sample vectors are invalid
     * @throws IllegalStateException if already trained
     */
    public void train(float[][] sampleVectors) {
        if (trained) {
            throw new IllegalStateException("ProductQuantizer already trained");
        }

        Objects.requireNonNull(sampleVectors, "Sample vectors cannot be null");
        if (sampleVectors.length == 0) {
            throw new IllegalArgumentException("Sample vectors cannot be empty");
        }

        LOGGER.info("Training ProductQuantizer on {} sample vectors", sampleVectors.length);

        // Train codebooks
        this.codebooks = trainer.trainCodebooks(sampleVectors, numSubspaces, numCentroids);

        // Validate codebooks
        if (codebooks.length != numSubspaces) {
            throw new IllegalStateException("Training produced wrong number of codebooks");
        }
        for (int s = 0; s < numSubspaces; s++) {
            if (codebooks[s].length != numCentroids) {
                throw new IllegalStateException(
                    "Codebook " + s + " has wrong number of centroids: " + codebooks[s].length);
            }
            for (int c = 0; c < numCentroids; c++) {
                if (codebooks[s][c].length != subvectorDim) {
                    throw new IllegalStateException(
                        "Centroid " + c + " in codebook " + s + " has wrong dimension");
                }
            }
        }

        trained = true;
        LOGGER.info("ProductQuantizer training complete");
    }

    /**
     * Sets pre-trained codebooks directly.
     *
     * <p>Use this method to load previously trained codebooks instead of training.</p>
     *
     * @param codebooks Pre-trained codebooks: {@code codebooks[subspace][centroid][dimension]}
     * @throws IllegalArgumentException if codebooks are invalid
     * @throws IllegalStateException if already trained
     */
    public void setCodebooks(float[][][] codebooks) {
        if (trained) {
            throw new IllegalStateException("ProductQuantizer already trained");
        }

        Objects.requireNonNull(codebooks, "Codebooks cannot be null");

        if (codebooks.length != numSubspaces) {
            throw new IllegalArgumentException(
                "Expected " + numSubspaces + " codebooks, got " + codebooks.length);
        }

        // Deep copy and validate
        float[][][] copy = new float[numSubspaces][numCentroids][subvectorDim];
        for (int s = 0; s < numSubspaces; s++) {
            if (codebooks[s] == null) {
                throw new IllegalArgumentException("Codebook " + s + " is null");
            }
            if (codebooks[s].length != numCentroids) {
                throw new IllegalArgumentException(
                    "Codebook " + s + " has " + codebooks[s].length + " centroids, expected " + numCentroids);
            }
            for (int c = 0; c < numCentroids; c++) {
                if (codebooks[s][c] == null) {
                    throw new IllegalArgumentException("Centroid " + c + " in codebook " + s + " is null");
                }
                if (codebooks[s][c].length != subvectorDim) {
                    throw new IllegalArgumentException(
                        "Centroid " + c + " in codebook " + s + " has dimension " + codebooks[s][c].length +
                        ", expected " + subvectorDim);
                }
                copy[s][c] = codebooks[s][c].clone();
            }
        }

        this.codebooks = copy;
        trained = true;

        LOGGER.info("ProductQuantizer initialized with pre-trained codebooks");
    }

    /**
     * Encodes a vector into PQ codes.
     *
     * <p>Each sub-vector is mapped to the index of its nearest centroid in the
     * corresponding codebook. The result is a byte array of length {@code numSubspaces}.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Output length is always {@code numSubspaces}</li>
     *   <li>Each byte is in [0, numCentroids-1]</li>
     *   <li>Encoding is deterministic for the same input</li>
     * </ul>
     *
     * @param vector The vector to encode (must match originalDim)
     * @return PQ codes as byte array
     * @throws IllegalArgumentException if vector dimension doesn't match
     * @throws IllegalStateException if not trained
     */
    public byte[] encode(float[] vector) {
        ensureTrained();
        validateVector(vector);

        byte[] codes = new byte[numSubspaces];

        for (int s = 0; s < numSubspaces; s++) {
            int startOffset = s * subvectorDim;
            int nearestCentroid = findNearestCentroid(vector, startOffset, s);
            codes[s] = (byte) nearestCentroid;
        }

        return codes;
    }

    /**
     * Encodes a vector into PQ codes, returning null if the quantizer is not trained.
     *
     * <p>This method provides a graceful fallback option for encoding during the
     * indexing phase when the codebook may not yet be trained. Callers can check
     * for null and fall back to full-precision operations.</p>
     *
     * <h3>Use Case</h3>
     * <p>Use this method during live indexing when:</p>
     * <ul>
     *   <li>The segment is in INDEXING or WARMING state</li>
     *   <li>The codebook is being trained in the background</li>
     *   <li>You want to optionally encode if possible, but continue if not</li>
     * </ul>
     *
     * @param vector The vector to encode (must match originalDim)
     * @return PQ codes as byte array, or null if quantizer is not trained
     * @throws IllegalArgumentException if vector dimension doesn't match
     */
    public byte[] encodeOrNull(float[] vector) {
        // Validate dimension first, regardless of training state
        validateVector(vector);

        if (!trained) {
            return null;
        }

        byte[] codes = new byte[numSubspaces];

        for (int s = 0; s < numSubspaces; s++) {
            int startOffset = s * subvectorDim;
            int nearestCentroid = findNearestCentroid(vector, startOffset, s);
            codes[s] = (byte) nearestCentroid;
        }

        return codes;
    }

    /**
     * Encodes multiple vectors in batch.
     *
     * <p>More efficient than individual encoding for large batches due to
     * better cache locality.</p>
     *
     * @param vectors Vectors to encode
     * @return Array of PQ codes for each vector
     * @throws IllegalArgumentException if any vector is invalid
     * @throws IllegalStateException if not trained
     */
    public byte[][] encodeBatch(float[][] vectors) {
        ensureTrained();
        Objects.requireNonNull(vectors, "Vectors cannot be null");

        byte[][] codes = new byte[vectors.length][];
        for (int i = 0; i < vectors.length; i++) {
            codes[i] = encode(vectors[i]);
        }
        return codes;
    }

    /**
     * Computes asymmetric distance between a query vector and a PQ-encoded vector.
     *
     * <p>Asymmetric distance uses the full-precision query vector and the quantized
     * database vector. This provides better accuracy than symmetric distance
     * (both quantized) while still being efficient.</p>
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Split query into sub-vectors</li>
     *   <li>For each subspace, compute distance from query sub-vector to the centroid
     *       indicated by the PQ code</li>
     *   <li>Sum all distances for approximate total distance</li>
     * </ol>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Result is always non-negative (sum of squared distances)</li>
     *   <li>Result is an approximation of the true L2 distance</li>
     * </ul>
     *
     * @param query Full-precision query vector
     * @param codes PQ codes for the database vector
     * @return Approximate squared L2 distance
     * @throws IllegalArgumentException if inputs are invalid
     * @throws IllegalStateException if not trained
     */
    public float asymmetricDistance(float[] query, byte[] codes) {
        ensureTrained();
        validateVector(query);

        if (codes == null) {
            throw new IllegalArgumentException("Codes cannot be null");
        }
        if (codes.length != numSubspaces) {
            throw new IllegalArgumentException(
                "Codes length (" + codes.length + ") must match numSubspaces (" + numSubspaces + ")");
        }

        double totalDistance = 0.0;

        for (int s = 0; s < numSubspaces; s++) {
            // Convert unsigned byte to centroid index
            int centroidIdx = codes[s] & 0xFF;
            if (centroidIdx >= numCentroids) {
                throw new IllegalArgumentException(
                    "Invalid centroid index " + centroidIdx + " in subspace " + s);
            }

            // Compute distance from query sub-vector to centroid
            int startOffset = s * subvectorDim;
            float[] centroid = codebooks[s][centroidIdx];

            double subDist = 0.0;
            for (int d = 0; d < subvectorDim; d++) {
                double diff = (double) query[startOffset + d] - (double) centroid[d];
                subDist += diff * diff;
            }

            totalDistance += subDist;
        }

        return (float) totalDistance;
    }

    /**
     * Computes distance between query and target, with graceful fallback for untrained quantizer.
     *
     * <p>This method implements the PQ graceful fallback requirement from the specification:</p>
     * <blockquote>"if codebook is not yet trained (cold segment), distance computation uses
     * full-precision vectors."</blockquote>
     *
     * <h3>Behavior</h3>
     * <ul>
     *   <li>If trained: Uses asymmetric PQ distance for efficiency (query is full-precision,
     *       target is PQ-encoded on-the-fly)</li>
     *   <li>If not trained: Computes exact L2 squared distance using full-precision vectors</li>
     * </ul>
     *
     * <h3>Use Case</h3>
     * <p>This method is designed for querying cold segments during the indexing phase,
     * when the codebook may not yet be trained. It allows queries to proceed with
     * full-precision computation until PQ is available.</p>
     *
     * <h3>Domain Correctness</h3>
     * <ul>
     *   <li>Result is always non-negative (squared L2 distance)</li>
     *   <li>Both vectors must have the same dimension as the quantizer</li>
     *   <li>When trained, result is approximate (PQ quantization error)</li>
     *   <li>When not trained, result is exact</li>
     * </ul>
     *
     * @param query Full-precision query vector
     * @param target Full-precision target vector
     * @return Squared L2 distance (exact if untrained, approximate if trained)
     * @throws IllegalArgumentException if vectors are null or wrong dimension
     */
    public float computeDistanceOrFallback(float[] query, float[] target) {
        validateVector(query);
        validateVector(target);

        // If not trained, compute exact L2 distance
        if (!trained) {
            double totalDistance = 0.0;
            for (int i = 0; i < originalDim; i++) {
                double diff = (double) query[i] - (double) target[i];
                totalDistance += diff * diff;
            }
            return (float) totalDistance;
        }

        // If trained, use asymmetric PQ distance
        // Encode target on-the-fly and compute asymmetric distance
        byte[] targetCodes = encode(target);
        return asymmetricDistance(query, targetCodes);
    }

    /**
     * Creates a precomputed distance table for efficient batch distance computation.
     *
     * <p>The distance table stores distances from each query sub-vector to all
     * centroids in the corresponding codebook. This allows O(numSubspaces) distance
     * lookups instead of O(dimension) distance computations.</p>
     *
     * @param query The query vector
     * @return Precomputed distance table
     * @throws IllegalArgumentException if query is invalid
     * @throws IllegalStateException if not trained
     */
    public PQDistanceTable createDistanceTable(float[] query) {
        return createDistanceTable(query, null);
    }

    /**
     * Creates a precomputed distance table for a specific metric.
     *
     * @param query  The query vector
     * @param distFn The metric the caller ranks with; null means squared L2
     * @return Precomputed distance table
     * @throws IllegalArgumentException if query is invalid
     * @throws IllegalStateException if not trained
     */
    public PQDistanceTable createDistanceTable(
            float[] query, io.excalibur.vector.distance.DistanceFunction distFn) {
        ensureTrained();
        validateVector(query);
        return new PQDistanceTable(query, this, distFn);
    }

    /**
     * Decodes PQ codes back to an approximate vector.
     *
     * <p>This reconstructs the vector by looking up centroids for each code.
     * The result is an approximation since quantization loses information.</p>
     *
     * @param codes PQ codes
     * @return Approximate reconstructed vector
     * @throws IllegalArgumentException if codes are invalid
     * @throws IllegalStateException if not trained
     */
    public float[] decode(byte[] codes) {
        ensureTrained();

        if (codes == null) {
            throw new IllegalArgumentException("Codes cannot be null");
        }
        if (codes.length != numSubspaces) {
            throw new IllegalArgumentException(
                "Codes length (" + codes.length + ") must match numSubspaces (" + numSubspaces + ")");
        }

        float[] reconstructed = new float[originalDim];

        for (int s = 0; s < numSubspaces; s++) {
            int centroidIdx = codes[s] & 0xFF;
            if (centroidIdx >= numCentroids) {
                throw new IllegalArgumentException(
                    "Invalid centroid index " + centroidIdx + " in subspace " + s);
            }

            int startOffset = s * subvectorDim;
            float[] centroid = codebooks[s][centroidIdx];
            System.arraycopy(centroid, 0, reconstructed, startOffset, subvectorDim);
        }

        return reconstructed;
    }

    /**
     * Finds the nearest centroid for a sub-vector.
     *
     * @param vector Full vector
     * @param startOffset Start offset of the sub-vector
     * @param subspace Subspace index
     * @return Index of the nearest centroid
     */
    private int findNearestCentroid(float[] vector, int startOffset, int subspace) {
        float[][] codebook = codebooks[subspace];

        double minDist = Double.MAX_VALUE;
        int minIdx = 0;

        for (int c = 0; c < numCentroids; c++) {
            double dist = 0.0;
            for (int d = 0; d < subvectorDim; d++) {
                double diff = (double) vector[startOffset + d] - (double) codebook[c][d];
                dist += diff * diff;
            }

            if (dist < minDist) {
                minDist = dist;
                minIdx = c;
            }
        }

        return minIdx;
    }

    /**
     * Validates a vector's dimension.
     */
    private void validateVector(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("Vector cannot be null");
        }
        if (vector.length != originalDim) {
            throw new IllegalArgumentException(
                "Vector dimension (" + vector.length + ") must match originalDim (" + originalDim + ")");
        }
    }

    /**
     * Ensures the quantizer has been trained.
     */
    private void ensureTrained() {
        if (!trained) {
            throw new IllegalStateException("ProductQuantizer must be trained before use");
        }
    }

    // ==================== Accessors ====================

    /**
     * Returns the original vector dimension.
     */
    public int originalDim() {
        return originalDim;
    }

    /**
     * Returns the number of subspaces.
     */
    public int numSubspaces() {
        return numSubspaces;
    }

    /**
     * Returns the dimension of each sub-vector.
     */
    public int subvectorDim() {
        return subvectorDim;
    }

    /**
     * Returns the number of centroids per subspace.
     */
    public int numCentroids() {
        return numCentroids;
    }

    /**
     * Returns whether the quantizer has been trained.
     */
    public boolean isTrained() {
        return trained;
    }

    /**
     * Returns the codebooks (for serialization).
     *
     * <p><strong>Note:</strong> Returns a reference to internal state.
     * Do not modify the returned array.</p>
     *
     * @return Codebooks array, or null if not trained
     */
    public float[][][] getCodebooks() {
        return codebooks;
    }

    /**
     * Returns the compressed size per vector in bytes.
     */
    public int codeSize() {
        return numSubspaces;
    }

    /**
     * Returns the compression ratio.
     *
     * <p>Original size = originalDim * 4 bytes (float)
     * Compressed size = numSubspaces bytes</p>
     */
    public float compressionRatio() {
        return (float) (originalDim * 4) / numSubspaces;
    }

    @Override
    public String toString() {
        return String.format("ProductQuantizer{dim=%d, subspaces=%d, centroids=%d, trained=%s}",
            originalDim, numSubspaces, numCentroids, trained);
    }
}
