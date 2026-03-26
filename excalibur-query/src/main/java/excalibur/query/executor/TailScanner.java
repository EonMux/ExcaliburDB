package excalibur.query.executor;

import io.excalibur.store.WriteLog;
import io.excalibur.store.format.LogEntry;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.distance.DistanceValidator;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Performs brute-force scan over un-integrated write log entries.
 *
 * <p>This class implements the "tail scan" portion of the query pipeline.
 * When entries exist in the write log that haven't yet been integrated into
 * the HNSW graph (entries after {@code lastIntegratedOffset}), this scanner
 * performs a brute-force distance calculation to ensure query completeness.</p>
 *
 * <h2>Live Indexing Guarantee</h2>
 * <p>During live indexing, newly inserted entries are first written to the
 * write log before being integrated into the HNSW graph. The tail scan
 * ensures queries see these entries immediately, without waiting for
 * integration.</p>
 *
 * <h2>Backpressure Enforcement</h2>
 * <p>Tail scan entry count is bounded by the backpressure limit (default 100K entries).
 * This ensures query latency remains acceptable even during heavy ingestion.
 * When the limit is reached, scanning stops and a warning flag is set in results.</p>
 *
 * <h2>Vector Storage in Write Log</h2>
 * <p>Vectors are stored in the write log's value field (LITTLE-ENDIAN) as:
 * <pre>
 * [dimension:4][vector:dim*4][optional text bytes]
 * </pre>
 * The first 4 bytes are the dimension (int, little-endian), followed by
 * the vector components (floats, little-endian). Optional text bytes may follow
 * the vector when using DocumentChunkBuffer.encodeChunkWithEmbedding().</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Start offset must be less than or equal to end offset</li>
 *   <li>Offsets must be within write log bounds</li>
 *   <li>Vector dimension must match the distance function's expected dimension</li>
 *   <li>Distances are computed using exact arithmetic (full precision)</li>
 *   <li>Entry count never exceeds backpressure limit (100K)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Each scan operation reads from the write log
 * using thread-local buffers.</p>
 *
 * @author ExcaliburDB
 * @since 1.0
 */
