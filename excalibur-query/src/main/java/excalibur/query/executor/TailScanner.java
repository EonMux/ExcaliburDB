package excalibur.query.executor;

import excalibur.query.payload.ChunkValueCodec;
import io.excalibur.core.codec.ChunkPayloadCodec;
import io.excalibur.metrics.Metrics;
import io.excalibur.store.WriteLog;
import io.excalibur.store.format.LogEntry;
import io.excalibur.vector.distance.DistanceFunction;
import io.excalibur.vector.distance.DistanceValidator;
import io.excalibur.vector.hnsw.HnswSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * the vector components (floats, little-endian). The chunk text follows the vector, and is
 * what a tail-scan hit carries back as its payload. Both halves are written by
 * {@code io.excalibur.core.codec.ChunkPayloadCodec}, which is the only encoder of this
 * layout; this scanner is the only decoder of it outside that class.</p>
 *
 * <h2>Result Identity</h2>
 * <p>Each result carries a synthetic node ID (tagged with the high bit so
 * {@link #isTailScanResult(long)} can recognise it) plus the document ID decoded from
 * the write-log entry key. Keys are written by the ingestion pipeline as
 * {@code sourceId:documentId:chunk:index}, and the HNSW integration worker registers
 * {@code sourceId:documentId} as the node's document ID, so a tail-scan hit and the
 * graph node it later becomes report the same document identity.</p>
 *
 * <h2>Chunk Text</h2>
 * <p>The entry's value is already in hand while it is being scanned, so the chunk text
 * trailing the embedding is decoded here and returned alongside the results
 * ({@link TailScanOutcome#payloads()}). Relevancy scoring therefore never has to read a
 * tail hit back out of storage - and could not, because the synthetic node ID carries no
 * chunk index.</p>
 *
 * <h2>Corruption Handling</h2>
 * <p>An entry that cannot be parsed is resynchronised by advancing one byte at a time,
 * bounded by {@link #MAX_RESYNC_BYTES}. Beyond that budget the offsets can no longer be
 * trusted and the scan aborts with a warning rather than walking the whole tail a byte
 * at a time.</p>
 *
 * <h2>Cancellation</h2>
 * <p>The scan loop checks the scanning thread's interrupt status each iteration and
 * returns the results gathered so far, so a cancelled or timed-out query stops
 * consuming CPU instead of running the scan to completion.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Start offset must be less than or equal to end offset</li>
 *   <li>Offsets must be within write log bounds</li>
 *   <li>Vector dimension must match the distance function's expected dimension</li>
 *   <li>Distances are the exact {@link DistanceFunction#distance(float[], float[])}
 *       dissimilarity (smaller is closer) for every metric</li>
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
     * Backpressure limit for tail scan entry count.
     * When the number of entries scanned exceeds this limit, scanning stops.
     * This bounds tail scan latency to ensure acceptable query response times.
     *
     * <p>This is the limit every query uses: {@code SegmentQueryTask} constructs the scanner
     * with {@link #TailScanner()}, so only a caller that supplies its own value through
     * {@link #TailScanner(int)} gets anything else. There is deliberately no configuration key
     * for it any more - one was declared here and read by nothing, which meant setting it in
     * {@code excalibur.properties} changed the limit a query actually applied not at all.</p>
     */
    public static final int DEFAULT_BACKPRESSURE_ENTRY_LIMIT = 100_000;

    /**
     * Maximum number of bytes the scan may skip while resynchronising after an entry
     * that could not be parsed.
     *
     * <p>A truncated entry at the very end of the tail is normal (a writer may be
     * mid-append), and skipping over one damaged record is worth attempting. Past this
     * budget the offset stream is no longer trustworthy, so the scan aborts instead of
     * advancing one byte at a time through the remainder of the tail.</p>
     */
    public static final int MAX_RESYNC_BYTES = 64 * 1024;

    /**
     * Marker separating the document ID from the chunk index in a write-log entry key.
     */
    private static final String CHUNK_MARKER = ":chunk:";

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
     * <p>Equivalent to {@link #scan}, returning only the top-k results. Prefer
     * {@link #scan} when the caller needs the number of entries actually scanned
     * (for coverage reporting) rather than the number of results kept.</p>
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

        return scan(queryVector, writeLog, startOffset, endOffset, k, distanceFunction).results();
    }

    /**
     * Performs a brute-force scan over write log entries in the specified range.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Reads all PUT entries from the write log between startOffset and endOffset</li>
     *   <li>Extracts vectors from each entry's value field</li>
     *   <li>Computes exact dissimilarities to the query vector</li>
     *   <li>Returns the top-k results sorted by dissimilarity (closest first)</li>
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
     * @return The scan outcome: top-k results plus the work actually performed
     * @throws NullPointerException if any parameter is null
     * @throws IllegalArgumentException if offsets are invalid or k is negative
     */
    public TailScanOutcome scan(
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
            return TailScanOutcome.empty();
        }

        // Now check the remaining parameters that are needed for actual scanning
        Objects.requireNonNull(writeLog, "WriteLog cannot be null");
        Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");

        LOGGER.trace("Starting tail scan from offset {} to {}, k={}", startOffset, endOffset, k);

        // Use a max-heap to track top-k results (furthest at the top for removal)
        PriorityQueue<HnswSearchResult> topResults = new PriorityQueue<>(
            Math.min(k, 1024),
            Comparator.comparingDouble(HnswSearchResult::distance).reversed()
        );

        ScanState state = new ScanState();
        long currentOffset = startOffset;
        long writeLogEnd = writeLog.writeOffset();

        // Ensure we don't read beyond the actual write log data
        long scanEnd = Math.min(endOffset, writeLogEnd);

        while (currentOffset < scanEnd) {
            // CANCELLATION: a cancelled or timed-out query interrupts this thread.
            // Stop and hand back what has been found so far.
            if (Thread.currentThread().isInterrupted()) {
                state.cancelled = true;
                LOGGER.debug("Tail scan interrupted after {} entries; returning partial results",
                    state.entriesScanned);
                break;
            }

            // BACKPRESSURE CHECK: Stop if entry limit reached
            if (state.entriesScanned >= backpressureEntryLimit) {
                state.backpressureTriggered = true;
                LOGGER.warn("Tail scan backpressure triggered at {} entries (limit: {}). " +
                           "Remaining entries in tail will not be scanned. " +
                           "Query results may be incomplete.",
                           state.entriesScanned, backpressureEntryLimit);
                break;
            }

            long nextOffset = scanOne(
                writeLog, currentOffset, queryVector, distanceFunction, k, topResults, state);

            if (nextOffset < 0) {
                break;
            }
            currentOffset = nextOffset;
        }

        // Record tail scan metrics for JMX observability
        // This tracks: total scans performed, total entries scanned, max entries per scan
        Metrics.recordTailScanMetrics(state.entriesScanned, state.vectorEntriesFound);

        // Convert max-heap to sorted list (closest first)
        List<HnswSearchResult> results = new ArrayList<>(topResults);
        results.sort(Comparator.comparingDouble(HnswSearchResult::distance));

        if (state.backpressureTriggered) {
            LOGGER.warn("Tail scan complete with backpressure: {} entries scanned (limit: {}), " +
                       "{} vectors found, {} results returned. Some tail entries were not scanned.",
                       state.entriesScanned, backpressureEntryLimit, state.vectorEntriesFound, results.size());
        } else {
            LOGGER.trace("Tail scan complete: {} entries scanned, {} vectors found, {} results returned",
                state.entriesScanned, state.vectorEntriesFound, results.size());
        }

        return new TailScanOutcome(
            results, state.entriesScanned, state.backpressureTriggered, state.cancelled,
            state.payloads);
    }

    /**
     * Processes the entry at {@code offset}, adding it to {@code topResults} when it is
     * a PUT carrying a vector of the expected dimension.
     *
     * @return The offset to continue from, or -1 to abort the scan
     */
    private long scanOne(
            WriteLog writeLog,
            long offset,
            float[] queryVector,
            DistanceFunction distanceFunction,
            int k,
            PriorityQueue<HnswSearchResult> topResults,
            ScanState state) {

        LogEntry entry;
        try {
            entry = readEntryAtOffset(writeLog, offset);
        } catch (Exception e) {
            LOGGER.debug("Error reading entry at offset {}: {}", offset, e.getMessage());
            entry = null;
        }

        if (entry == null || entry.size() <= 0) {
            return resync(offset, state);
        }

        state.entriesScanned++;

        // Only process PUT entries with vector data
        if (entry.isPut() && entry.valueLength() > ChunkPayloadCodec.HEADER_BYTES) {
            float[] vector = extractVector(entry.value(), queryVector.length);

            if (vector != null) {
                // Exact dissimilarity: smaller is closer for every metric
                float distance = distanceFunction.distance(queryVector, vector);

                if (Float.isFinite(distance)) {
                    // Clamp on the metric's natural scale, then convert back to the
                    // ordered scale so the value stays comparable with HNSW results.
                    DistanceFunction.Ordering ordering = distanceFunction.ordering();
                    distance = ordering.toDistance(DistanceValidator.validateAndClamp(
                        ordering.fromDistance(distance), distanceFunction));

                    // Synthetic node ID (high bit tagged) plus the real document identity
                    long nodeId = encodeNodeId(offset, entry.lsn());
                    String documentId = extractDocumentId(entry.key());

                    HnswSearchResult result = new HnswSearchResult(nodeId, distance, documentId);

                    if (topResults.size() < k) {
                        topResults.add(result);
                        state.keepPayload(nodeId, chunkText(entry.value()));
                    } else if (distance < topResults.peek().distance()) {
                        state.dropPayload(topResults.poll().nodeId());
                        topResults.add(result);
                        state.keepPayload(nodeId, chunkText(entry.value()));
                    }

                    state.vectorEntriesFound++;
                } else {
                    LOGGER.debug("Skipping tail entry at offset {} with non-finite distance {}",
                        offset, distance);
                }
            }
        }

        return offset + entry.size();
    }

    /**
     * Advances one byte past an unparseable entry, charging the move against the
     * resynchronisation budget.
     *
     * @return The next offset to try, or -1 when the budget is exhausted
     */
    private long resync(long offset, ScanState state) {
        state.resyncBytes++;
        if (state.resyncBytes > MAX_RESYNC_BYTES) {
            LOGGER.warn("Tail scan aborted at offset {}: could not resynchronise within {} bytes. " +
                       "The write log is likely corrupted; {} entries were scanned before aborting.",
                       offset, MAX_RESYNC_BYTES, state.entriesScanned);
            return -1;
        }
        return offset + 1;
    }

    /**
     * Mutable counters for a single scan.
     */
    private static final class ScanState {
        final Map<Long, byte[]> payloads = new HashMap<>();
        int entriesScanned;
        int vectorEntriesFound;
        int resyncBytes;
        boolean backpressureTriggered;
        boolean cancelled;

        /**
         * Records the chunk text of a result that entered the top-k heap.
         */
        void keepPayload(long nodeId, byte[] text) {
            if (text != null && text.length > 0) {
                payloads.put(nodeId, text);
            }
        }

        /**
         * Drops the chunk text of a result evicted from the top-k heap.
         */
        void dropPayload(long nodeId) {
            payloads.remove(nodeId);
        }
    }

    /**
     * Outcome of a tail scan.
     *
     * @param results The top-k results, sorted closest first (never null)
     * @param entriesScanned The number of write-log entries actually read, which is the
     *                       work performed - not the number of results kept
     * @param backpressureTriggered True if the scan stopped at the backpressure limit
     * @param cancelled True if the scan stopped because its thread was interrupted
     * @param payloads The chunk text of each kept result, keyed by its synthetic node ID;
     *                 entries carrying no text are absent
     */
    public record TailScanOutcome(
        List<HnswSearchResult> results,
        int entriesScanned,
        boolean backpressureTriggered,
        boolean cancelled,
        Map<Long, byte[]> payloads
    ) {
        public TailScanOutcome {
            results = results == null ? List.of() : List.copyOf(results);
            payloads = payloads == null ? Map.of() : Map.copyOf(payloads);
            if (entriesScanned < 0) {
                throw new IllegalArgumentException("entriesScanned must be non-negative: " + entriesScanned);
            }
        }

        /**
         * Returns an outcome describing a scan that did no work.
         */
        static TailScanOutcome empty() {
            return new TailScanOutcome(List.of(), 0, false, false, Map.of());
        }

        /**
         * Returns the chunk text of one result, or null when it carried none.
         *
         * @param nodeId The synthetic node ID of a result in {@link #results()}
         * @return The UTF-8 text bytes, or null
         */
        public byte[] payload(long nodeId) {
            return payloads.get(nodeId);
        }

        /**
         * Returns true if the scan did not cover its whole requested range.
         */
        public boolean incomplete() {
            return backpressureTriggered || cancelled;
        }
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
     * <p>The layout, and the validation of the dimension header that comes before trusting it,
     * belong to {@link ChunkPayloadCodec}; this scanner used to spell both out again, which is
     * how it came to accept a header the other readers rejected. Trailing bytes after the vector
     * are the chunk text and are not this method's business.</p>
     *
     * @param value The value bytes from the log entry
     * @param expectedDimension The expected vector dimension
     * @return The vector, or null if the value carries no vector of that dimension
     */
    private float[] extractVector(byte[] value, int expectedDimension) {
        return ChunkPayloadCodec.embeddingOf(value, expectedDimension);
    }

    /**
     * Extracts the chunk text stored after the embedding in a write-log entry value.
     *
     * @param value The entry value bytes
     * @return The UTF-8 text bytes truncated to {@link ChunkValueCodec#MAX_TEXT_BYTES},
     *         or null when the entry carries no text
     */
    private byte[] chunkText(byte[] value) {
        return ChunkValueCodec.truncateUtf8(
            ChunkValueCodec.textOf(value), ChunkValueCodec.MAX_TEXT_BYTES);
    }

    /**
     * Extracts the document ID from a write-log entry key.
     *
     * <p>Keys are written by the ingestion pipeline as
     * {@code sourceId:documentId:chunk:index}; the document identity registered against
     * the HNSW node is {@code sourceId:documentId}. Decoding it here means a tail-scan
     * hit and the graph node the same entry later becomes are reported under one
     * identity, so they deduplicate against each other and the document atomicity
     * filter can classify both.</p>
     *
     * @param key The entry key bytes (UTF-8 encoded)
     * @return The document ID, or null if it cannot be determined
     */
    private String extractDocumentId(byte[] key) {
        if (key == null || key.length == 0) {
            return null;
        }

        try {
            String keyStr = new String(key, StandardCharsets.UTF_8);
            int chunkMarker = keyStr.lastIndexOf(CHUNK_MARKER);
            if (chunkMarker > 0) {
                return keyStr.substring(0, chunkMarker);
            }
            // Legacy key format: documentId:chunkIndex
            int lastColon = keyStr.lastIndexOf(':');
            if (lastColon > 0) {
                return keyStr.substring(0, lastColon);
            }
            return keyStr;
        } catch (Exception e) {
            LOGGER.trace("Failed to extract document ID from key: {}", e.getMessage());
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
