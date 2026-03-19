package excalibur.filter.lsh;

import java.util.SplittableRandom;

/**
 * Random hyperplane hash function for Locality-Sensitive Hashing (LSH).
 *
 * <h2>Overview</h2>
 * <p>This class implements the random hyperplane LSH family for cosine similarity.
 * Each hash function is defined by a random hyperplane through the origin.
 * The hash of a vector is determined by which side of the hyperplane it falls on.</p>
 *
 * <h2>Algorithm</h2>
 * <p>For a vector v and a random hyperplane defined by normal vector h:</p>
 * <ul>
 *   <li>Compute dot product: d = v · h</li>
 *   <li>Hash bit = sign(d) = 1 if d >= 0, else 0</li>
 * </ul>
 * <p>Multiple hyperplanes are combined to form a longer hash. Two vectors with
 * angle θ between them have collision probability: 1 - θ/π</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li><strong>Hyperplanes</strong>: Random unit vectors sampled from uniform distribution
 *       on the unit sphere using Gaussian normalization. This ensures unbiased projections.</li>
 *   <li><strong>Hash bits</strong>: Each bit is 0 or 1 with equal probability for random vectors.</li>
 *   <li><strong>Hash value</strong>: Up to 63 bits (positive long values only).</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe for read operations. The hyperplanes are immutable
 * after construction.</p>
 *
 * @author ExcaliburDB
 */
public final class RandomHyperplaneHash {

    /** Number of bits in the hash (one per hyperplane). */
    private final int numBits;

    /** Random hyperplane normal vectors (numBits x dimensions). */
    private final float[][] hyperplanes;

    /** Vector dimensionality. */
    private final int dimensions;

    /**
     * Creates a new RandomHyperplaneHash with the specified parameters.
     *
     * @param dimensions Vector dimensionality (e.g., 768 for embeddings)
     * @param numBits    Number of hash bits (hyperplanes), max 63
     * @param seed       Random seed for reproducible hyperplane generation
     * @throws IllegalArgumentException if parameters are invalid
     */
    public RandomHyperplaneHash(int dimensions, int numBits, long seed) {
        if (dimensions < 1) {
            throw new IllegalArgumentException("dimensions must be >= 1, got: " + dimensions);
        }
        if (numBits < 1 || numBits > 63) {
            throw new IllegalArgumentException("numBits must be in range [1, 63], got: " + numBits);
        }

        this.dimensions = dimensions;
        this.numBits = numBits;
        this.hyperplanes = new float[numBits][dimensions];

        // Generate random hyperplanes using Gaussian distribution
        // This produces uniformly distributed unit vectors on the sphere
        SplittableRandom random = new SplittableRandom(seed);
        for (int i = 0; i < numBits; i++) {
            generateRandomUnitVector(hyperplanes[i], random);
        }
    }

    /**
     * Generates a random unit vector using Gaussian normalization.
     * This produces uniformly distributed points on the unit sphere.
     *
     * <p>Domain constraint: Each component is sampled from N(0,1), then normalized
     * to unit length. This is the standard method for uniform spherical sampling.</p>
     *
     * @param vector The vector to fill (must have length == dimensions)
     * @param random The random number generator
     */
    private void generateRandomUnitVector(float[] vector, SplittableRandom random) {
        // Sample from Gaussian distribution
        double sumSquares = 0.0;
        for (int i = 0; i < dimensions; i++) {
            // Box-Muller transform for Gaussian random numbers
            double u1 = random.nextDouble();
            double u2 = random.nextDouble();
            // Avoid log(0)
            while (u1 == 0) {
                u1 = random.nextDouble();
            }
            double gaussian = Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
            vector[i] = (float) gaussian;
            sumSquares += gaussian * gaussian;
        }

        // Normalize to unit length
        double norm = Math.sqrt(sumSquares);
        if (norm > 0) {
            for (int i = 0; i < dimensions; i++) {
                vector[i] /= norm;
            }
        } else {
            // Fallback: use first basis vector if all zeros (extremely rare)
            vector[0] = 1.0f;
        }
    }

    /**
     * Computes the hash of a vector using all hyperplanes.
     *
     * <p>The hash is computed by:</p>
     * <ol>
     *   <li>For each hyperplane h_i, compute dot(v, h_i)</li>
     *   <li>If dot >= 0, set bit i to 1, else 0</li>
     *   <li>Concatenate bits into a long value</li>
     * </ol>
     *
     * <p>Domain constraint: Returns a non-negative long value (bit 63 is always 0).</p>
     *
     * @param vector The input vector (must have length == dimensions)
     * @return The hash value (non-negative, up to 63 bits)
     * @throws IllegalArgumentException if vector is null or wrong size
     */
    public long hash(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("vector cannot be null");
        }
        if (vector.length != dimensions) {
            throw new IllegalArgumentException(
                "vector length " + vector.length + " != dimensions " + dimensions);
        }