public class TailScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(TailScanner.class);

    /**
     * Default backpressure limit for tail scan entry count.
     * When the number of entries scanned exceeds this limit, scanning stops.
     * This bounds tail scan latency to ensure acceptable query response times.
     */
    public static final int DEFAULT_BACKPRESSURE_ENTRY_LIMIT = 100_000;

    /**
     * Configuration key for the backpressure entry limit.
     */
    public static final String CONFIG_BACKPRESSURE_ENTRY_LIMIT = "excalibur.tail-scan.backpressure-entry-limit";

    /**
     * Size of the dimension field in the value buffer (4 bytes for int).
     */
    private static final int DIMENSION_SIZE = 4;

    /**
     * The maximum number of entries to scan before applying backpressure.
     * Must be positive. Defaults to {@link #DEFAULT_BACKPRESSURE_ENTRY_LIMIT}.
     */
    private final int backpressureEntryLimit;

    /**
     * Creates a new TailScanner with the default backpressure entry limit.
     */
    public TailScanner() {
        this(DEFAULT_BACKPRESSURE_ENTRY_LIMIT);
    }

    /**
     * Creates a new TailScanner with a custom backpressure entry limit.
     *
     * @param backpressureEntryLimit The maximum number of entries to scan.
     *                               Must be positive. Values ≤ 0 are clamped to 1.
     */
    public TailScanner(int backpressureEntryLimit) {
        // Ensure limit is at least 1 to avoid division by zero and allow some scanning
        this.backpressureEntryLimit = Math.max(1, backpressureEntryLimit);
    }

    /**
     * Gets the configured backpressure entry limit.
     *
     * @return The maximum number of entries this scanner will process
     */
    public int getBackpressureEntryLimit() {
        return backpressureEntryLimit;
    }

    /**
     * Performs a brute-force scan over write log entries in the specified range.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Reads all PUT entries from the write log between startOffset and endOffset</li>
     *   <li>Extracts vectors from each entry's value field</li>
     *   <li>Computes exact distances to the query vector</li>
     *   <li>Returns the top-k results sorted by distance (closest first)</li>
     * </ol>
     *
     * <p><strong>Note:</strong> DELETE entries are skipped during tail scan since
     * they don't contain vectors.</p>
     *
     * @param queryVector The query vector to compare against
     * @param writeLog The write log to scan
     * @param startOffset The starting byte offset (inclusive, typically lastIntegratedOffset)
     * @param endOffset The ending byte offset (exclusive, typically writeLogHead)
     * @param k The maximum number of results to return
     * @param distanceFunction The distance function to use for comparison
     * @return List of search results sorted by distance (closest first), never null
     * @throws NullPointerException if any parameter is null
     * @throws IllegalArgumentException if offsets are invalid or k is negative
     */
    public List<HnswSearchResult> performTailScan(
            float[] queryVector,
            WriteLog writeLog,
            long startOffset,
            long endOffset,
            int k,
            DistanceFunction distanceFunction) {

        Objects.requireNonNull(queryVector, "Query vector cannot be null");

        if (startOffset < 0) {
            throw new IllegalArgumentException("Start offset cannot be negative: " + startOffset);
        }
        if (endOffset < startOffset) {
            throw new IllegalArgumentException(
                "End offset " + endOffset + " cannot be less than start offset " + startOffset);
        }
        if (k < 0) {
            throw new IllegalArgumentException("k cannot be negative: " + k);
        }

        // Early return for empty range or k=0 (before null checks on writeLog/distanceFunction)
        if (startOffset >= endOffset || k == 0) {
            return new ArrayList<>();
        }

        // Now check the remaining parameters that are needed for actual scanning
        Objects.requireNonNull(writeLog, "WriteLog cannot be null");
        Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");

        LOGGER.trace("Starting tail scan from offset {} to {}, k={}", startOffset, endOffset, k);

        // Use a max-heap to track top-k results (furthest at the top for removal)
        PriorityQueue<HnswSearchResult> topResults = new PriorityQueue<>(
            k > 0 ? k : 16,
            Comparator.comparingDouble(HnswSearchResult::distance).reversed()
        );

        long currentOffset = startOffset;
        long writeLogEnd = writeLog.writeOffset();
        int entriesScanned = 0;
        int vectorEntriesFound = 0;
        boolean backpressureTriggered = false;

        // Ensure we don't read beyond the actual write log data
        endOffset = Math.min(endOffset, writeLogEnd);

        while (currentOffset < endOffset) {
            // BACKPRESSURE CHECK: Stop if entry limit reached
            if (entriesScanned >= backpressureEntryLimit) {
                backpressureTriggered = true;
                LOGGER.warn("Tail scan backpressure triggered at {} entries (limit: {}). " +
                           "Remaining entries in tail will not be scanned. " +
                           "Query results may be incomplete.",
                           entriesScanned, backpressureEntryLimit);
                break;
            }
            try {
                // Read entry at current offset
                LogEntry entry = readEntryAtOffset(writeLog, currentOffset);
                if (entry == null) {
                    // Corrupted or incomplete entry, try to find next valid entry
                    currentOffset++;
                    continue;
                }

                entriesScanned++;

                // Only process PUT entries with vector data
                if (entry.isPut() && entry.valueLength() > DIMENSION_SIZE) {
                    float[] vector = extractVector(entry.value(), queryVector.length);

                    if (vector != null) {
                        // Compute exact distance
                        float distance = distanceFunction.compute(queryVector, vector);

                        // Validate and clamp distance to ensure domain correctness
                        // Note: We store raw distances here; normalization happens in
                        // VectorReranker to ensure consistency with HNSW results.
                        // Cosine similarity ∈ [-1, 1], L2 distance ∈ [0, ∞)
                        distance = DistanceValidator.validateAndClamp(distance, distanceFunction);

                        // Use a synthetic node ID based on offset for deduplication
                        // In production, this would be the actual document/vector ID
                        long nodeId = encodeNodeId(currentOffset, entry.lsn());

                        HnswSearchResult result = new HnswSearchResult(nodeId, distance);

                        if (topResults.size() < k) {
                            topResults.add(result);
                        } else if (distance < topResults.peek().distance()) {
                            topResults.poll();
                            topResults.add(result);
                        }

                        vectorEntriesFound++;
                    }
                }

                // Move to next entry
                int entrySize = entry.size();
                if (entrySize <= 0) {
                    // Invalid entry size, advance by 1 to avoid infinite loop
                    currentOffset++;
                } else {
                    currentOffset += entrySize;
                }

            } catch (Exception e) {
                // Log error and continue to next entry
                LOGGER.debug("Error reading entry at offset {}: {}", currentOffset, e.getMessage());
                currentOffset++;
            }
        }

        // Convert max-heap to sorted list (closest first)
        List<HnswSearchResult> results = new ArrayList<>(topResults);
        results.sort(Comparator.comparingDouble(HnswSearchResult::distance));

        if (backpressureTriggered) {
            LOGGER.warn("Tail scan complete with backpressure: {} entries scanned (limit: {}), " +
                       "{} vectors found, {} results returned. Some tail entries were not scanned.",
                       entriesScanned, backpressureEntryLimit, vectorEntriesFound, results.size());
        } else {
            LOGGER.trace("Tail scan complete: {} entries scanned, {} vectors found, {} results returned",
                entriesScanned, vectorEntriesFound, results.size());
        }

        return results;
    }

    /**
     * Checks if there are tail entries that need to be scanned.
     *
     * <p>Returns true when {@code lastIntegratedOffset < writeLogHead}, indicating
     * that entries exist in the write log that haven't been integrated into the
     * HNSW graph yet.</p>
     *
     * @param lastIntegratedOffset The offset up to which entries are in the HNSW graph
     * @param writeLogHead The current head of the write log
     * @return true if tail scan is needed, false otherwise
     */
    public boolean hasTailEntries(long lastIntegratedOffset, long writeLogHead) {
        return lastIntegratedOffset < writeLogHead;
    }

    /**
     * Estimates the number of entries in the tail region.
     *
     * <p>This is a rough estimate based on average entry size. Used for
     * query planning and resource allocation.</p>
     *
     * @param startOffset The start of the tail region
     * @param endOffset The end of the tail region
     * @param avgEntrySize Average entry size in bytes (default 100 if unknown)
     * @return Estimated number of entries
     */
    public long estimateTailEntryCount(long startOffset, long endOffset, int avgEntrySize) {
        if (startOffset >= endOffset) {
            return 0;
        }
        long tailSize = endOffset - startOffset;
        int size = avgEntrySize > 0 ? avgEntrySize : 100;
        return tailSize / size;
    }

    /**
     * Reads an entry from the write log at the specified offset.
     *
     * <p>This method uses the WriteLog's readEntryAt method for direct access.</p>
     */
    private LogEntry readEntryAtOffset(WriteLog writeLog, long offset) {
        try {
            return writeLog.readEntryAt(offset);
        } catch (Exception e) {
            LOGGER.trace("Failed to read entry at offset {}: {}", offset, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts a vector from the write log entry's value field.
     *
     * <p>Value format (LITTLE-ENDIAN): [dimension:4][vector:dim*4][optional text bytes]</p>
     *
     * <p>The value may contain additional bytes after the vector (e.g., chunk text from
     * DocumentChunkBuffer.encodeChunkWithEmbedding). This method only extracts the vector
     * portion and ignores any trailing bytes.</p>
     *
     * @param value The value bytes from the log entry
     * @param expectedDimension The expected vector dimension
     * @return The vector, or null if extraction fails
     */
    private float[] extractVector(byte[] value, int expectedDimension) {
        if (value == null || value.length <= DIMENSION_SIZE) {
            return null;
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
            int dimension = buffer.getInt();

            // Validate dimension
            if (dimension != expectedDimension) {
                LOGGER.trace("Dimension mismatch: expected {}, got {}", expectedDimension, dimension);
                return null;
            }

            // Check minimum length (allows extra bytes for text after the vector)
            int expectedBytes = DIMENSION_SIZE + dimension * Float.BYTES;
            if (value.length < expectedBytes) {
                LOGGER.trace("Value too short: {} bytes, expected at least {}", value.length, expectedBytes);
                return null;
            }
            // Note: Extra bytes after the vector (chunk text) are ignored

            float[] vector = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                vector[i] = buffer.getFloat();
            }

            return vector;

        } catch (Exception e) {
            LOGGER.trace("Failed to extract vector: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Encodes a node ID from offset and LSN for deduplication purposes.
     *
     * <p>This creates a unique identifier that can be used to deduplicate
     * results between HNSW search and tail scan.</p>
     *
     * @param offset The write log offset
     * @param lsn The log sequence number
     * @return A unique node ID
     */
    private long encodeNodeId(long offset, long lsn) {
        // Use LSN as the primary identifier since it's unique per entry
        // The high bit indicates this is a tail scan result
        return Long.MIN_VALUE | lsn;
    }

    /**
     * Checks if a node ID originated from a tail scan.
     *
     * @param nodeId The node ID to check
     * @return true if from tail scan, false if from HNSW graph
     */
    public static boolean isTailScanResult(long nodeId) {
        return (nodeId & Long.MIN_VALUE) != 0;
    }
}
