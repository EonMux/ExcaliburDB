package excalibur.shard.stats;

import excalibur.filter.hyperloglog.HyperLogLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Tracks unique term counts using HyperLogLog for memory-efficient cardinality estimation.
 *
 * <p>UniqueTermCounter provides probabilistic counting of distinct terms within a segment,
 * enabling the query planner to understand data distribution and improve selectivity
 * estimates for query optimization.</p>
 *
 * <h2>Use Case in ExcaliburDB</h2>
 * <ul>
 *   <li>Estimating unique term counts per segment for selectivity estimation</li>
 *   <li>Aggregating unique counts across segments for corpus-wide statistics</li>
 *   <li>Exposing unique term metrics via the /v1/stats API</li>
 * </ul>
 *
 * <h2>Error Bounds</h2>
 * <p>Standard error depends on precision (configured via excalibur.shard.hyperloglog-precision):</p>
 * <ul>
 *   <li>precision=10 (1024 registers): ~3.25% error</li>
 *   <li>precision=14 (16384 registers, default): ~1.625% error</li>
 *   <li>precision=16 (65536 registers): ~0.81% error</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is fully thread-safe. All operations delegate to the thread-safe
 * {@link HyperLogLog} implementation.</p>
 *
 * <h2>Memory Layout</h2>
 * <p>Serialization format (compatible with HyperLogLog.toMemorySegment):</p>
 * <pre>
 * [magic(4B)|version(4B)|seed(8B)|precision(4B)|reserved(4B)|registers...]
 * </pre>
 *
 * @author ExcaliburDB
 * @since 5.0
 */
public class UniqueTermCounter {

    private static final Logger LOGGER = LoggerFactory.getLogger(UniqueTermCounter.class);

    /** Default precision for HyperLogLog (14 bits = 16384 registers, ~1.625% error). */
    public static final int DEFAULT_PRECISION = 14;

    /** Minimum precision (4 bits = 16 registers, ~26% error). */
    public static final int MIN_PRECISION = HyperLogLog.MIN_PRECISION;

    /** Maximum precision (16 bits = 65536 registers, ~0.81% error). */
    public static final int MAX_PRECISION = HyperLogLog.MAX_PRECISION;

    /** The underlying HyperLogLog sketch. */
    private final HyperLogLog hll;

    /** Lock for merge operations to ensure atomicity. */
    private final Object mergeLock = new Object();

    /**
     * Creates a new UniqueTermCounter with default precision (14).
     * This provides ~1.625% standard error with 16384 registers.
     */
    public UniqueTermCounter() {
        this(DEFAULT_PRECISION);
    }

    /**
     * Creates a new UniqueTermCounter with specified precision.
     *
     * @param precision Number of bits for register addressing (4-16)
     * @throws IllegalArgumentException if precision is not in [4, 16]
     */
    public UniqueTermCounter(int precision) {
        this.hll = new HyperLogLog(precision);
        LOGGER.debug("Created UniqueTermCounter with precision={} ({} registers, ~{:.2f}% error)",
            precision, hll.numRegisters(), HyperLogLog.standardError(precision));
    }

    /**
     * Creates a new UniqueTermCounter from an existing HyperLogLog instance.
     *
     * <p>This constructor is useful when deserializing or when a HyperLogLog
     * has already been created elsewhere.</p>
     *
     * @param hll The HyperLogLog instance (must not be null)
     * @throws NullPointerException if hll is null
     */
    public UniqueTermCounter(HyperLogLog hll) {
        this.hll = Objects.requireNonNull(hll, "HyperLogLog cannot be null");
    }

    /**
     * Adds a term to the counter.
     *
     * <p>The term is converted to UTF-8 bytes and hashed using xxHash64
     * before being added to the HyperLogLog sketch.</p>
     *
     * @param term The term to add (must not be null)
     * @throws NullPointerException if term is null
     */
    public void addTerm(String term) {
        Objects.requireNonNull(term, "Term cannot be null");
        if (term.isEmpty()) {
            return; // Skip empty terms
        }
        byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
        hll.insert(termBytes);
    }

