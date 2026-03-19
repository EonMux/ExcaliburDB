package io.excalibur.vector.layout;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/**
 * A block of vectors stored in SoA (Structure of Arrays) format for optimal
 * JIT auto-vectorization during batch distance computations.
 *
 * <p>This class provides:</p>
 * <ul>
 *   <li>Cache-aligned storage for efficient SIMD loads</li>
 *   <li>Transposed (SoA) layout for contiguous memory access patterns</li>
 *   <li>Batch distance computation from a query vector to all stored vectors</li>
 *   <li>Support for both heap-backed and direct buffer-backed storage</li>
 * </ul>
 *
 * <h2>Memory Layout</h2>
 * <p>Vectors are stored in transposed format:</p>
 * <pre>
 * Block layout (SoA):
 * [Header 64B][Element 0 block][Element 1 block]...[Element D-1 block]
 *
 * Element block: [v0_e, v1_e, v2_e, ..., vN_e, padding...]
 * </pre>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li>Distance computation: O(N * D) where N = vector count, D = dimension</li>
 *   <li>Memory access pattern: Sequential reads within element blocks</li>
 *   <li>Cache efficiency: Each cache line contains useful data for the same element position</li>
 *   <li>SIMD utilization: 8-16 floats processed per vector instruction (AVX2/AVX-512)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe for read operations. Write operations (insert, update)
 * require external synchronization. Batch distance computations can be performed
 * concurrently.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class VectorBlock {

    /**
     * Preferred vector species for SIMD operations.
     */
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    /**
     * Minimum dimension threshold for vectorized distance computation.
     */
    private static final int VECTORIZATION_THRESHOLD = 8;

    private final VectorLayout layout;
    private final ByteBuffer buffer;
    private final boolean direct;
    private final int dimension;
    private final int vectorCount;
    private final long elementBlockSize;

    // Cached offsets for faster access
    private final long[] elementBlockOffsets;

    /**
     * Creates a new VectorBlock backed by a heap-allocated buffer.
     *
     * @param layout The layout configuration
     * @throws IllegalArgumentException if layout is null
     */
    public VectorBlock(VectorLayout layout) {
        this(layout, false);
    }

    /**
     * Creates a new VectorBlock with specified backing store type.
     *
     * @param layout The layout configuration
     * @param direct If true, use direct (native) buffer; otherwise use heap buffer
     * @throws IllegalArgumentException if layout is null
     */
    public VectorBlock(VectorLayout layout, boolean direct) {
        Objects.requireNonNull(layout, "Layout must not be null");

        this.layout = layout;
        this.dimension = layout.dimension();
        this.vectorCount = layout.vectorCount();
        this.elementBlockSize = layout.elementBlockSize();
        this.direct = direct;

        // Allocate buffer
        long size = layout.totalBlockSize();
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "Block size too large for ByteBuffer: " + size + " bytes");
        }

        if (direct) {
            this.buffer = ByteBuffer.allocateDirect((int) size)
                .order(ByteOrder.LITTLE_ENDIAN);
        } else {
            this.buffer = ByteBuffer.allocate((int) size)
                .order(ByteOrder.LITTLE_ENDIAN);
        }

        // Pre-compute element block offsets for faster access
        this.elementBlockOffsets = new long[dimension];
        for (int e = 0; e < dimension; e++) {
            elementBlockOffsets[e] = layout.elementBlockOffset(e);
        }

        // Write header
        writeHeader();
    }

    /**
     * Creates a VectorBlock wrapping an existing buffer.
     *
     * @param layout The layout configuration
     * @param buffer The backing buffer (position will be set to 0)
     * @throws IllegalArgumentException if buffer is too small
     */
    public VectorBlock(VectorLayout layout, ByteBuffer buffer) {
        Objects.requireNonNull(layout, "Layout must not be null");
        Objects.requireNonNull(buffer, "Buffer must not be null");

        if (buffer.capacity() < layout.totalBlockSize()) {
            throw new IllegalArgumentException(
                "Buffer too small: " + buffer.capacity() + " < " + layout.totalBlockSize());
        }

        this.layout = layout;
        this.dimension = layout.dimension();
        this.vectorCount = layout.vectorCount();
        this.elementBlockSize = layout.elementBlockSize();
        this.buffer = buffer.order(ByteOrder.LITTLE_ENDIAN);
        this.direct = buffer.isDirect();

        // Pre-compute element block offsets
        this.elementBlockOffsets = new long[dimension];
        for (int e = 0; e < dimension; e++) {
            elementBlockOffsets[e] = layout.elementBlockOffset(e);
        }
    }

    /**
     * Writes the header to the buffer.
     */
    private void writeHeader() {
        buffer.putInt(VectorLayout.OFFSET_DIMENSION, dimension);
        buffer.putInt(VectorLayout.OFFSET_VECTOR_COUNT, vectorCount);
        int flags = VectorLayout.FLAG_TRANSPOSED;
        if (layout.isNormalized()) {
            flags |= VectorLayout.FLAG_NORMALIZED;
        }
        buffer.putInt(VectorLayout.OFFSET_FLAGS, flags);
    }

    /**
     * Returns the layout configuration for this block.
     *
     * @return The layout
     */
    public VectorLayout layout() {
        return layout;
    }

    /**
     * Returns the dimension of vectors in this block.
     *
     * @return The dimension
     */
    public int dimension() {
        return dimension;
    }

    /**
     * Returns the number of vectors in this block.
     *
     * @return The vector count
     */
    public int vectorCount() {
        return vectorCount;
    }

    /**
     * Returns the underlying buffer.
     * Use with caution - direct access bypasses bounds checking.
     *
     * @return The backing buffer
     */
    public ByteBuffer buffer() {
        return buffer;
    }

    /**
     * Returns whether this block uses direct (native) memory.
     *
     * @return true if direct buffer
     */
    public boolean isDirect() {
        return direct;
    }

    /**
     * Returns the total size of this block in bytes.
     *
     * @return The size in bytes
     */
    public long size() {
        return layout.totalBlockSize();
    }

    // ==================== Vector Access ====================

    /**
     * Stores a vector at the specified index.
     *
     * @param index The vector index (0 to vectorCount-1)
     * @param vector The vector to store
     * @throws IllegalArgumentException if index or vector is invalid
     */
    public void putVector(int index, float[] vector) {
        checkVectorIndex(index);
        checkVectorDimension(vector);

        // Store each element in its respective element block (SoA layout)
        for (int e = 0; e < dimension; e++) {
            int offset = (int) elementBlockOffsets[e] + index * VectorLayout.FLOAT_SIZE;
            buffer.putFloat(offset, vector[e]);
        }
    }

    /**
     * Retrieves a vector from the specified index.
     *
     * @param index The vector index (0 to vectorCount-1)
     * @return A new float array containing the vector
     * @throws IllegalArgumentException if index is invalid
     */
    public float[] getVector(int index) {
        checkVectorIndex(index);

        float[] result = new float[dimension];
        for (int e = 0; e < dimension; e++) {
            int offset = (int) elementBlockOffsets[e] + index * VectorLayout.FLOAT_SIZE;
            result[e] = buffer.getFloat(offset);
        }
        return result;
    }

    /**
     * Copies a vector to the specified destination array.
     *
     * @param index The vector index
     * @param dest The destination array
     * @param destOffset The offset in the destination array
     */
    public void getVector(int index, float[] dest, int destOffset) {
        checkVectorIndex(index);
        if (dest == null || dest.length - destOffset < dimension) {
            throw new IllegalArgumentException("Destination array too small");
        }

        for (int e = 0; e < dimension; e++) {
            int offset = (int) elementBlockOffsets[e] + index * VectorLayout.FLOAT_SIZE;
            dest[destOffset + e] = buffer.getFloat(offset);
        }
    }

    /**
     * Sets a single element of a vector.
     *
     * @param vectorIndex The vector index
     * @param elementIndex The element index within the vector
     * @param value The value to set
     */
    public void putElement(int vectorIndex, int elementIndex, float value) {
        checkVectorIndex(vectorIndex);
        checkElementIndex(elementIndex);

        int offset = (int) elementBlockOffsets[elementIndex] + vectorIndex * VectorLayout.FLOAT_SIZE;
        buffer.putFloat(offset, value);
    }

    /**
     * Gets a single element of a vector.
     *
     * @param vectorIndex The vector index
     * @param elementIndex The element index within the vector
     * @return The element value
     */
    public float getElement(int vectorIndex, int elementIndex) {
        checkVectorIndex(vectorIndex);
        checkElementIndex(elementIndex);

        int offset = (int) elementBlockOffsets[elementIndex] + vectorIndex * VectorLayout.FLOAT_SIZE;
        return buffer.getFloat(offset);
    }

    // ==================== Batch Distance Computation ====================

    /**
     * Computes distances from a query vector to all vectors in this block.
     *
     * <p>Results are written to the provided array. This method uses
     * SIMD-optimized computation for large blocks.</p>
     *
     * @param query The query vector
     * @param results Array to receive distances (must have length >= vectorCount)
     * @param distanceType The type of distance to compute
     * @throws IllegalArgumentException if query or results array is invalid
     */
    public void computeDistances(float[] query, float[] results, DistanceType distanceType) {
        Objects.requireNonNull(query, "Query vector must not be null");
        Objects.requireNonNull(results, "Results array must not be null");
        Objects.requireNonNull(distanceType, "Distance type must not be null");

        if (query.length != dimension) {
            throw new IllegalArgumentException(
                "Query dimension mismatch: " + query.length + " vs " + dimension);
        }
        if (results.length < vectorCount) {
            throw new IllegalArgumentException(
                "Results array too small: " + results.length + " < " + vectorCount);
        }

        switch (distanceType) {
            case COSINE:
                computeCosineDistances(query, results);
                break;
            case L2:
                computeL2Distances(query, results);
                break;
            case INNER_PRODUCT:
                computeInnerProductDistances(query, results);
                break;
            default:
                throw new AssertionError("Unknown distance type: " + distanceType);
        }
    }

    /**
     * Computes L2 (Euclidean) distances from query to all vectors.
     * Uses SIMD vectorization for optimal performance.
     */
    private void computeL2Distances(float[] query, float[] results) {
        // For each vector, compute squared distance
        Arrays.fill(results, 0, vectorCount, 0.0f);

        // Accumulate squared differences element by element
        for (int e = 0; e < dimension; e++) {
            final float queryElement = query[e];
            final int baseOffset = (int) elementBlockOffsets[e];

            // Vectorized accumulation within element block
            if (vectorCount >= VECTORIZATION_THRESHOLD) {
                computeL2DistancesVectorized(queryElement, baseOffset, results);
            } else {
                // Scalar fallback for small blocks
                for (int v = 0; v < vectorCount; v++) {
                    float diff = buffer.getFloat(baseOffset + v * VectorLayout.FLOAT_SIZE) - queryElement;
                    results[v] += diff * diff;
                }
            }
        }

        // Take square root to get final L2 distance
        for (int v = 0; v < vectorCount; v++) {
            results[v] = (float) Math.sqrt(Math.max(0.0, results[v]));
        }
    }

    /**
     * Vectorized L2 distance accumulation for a single element.
     */
    private void computeL2DistancesVectorized(float queryElement, int baseOffset, float[] results) {
        final int laneCount = SPECIES.length();
        final int upperBound = SPECIES.loopBound(vectorCount);

        // Process full SIMD chunks
        for (int v = 0; v < upperBound; v += laneCount) {
            // Load database vector elements
            FloatVector dbVec = loadVector(baseOffset, v);

            // Broadcast query element
            FloatVector queryVec = FloatVector.broadcast(SPECIES, queryElement);

            // Compute squared difference
            FloatVector diff = dbVec.sub(queryVec);
            FloatVector squared = diff.mul(diff);

            // Accumulate into results (load-modify-store)
            FloatVector acc = FloatVector.fromArray(SPECIES, results, v);
            acc = acc.add(squared);
            acc.intoArray(results, v);
        }

        // Scalar tail
        for (int v = upperBound; v < vectorCount; v++) {
            float diff = buffer.getFloat(baseOffset + v * VectorLayout.FLOAT_SIZE) - queryElement;
            results[v] += diff * diff;
        }
    }

    /**
     * Computes cosine similarities from query to all vectors.
     * Uses SIMD vectorization for dot products and norms.
     */
    private void computeCosineDistances(float[] query, float[] results) {
        // Need to compute: dot(query, v) / (norm(query) * norm(v))
        float[] dotProducts = new float[vectorCount];
        float[] norms = new float[vectorCount];

        // Compute dot products and norms in single pass
        for (int e = 0; e < dimension; e++) {
            final float queryElement = query[e];
            final int baseOffset = (int) elementBlockOffsets[e];

            if (vectorCount >= VECTORIZATION_THRESHOLD) {
                // Vectorized path
                final int laneCount = SPECIES.length();
                final int upperBound = SPECIES.loopBound(vectorCount);

                FloatVector queryVec = FloatVector.broadcast(SPECIES, queryElement);

                for (int v = 0; v < upperBound; v += laneCount) {
                    FloatVector dbVec = loadVector(baseOffset, v);

                    // Dot product contribution
                    FloatVector dotContrib = dbVec.mul(queryVec);
                    FloatVector dotAcc = FloatVector.fromArray(SPECIES, dotProducts, v);
                    dotAcc = dotAcc.add(dotContrib);
                    dotAcc.intoArray(dotProducts, v);

                    // Norm contribution
                    FloatVector normContrib = dbVec.mul(dbVec);
                    FloatVector normAcc = FloatVector.fromArray(SPECIES, norms, v);
                    normAcc = normAcc.add(normContrib);
                    normAcc.intoArray(norms, v);
                }

                // Tail
                for (int v = upperBound; v < vectorCount; v++) {
                    float val = buffer.getFloat(baseOffset + v * VectorLayout.FLOAT_SIZE);
                    dotProducts[v] += val * queryElement;
                    norms[v] += val * val;
                }
            } else {
                // Scalar path
                for (int v = 0; v < vectorCount; v++) {
                    float val = buffer.getFloat(baseOffset + v * VectorLayout.FLOAT_SIZE);
                    dotProducts[v] += val * queryElement;
                    norms[v] += val * val;
                }
            }
        }

        // Compute query norm
        float queryNormSq = 0.0f;
        for (int e = 0; e < dimension; e++) {
            queryNormSq += query[e] * query[e];
        }
        float queryNorm = (float) Math.sqrt(queryNormSq);

        // Compute final cosine similarities
        for (int v = 0; v < vectorCount; v++) {
            float dbNorm = (float) Math.sqrt(norms[v]);
            if (queryNorm > 0 && dbNorm > 0) {
                float similarity = dotProducts[v] / (queryNorm * dbNorm);
                // Clamp to [-1, 1] to handle floating-point errors
                results[v] = Math.max(-1.0f, Math.min(1.0f, similarity));
            } else {
                results[v] = 0.0f;
            }
        }
    }

    /**
     * Computes inner products from query to all vectors.
     */
    private void computeInnerProductDistances(float[] query, float[] results) {
        Arrays.fill(results, 0, vectorCount, 0.0f);

        for (int e = 0; e < dimension; e++) {
            final float queryElement = query[e];
            final int baseOffset = (int) elementBlockOffsets[e];

            if (vectorCount >= VECTORIZATION_THRESHOLD) {
                computeInnerProductVectorized(queryElement, baseOffset, results);
            } else {
                for (int v = 0; v < vectorCount; v++) {
                    float val = buffer.getFloat(baseOffset + v * VectorLayout.FLOAT_SIZE);
                    results[v] += val * queryElement;
                }
            }
        }
    }

    /**
     * Vectorized inner product accumulation.
     */
    private void computeInnerProductVectorized(float queryElement, int baseOffset, float[] results) {
        final int laneCount = SPECIES.length();
        final int upperBound = SPECIES.loopBound(vectorCount);

        FloatVector queryVec = FloatVector.broadcast(SPECIES, queryElement);

        for (int v = 0; v < upperBound; v += laneCount) {
            FloatVector dbVec = loadVector(baseOffset, v);
            FloatVector contrib = dbVec.mul(queryVec);
            FloatVector acc = FloatVector.fromArray(SPECIES, results, v);
            acc = acc.add(contrib);
            acc.intoArray(results, v);
        }

        // Tail
        for (int v = upperBound; v < vectorCount; v++) {
            float val = buffer.getFloat(baseOffset + v * VectorLayout.FLOAT_SIZE);
            results[v] += val * queryElement;
        }
    }

    /**
     * Loads a FloatVector from the element block at the specified position.
     */
    private FloatVector loadVector(int baseOffset, int vectorIndex) {
        // For ByteBuffer-backed storage, we need to use array operations
        // Extract values into a temporary array and load from there
        float[] temp = new float[SPECIES.length()];
        for (int i = 0; i < SPECIES.length() && vectorIndex + i < vectorCount; i++) {
            temp[i] = buffer.getFloat(baseOffset + (vectorIndex + i) * VectorLayout.FLOAT_SIZE);
        }
        return FloatVector.fromArray(SPECIES, temp, 0);
    }

    // ==================== Batch Operations ====================

    /**
     * Fills the block with vectors from an array.
     *
     * @param vectors Array of vectors in row-major format
     * @param offset Starting index in the array
     * @param count Number of vectors to copy
     */
    public void putVectors(float[][] vectors, int offset, int count) {
        if (vectors == null) {
            throw new IllegalArgumentException("Vectors array must not be null");
        }
        if (offset < 0 || count < 0 || offset + count > vectors.length) {
            throw new IllegalArgumentException("Invalid offset or count");
        }
        if (count > vectorCount) {
            throw new IllegalArgumentException("Count exceeds block capacity");
        }

        for (int i = 0; i < count; i++) {
            putVector(i, vectors[offset + i]);
        }
    }

    /**
     * Fills the block with vectors from a flattened array.
     *
     * @param flatVectors Flattened array of vectors [v0_0, v0_1, ..., v1_0, v1_1, ...]
     * @param offset Starting offset in the array
     * @param count Number of vectors to copy
     */
    public void putVectorsFlat(float[] flatVectors, int offset, int count) {
        if (flatVectors == null) {
            throw new IllegalArgumentException("Vectors array must not be null");
        }
        if (offset < 0 || count < 0) {
            throw new IllegalArgumentException("Invalid offset or count");
        }
        if ((long) offset + (long) count * dimension > flatVectors.length) {
            throw new IllegalArgumentException("Insufficient data in array");
        }
        if (count > vectorCount) {
            throw new IllegalArgumentException("Count exceeds block capacity");
        }

        // Transpose from AoS to SoA format
        for (int e = 0; e < dimension; e++) {
            int baseOffset = (int) elementBlockOffsets[e];
            for (int v = 0; v < count; v++) {
                float val = flatVectors[offset + v * dimension + e];
                buffer.putFloat(baseOffset + v * VectorLayout.FLOAT_SIZE, val);
            }
        }
    }

    /**
     * Copies all vectors to a flattened array.
     *
     * @param dest Destination array
     * @param offset Starting offset in destination
     */
    public void getVectorsFlat(float[] dest, int offset) {
        if (dest == null) {
            throw new IllegalArgumentException("Destination array must not be null");
        }
        if ((long) offset + (long) vectorCount * dimension > dest.length) {
            throw new IllegalArgumentException("Destination array too small");
        }

        // Transpose from SoA to AoS format
        for (int v = 0; v < vectorCount; v++) {
            for (int e = 0; e < dimension; e++) {
                int srcOffset = (int) elementBlockOffsets[e] + v * VectorLayout.FLOAT_SIZE;
                dest[offset + v * dimension + e] = buffer.getFloat(srcOffset);
            }
        }
    }

    // ==================== Utility Methods ====================

    private void checkVectorIndex(int index) {
        if (index < 0 || index >= vectorCount) {
            throw new IllegalArgumentException(
                "Vector index out of range: " + index + " (count=" + vectorCount + ")");
        }
    }

    private void checkElementIndex(int index) {
        if (index < 0 || index >= dimension) {
            throw new IllegalArgumentException(
                "Element index out of range: " + index + " (dimension=" + dimension + ")");
        }
    }

    private void checkVectorDimension(float[] vector) {
        if (vector == null || vector.length != dimension) {
            throw new IllegalArgumentException(
                "Vector must have dimension " + dimension + ", got: " +
                (vector == null ? "null" : vector.length));
        }
    }

    /**
     * Clears all vectors in this block (sets all values to zero).
     */
    public void clear() {
        for (int e = 0; e < dimension; e++) {
            int baseOffset = (int) elementBlockOffsets[e];
            for (int v = 0; v < vectorCount; v++) {
                buffer.putFloat(baseOffset + v * VectorLayout.FLOAT_SIZE, 0.0f);
            }
        }
    }

    @Override
    public String toString() {
        return "VectorBlock{" +
            "dimension=" + dimension +
            ", vectorCount=" + vectorCount +
            ", size=" + size() +
            ", direct=" + direct +
            '}';
    }
}
