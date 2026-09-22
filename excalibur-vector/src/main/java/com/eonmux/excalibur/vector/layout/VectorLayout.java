package com.eonmux.excalibur.vector.layout;

/**
 * Configuration for vector memory layout optimized for JIT auto-vectorization.
 *
 * <p>This class defines the parameters for storing vectors in a format that
 * enables efficient SIMD operations and cache-friendly access patterns.</p>
 *
 * <h2>Layout Strategy: Struct of Arrays (SoA)</h2>
 * <p>Vectors are stored in transposed (columnar) format rather than the traditional
 * row-major format. This provides several benefits for batch distance computations:</p>
 *
 * <pre>
 * Traditional (AoS - Array of Structures):
 *   [v0_0, v0_1, v0_2, v1_0, v1_1, v1_2, v2_0, v2_1, v2_2, ...]
 *
 * Optimized (SoA - Structure of Arrays):
 *   [v0_0, v1_0, v2_0, ...] [v0_1, v1_1, v2_1, ...] [v0_2, v1_2, v2_2, ...]
 *    ^--- element 0 block ---^    ^--- element 1 block ---^    ^--- element 2 block ---^
 * </pre>
 *
 * <h2>Benefits of SoA Layout</h2>
 * <ul>
 *   <li><strong>Contiguous memory access:</strong> When computing distance from query
 *       to N vectors, accessing element 0 of all N vectors is a contiguous read</li>
 *   <li><strong>SIMD-friendly:</strong> Loading 8 element-0s into a vector register
 *       is a single aligned load</li>
 *   <li><strong>Prefetching:</strong> CPU prefetcher can predict access patterns</li>
 *   <li><strong>Cache efficiency:</strong> Cache lines contain useful data, not mixed elements</li>
 * </ul>
 *
 * <h2>Memory Layout</h2>
 * <pre>
 * +------------------+
 * | Header (64 bytes)|
 * +------------------+
 * | dimension (4B)   | offset 0
 * | vectorCount (4B) | offset 4
 * | flags (4B)       | offset 8
 * | reserved (52B)   | offset 12
 * +------------------+
 * | Element Block 0  | offset 64
 * | [v0_e0, v1_e0, v2_e0, ...] padded to 64-byte boundary
 * +------------------+
 * | Element Block 1  | offset 64 + elementBlockSize
 * | [v0_e1, v1_e1, v2_e1, ...]
 * +------------------+
 * | ...              |
 * +------------------+
 * | Element Block D-1|
 * +------------------+
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public final class VectorLayout {

    /**
     * Default cache line size in bytes (64 bytes on modern x86/ARM).
     */
    public static final int DEFAULT_CACHE_LINE_SIZE = 64;

    /**
     * Header size in bytes. Must be cache-line aligned.
     */
    public static final int HEADER_SIZE = 64;

    /**
     * Maximum supported vector dimension.
     */
    public static final int MAX_DIMENSION = 4096;

    /**
     * Maximum number of vectors in a single block.
     * Limited to ensure block size fits in an int.
     */
    public static final int MAX_VECTORS_PER_BLOCK = 1 << 24; // ~16M vectors

    /**
     * Size of a float in bytes.
     */
    public static final int FLOAT_SIZE = 4;

    // Header field offsets
    static final int OFFSET_DIMENSION = 0;
    static final int OFFSET_VECTOR_COUNT = 4;
    static final int OFFSET_FLAGS = 8;
    static final int OFFSET_RESERVED = 12;

    // Flag bits
    static final int FLAG_NORMALIZED = 1 << 0;
    static final int FLAG_TRANSPOSED = 1 << 1;

    private final int dimension;
    private final int vectorCount;
    private final int cacheLineSize;
    private final int vectorsPerBlock;
    private final long elementBlockSize;
    private final long totalBlockSize;
    private final boolean normalized;
    private final boolean transposed;

    /**
     * Creates a new VectorLayout with the specified parameters.
     *
     * @param dimension The dimension of each vector (must be positive, max 4096)
     * @param vectorCount The number of vectors in the block (must be positive, max 16M)
     * @param cacheLineSize The cache line size for alignment (must be power of 2, typically 64)
     * @param normalized Whether vectors are pre-normalized (affects distance computations)
     * @throws IllegalArgumentException if parameters are invalid
     */
    public VectorLayout(int dimension, int vectorCount, int cacheLineSize,
                        boolean normalized, boolean transposed) {
        if (dimension <= 0 || dimension > MAX_DIMENSION) {
            throw new IllegalArgumentException(
                "Dimension must be in range [1, " + MAX_DIMENSION + "], got: " + dimension);
        }
        if (vectorCount <= 0 || vectorCount > MAX_VECTORS_PER_BLOCK) {
            throw new IllegalArgumentException(
                "Vector count must be in range [1, " + MAX_VECTORS_PER_BLOCK + "], got: " + vectorCount);
        }
        if (cacheLineSize <= 0 || (cacheLineSize & (cacheLineSize - 1)) != 0) {
            throw new IllegalArgumentException(
                "Cache line size must be a power of 2, got: " + cacheLineSize);
        }

        this.dimension = dimension;
        this.vectorCount = vectorCount;
        this.cacheLineSize = cacheLineSize;
        this.normalized = normalized;
        this.transposed = transposed;
        this.vectorsPerBlock = vectorCount;

        // Calculate element block size: one float per vector, padded to cache line
        // Element block = vectorCount floats, padded to cache line boundary
        this.elementBlockSize = alignToCacheLine((long) vectorCount * FLOAT_SIZE, cacheLineSize);

        // Total block size = header + (dimension * elementBlockSize)
        this.totalBlockSize = HEADER_SIZE + (long) dimension * elementBlockSize;
    }

    /**
     * Creates a new VectorLayout with default cache line size (64 bytes).
     *
     * @param dimension The dimension of each vector
     * @param vectorCount The number of vectors in the block
     * @param normalized Whether vectors are pre-normalized
     * @return A new VectorLayout instance
     */
    public static VectorLayout create(int dimension, int vectorCount, boolean normalized) {
        return new VectorLayout(dimension, vectorCount, DEFAULT_CACHE_LINE_SIZE, normalized, true);
    }

    /**
     * Creates a new VectorLayout for non-normalized vectors with default settings.
     *
     * @param dimension The dimension of each vector
     * @param vectorCount The number of vectors in the block
     * @return A new VectorLayout instance
     */
    public static VectorLayout create(int dimension, int vectorCount) {
        return create(dimension, vectorCount, false);
    }

    /**
     * Returns the dimension of each vector.
     *
     * @return The vector dimension
     */
    public int dimension() {
        return dimension;
    }

    /**
     * Returns the number of vectors in the block.
     *
     * @return The vector count
     */
    public int vectorCount() {
        return vectorCount;
    }

    /**
     * Returns the cache line size used for alignment.
     *
     * @return The cache line size in bytes
     */
    public int cacheLineSize() {
        return cacheLineSize;
    }

    /**
     * Returns whether vectors are pre-normalized.
     *
     * @return true if vectors have unit length
     */
    public boolean isNormalized() {
        return normalized;
    }

    /**
     * Returns whether vectors are stored in transposed (SoA) format.
     *
     * @return true if transposed
     */
    public boolean isTransposed() {
        return transposed;
    }

    /**
     * Returns the size of a single element block in bytes.
     * Each element block contains one element from each vector.
     *
     * @return The element block size in bytes
     */
    public long elementBlockSize() {
        return elementBlockSize;
    }

    /**
     * Returns the total size of the vector block in bytes, including header.
     *
     * @return The total block size in bytes
     */
    public long totalBlockSize() {
        return totalBlockSize;
    }

    /**
     * Returns the byte offset to the start of an element block.
     *
     * @param elementIndex The element index (0 to dimension-1)
     * @return The byte offset
     * @throws IllegalArgumentException if elementIndex is out of range
     */
    public long elementBlockOffset(int elementIndex) {
        if (elementIndex < 0 || elementIndex >= dimension) {
            throw new IllegalArgumentException(
                "Element index out of range: " + elementIndex + " (dimension=" + dimension + ")");
        }
        return HEADER_SIZE + (long) elementIndex * elementBlockSize;
    }

    /**
     * Returns the byte offset to a specific element within a vector.
     *
     * @param vectorIndex The vector index (0 to vectorCount-1)
     * @param elementIndex The element index (0 to dimension-1)
     * @return The byte offset
     * @throws IllegalArgumentException if indices are out of range
     */
    public long elementOffset(int vectorIndex, int elementIndex) {
        if (vectorIndex < 0 || vectorIndex >= vectorCount) {
            throw new IllegalArgumentException(
                "Vector index out of range: " + vectorIndex + " (count=" + vectorCount + ")");
        }
        if (elementIndex < 0 || elementIndex >= dimension) {
            throw new IllegalArgumentException(
                "Element index out of range: " + elementIndex + " (dimension=" + dimension + ")");
        }

        if (transposed) {
            // SoA layout: element blocks come first
            return elementBlockOffset(elementIndex) + (long) vectorIndex * FLOAT_SIZE;
        } else {
            // AoS layout: vectors stored consecutively
            return HEADER_SIZE + (long) vectorIndex * dimension * FLOAT_SIZE + (long) elementIndex * FLOAT_SIZE;
        }
    }

    /**
     * Returns the number of vectors per block.
     *
     * @return The vectors per block
     */
    public int vectorsPerBlock() {
        return vectorsPerBlock;
    }

    /**
     * Calculates the required size for a vector block with the given parameters.
     *
     * @param dimension The vector dimension
     * @param vectorCount The number of vectors
     * @param cacheLineSize The cache line size
     * @return The required size in bytes
     */
    public static long calculateRequiredSize(int dimension, int vectorCount, int cacheLineSize) {
        long elementBlockSize = alignToCacheLine((long) vectorCount * FLOAT_SIZE, cacheLineSize);
        return HEADER_SIZE + (long) dimension * elementBlockSize;
    }

    /**
     * Aligns a size to the cache line boundary.
     *
     * @param size The size to align
     * @param cacheLineSize The cache line size
     * @return The aligned size
     */
    static long alignToCacheLine(long size, int cacheLineSize) {
        return (size + cacheLineSize - 1) & ~(cacheLineSize - 1);
    }

    /**
     * Returns the number of SIMD lanes that fit in a cache line for floats.
     *
     * @return The number of floats per cache line
     */
    public int floatsPerCacheLine() {
        return cacheLineSize / FLOAT_SIZE;
    }

    /**
     * Returns a new layout with a different vector count.
     * Useful for creating views over subsets of data.
     *
     * @param newVectorCount The new vector count
     * @return A new VectorLayout with the updated count
     */
    public VectorLayout withVectorCount(int newVectorCount) {
        return new VectorLayout(dimension, newVectorCount, cacheLineSize, normalized, transposed);
    }

    @Override
    public String toString() {
        return "VectorLayout{" +
            "dimension=" + dimension +
            ", vectorCount=" + vectorCount +
            ", cacheLineSize=" + cacheLineSize +
            ", normalized=" + normalized +
            ", transposed=" + transposed +
            ", totalBlockSize=" + totalBlockSize +
            '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VectorLayout that = (VectorLayout) o;
        return dimension == that.dimension &&
            vectorCount == that.vectorCount &&
            cacheLineSize == that.cacheLineSize &&
            normalized == that.normalized &&
            transposed == that.transposed;
    }

    @Override
    public int hashCode() {
        int result = dimension;
        result = 31 * result + vectorCount;
        result = 31 * result + cacheLineSize;
        result = 31 * result + (normalized ? 1 : 0);
        result = 31 * result + (transposed ? 1 : 0);
        return result;
    }
}