    /**
     * Adds a term (as bytes) to the counter.
     *
     * <p>This method is useful when terms are already in byte form,
     * avoiding redundant UTF-8 encoding.</p>
     *
     * @param termBytes The term bytes to add (must not be null)
     * @throws NullPointerException if termBytes is null
     */
    public void addTermBytes(byte[] termBytes) {
        Objects.requireNonNull(termBytes, "Term bytes cannot be null");
        if (termBytes.length == 0) {
            return; // Skip empty terms
        }
        hll.insert(termBytes);
    }

    /**
     * Estimates the number of unique terms.
     *
     * <p>This is a probabilistic estimate with error bounds determined
     * by the precision setting. Higher precision = lower error but more memory.</p>
     *
     * @return Estimated number of unique terms (always non-negative)
     */
    public long estimateUniqueTerms() {
        return hll.estimate();
    }

    /**
     * Returns the precision setting.
     *
     * @return The precision (number of bits for register addressing)
     */
    public int precision() {
        return hll.precision();
    }

    /**
     * Returns the number of registers in the HyperLogLog.
     *
     * @return Number of registers (2^precision)
     */
    public int numRegisters() {
        return hll.numRegisters();
    }

    /**
     * Returns the approximate standard error as a percentage.
     *
     * @return Standard error percentage
     */
    public double standardError() {
        return HyperLogLog.standardError(hll.precision());
    }

    /**
     * Merges another UniqueTermCounter into this one.
     *
     * <p>This is useful for aggregating unique term counts across segments.
     * The merged estimate represents the union of both term sets.</p>
     *
     * <p><strong>Note:</strong> Both counters must have the same precision.</p>
     *
     * @param other The counter to merge into this one
     * @throws IllegalArgumentException if precisions don't match
     * @throws NullPointerException if other is null
     */
    public void merge(UniqueTermCounter other) {
        Objects.requireNonNull(other, "Other counter cannot be null");
        synchronized (mergeLock) {
            hll.merge(other.hll);
        }
    }

    /**
     * Clears all terms, resetting the counter to empty state.
     */
    public void clear() {
        hll.clear();
    }

    /**
     * Estimates memory usage in bytes.
     *
     * @return Estimated memory usage
     */
    public long estimatedMemoryUsage() {
        return hll.estimatedMemoryUsage();
    }

    /**
     * Serializes this counter to a byte array.
     *
     * <p>The serialized format is compatible with HyperLogLog's memory layout
     * and can be deserialized using {@link #deserialize(byte[])}.</p>
     *
     * @return Byte array containing serialized data
     */
    public byte[] serialize() {
        MemorySegment segment = hll.toMemorySegment(Arena.ofAuto());
        byte[] bytes = new byte[(int) segment.byteSize()];
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, 0, bytes, 0, bytes.length);
        return bytes;
    }

    /**
     * Deserializes a UniqueTermCounter from a byte array.
     *
     * <p>The bytes are copied into a native segment first. A {@code byte[]}-backed
     * {@link MemorySegment} supports 1-byte alignment only, while the HyperLogLog header
     * is read with the aligned {@code JAVA_INT} and {@code JAVA_LONG} layouts, so decoding
     * straight out of the array throws {@code IllegalArgumentException: Misaligned
     * access}.</p>
     *
     * @param bytes The byte array to deserialize from
     * @return A new UniqueTermCounter instance
     * @throws IllegalArgumentException if the bytes are invalid
     * @throws NullPointerException if bytes is null
     */
    public static UniqueTermCounter deserialize(byte[] bytes) {
        Objects.requireNonNull(bytes, "Bytes cannot be null");

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.length);
            HyperLogLog hll = HyperLogLog.fromMemorySegment(segment);
            return new UniqueTermCounter(hll);
        }
    }

    /**
     * Returns the underlying HyperLogLog instance.
     *
     * <p>This is useful for advanced operations or direct access
     * to the HyperLogLog API.</p>
     *
     * @return The HyperLogLog instance
     */
    public HyperLogLog getHyperLogLog() {
        return hll;
    }

    @Override
    public String toString() {
        return String.format("UniqueTermCounter[estimatedUnique=%d, precision=%d, error=%.2f%%]",
            estimateUniqueTerms(), precision(), standardError());
    }
}