        long hash = 0;
        for (int i = 0; i < numBits; i++) {
            if (dotProduct(vector, hyperplanes[i]) >= 0) {
                hash |= (1L << i);
            }
        }
        return hash;
    }

    /**
     * Computes the hash with a specific bit flipped.
     * Used for multi-probe LSH to generate nearby hash values.
     *
     * @param vector    The input vector
     * @param flipBit   The bit position to flip (0-indexed)
     * @return The hash with the specified bit flipped
     * @throws IllegalArgumentException if flipBit is out of range
     */
    public long hashWithFlip(float[] vector, int flipBit) {
        long baseHash = hash(vector);
        if (flipBit < 0 || flipBit >= numBits) {
            throw new IllegalArgumentException(
                "flipBit must be in range [0, " + (numBits - 1) + "], got: " + flipBit);
        }
        return baseHash ^ (1L << flipBit);
    }

    /**
     * Computes the dot product of two vectors.
     *
     * @param a First vector
     * @param b Second vector
     * @return The dot product
     */
    private float dotProduct(float[] a, float[] b) {
        float sum = 0.0f;
        for (int i = 0; i < dimensions; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /**
     * Computes the Hamming distance between two hash values.
     *
     * @param hash1 First hash
     * @param hash2 Second hash
     * @return The number of differing bits
     */
    public int hammingDistance(long hash1, long hash2) {
        return Long.bitCount(hash1 ^ hash2);
    }

    /**
     * Returns the number of bits in the hash.
     *
     * @return The number of bits
     */
    public int numBits() {
        return numBits;
    }

    /**
     * Returns the vector dimensionality.
     *
     * @return The dimensions
     */
    public int dimensions() {
        return dimensions;
    }

    /**
     * Returns a copy of the hyperplane at the specified index.
     * Useful for testing and debugging.
     *
     * @param index The hyperplane index (0 to numBits-1)
     * @return A copy of the hyperplane vector
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public float[] getHyperplane(int index) {
        if (index < 0 || index >= numBits) {
            throw new IndexOutOfBoundsException(
                "index must be in range [0, " + (numBits - 1) + "], got: " + index);
        }
        return hyperplanes[index].clone();
    }

    /**
     * Estimates the collision probability for two vectors at a given angle.
     * For random hyperplane LSH: P[collision] = 1 - θ/π
     *
     * @param angleRadians The angle between vectors in radians
     * @return The collision probability [0, 1]
     */
    public static double collisionProbability(double angleRadians) {
        return 1.0 - (angleRadians / Math.PI);
    }

    /**
     * Estimates the angle between vectors given their collision probability.
     * Inverse of collisionProbability.
     *
     * @param collisionProb The collision probability [0, 1]
     * @return The angle in radians [0, π]
     */
    public static double angleFromCollisionProbability(double collisionProb) {
        return Math.PI * (1.0 - collisionProb);
    }

    /**
     * Serializes this hash function to a byte array.
     * Format: [numBits(4)][dimensions(4)][hyperplanes(numBits*dimensions*4)]
     *
     * @return The serialized bytes
     */
    public byte[] toBytes() {
        int totalSize = 8 + (numBits * dimensions * 4);
        byte[] bytes = new byte[totalSize];

        // Write header
        bytes[0] = (byte) ((numBits >> 24) & 0xFF);
        bytes[1] = (byte) ((numBits >> 16) & 0xFF);
        bytes[2] = (byte) ((numBits >> 8) & 0xFF);
        bytes[3] = (byte) (numBits & 0xFF);

        bytes[4] = (byte) ((dimensions >> 24) & 0xFF);
        bytes[5] = (byte) ((dimensions >> 16) & 0xFF);
        bytes[6] = (byte) ((dimensions >> 8) & 0xFF);
        bytes[7] = (byte) (dimensions & 0xFF);

        // Write hyperplanes (little-endian floats)
        int offset = 8;
        for (int i = 0; i < numBits; i++) {
            for (int j = 0; j < dimensions; j++) {
                int bits = Float.floatToRawIntBits(hyperplanes[i][j]);
                bytes[offset++] = (byte) (bits & 0xFF);
                bytes[offset++] = (byte) ((bits >> 8) & 0xFF);
                bytes[offset++] = (byte) ((bits >> 16) & 0xFF);
                bytes[offset++] = (byte) ((bits >> 24) & 0xFF);
            }
        }

        return bytes;
    }

    /**
     * Creates a RandomHyperplaneHash from a serialized byte array.
     *
     * @param bytes The serialized bytes
     * @return A new RandomHyperplaneHash instance
     * @throws IllegalArgumentException if bytes are invalid
     */
    public static RandomHyperplaneHash fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            throw new IllegalArgumentException("bytes must have at least 8 bytes");
        }

        int numBits = ((bytes[0] & 0xFF) << 24) |
                      ((bytes[1] & 0xFF) << 16) |
                      ((bytes[2] & 0xFF) << 8) |
                      (bytes[3] & 0xFF);

        int dimensions = ((bytes[4] & 0xFF) << 24) |
                         ((bytes[5] & 0xFF) << 16) |
                         ((bytes[6] & 0xFF) << 8) |
                         (bytes[7] & 0xFF);

        int expectedSize = 8 + (numBits * dimensions * 4);
        if (bytes.length < expectedSize) {
            throw new IllegalArgumentException(
                "bytes length " + bytes.length + " < expected " + expectedSize);
        }

        // Create instance with dummy seed, then replace hyperplanes
        RandomHyperplaneHash hash = new RandomHyperplaneHash(dimensions, numBits, 0);

        // Read hyperplanes
        int offset = 8;
        for (int i = 0; i < numBits; i++) {
            for (int j = 0; j < dimensions; j++) {
                int bits = ((bytes[offset] & 0xFF)) |
                          ((bytes[offset + 1] & 0xFF) << 8) |
                          ((bytes[offset + 2] & 0xFF) << 16) |
                          ((bytes[offset + 3] & 0xFF) << 24);
                hash.hyperplanes[i][j] = Float.intBitsToFloat(bits);
                offset += 4;
            }
        }

        return hash;
    }

    @Override
    public String toString() {
        return String.format("RandomHyperplaneHash[dimensions=%d, numBits=%d]", dimensions, numBits);
    }
}
