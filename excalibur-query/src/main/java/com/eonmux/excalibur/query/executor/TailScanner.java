package com.eonmux.excalibur.query.executor;

import com.eonmux.excalibur.query.payload.ChunkValueCodec;
import com.eonmux.excalibur.core.codec.ChunkPayloadCodec;
import com.eonmux.excalibur.metrics.Metrics;
import com.eonmux.excalibur.store.WriteLog;
import com.eonmux.excalibur.store.format.LogEntry;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.distance.DistanceValidator;
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;
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
import java.util.Set;

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
 * {@code com.eonmux.excalibur.core.codec.ChunkPayloadCodec}, which is the only encoder of this
 * layout; this scanner is the only decoder of it outside that class.</p>
 *
 * <h2>Result Identity</h2>
 * <p>Each result carries a synthetic node ID (tagged with the high bit so
 * {@link #isTailScanResult(long)} can recognise it) plus the write-log entry key, which is the
 * chunk id {@code sourceId:documentId:chunk:index}. The HNSW integration worker attributes the
 * node the entry later becomes to the same chunk id, so a tail-scan hit and that node report one
 * identity, and the document atomicity filter derives the document from either the same way.</p>
 *
 * <h2>Later Entries Win</h2>
 * <p>The log holds every write in order, so a chunk rewritten or deleted since it was last
 * integrated appears in the tail more than once. Only the key's last entry says what storage
 * holds: a PUT ranks by its own vector, and a DELETE - or a PUT with no usable vector - ranks
 * nothing. The scan therefore keeps one candidate per key, the latest, and ranks those. It also
 * reports every key the tail touched ({@link TailScanOutcome#touchedKeys()}): for each of them,
 * whatever node the graph holds is an older value than the tail's, which the caller must not rank
 * in its place.</p>
 *
 * <h2>Chunk Text</h2>
 * <p>The chunk text trailing the embedding of each result kept is decoded here and returned
 * alongside the results ({@link TailScanOutcome#payloads()}), so relevancy scoring reads the
 * text of the very value the hit ranked by rather than going back to storage for it.</p>
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
     *   <li>Reads every entry from the write log between startOffset and endOffset</li>
     *   <li>Keeps, per key, only the latest: a PUT's exact dissimilarity to the query, or
     *       nothing for a DELETE</li>
     *   <li>Returns the top-k of those, sorted by dissimilarity (closest first), with the set
     *       of keys the range touched</li>
     * </ol>
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

        requireScannableRange(queryVector, startOffset, endOffset, k);

        // Early return for empty range or k=0 (before null checks on writeLog/distanceFunction)
        if (startOffset >= endOffset || k == 0) {
            return TailScanOutcome.empty();
        }

        // Now check the remaining parameters that are needed for actual scanning
        Objects.requireNonNull(writeLog, "WriteLog cannot be null");
        Objects.requireNonNull(distanceFunction, "DistanceFunction cannot be null");

        LOGGER.trace("Starting tail scan from offset {} to {}, k={}", startOffset, endOffset, k);

        ScanState state = new ScanState();

        // Ensure we don't read beyond the actual write log data
        long scanEnd = Math.min(endOffset, writeLog.writeOffset());

        scanEntries(writeLog, startOffset, scanEnd, queryVector, distanceFunction, state);

        // Record tail scan metrics for JMX observability
        // This tracks: total scans performed, total entries scanned, max entries per scan
        Metrics.recordTailScanMetrics(state.entriesScanned, state.vectorEntriesFound);

        List<Candidate> kept = closest(state, k);
        List<HnswSearchResult> results = new ArrayList<>(kept.size());
        Map<Long, byte[]> payloads = new HashMap<>();
        for (Candidate candidate : kept) {
            results.add(candidate.result());
            byte[] text = textAt(writeLog, candidate.offset());
            if (text != null && text.length > 0) {
                payloads.put(candidate.result().nodeId(), text);
            }
        }

        logScanComplete(state, results.size());

        return new TailScanOutcome(
            results, state.entriesScanned, state.backpressureTriggered, state.cancelled,
            payloads, state.latestByKey.keySet());
    }

    /**
     * Picks the k closest of the candidates still standing when the scan ended.
     *
     * @param state The finished scan
     * @param k The maximum number to keep
     * @return The candidates kept, closest first
     */
    private static List<Candidate> closest(ScanState state, int k) {
        // A max-heap, furthest at its head, so each candidate costs O(log k)
        Comparator<Candidate> byDistance =
            Comparator.comparingDouble(candidate -> candidate.result().distance());
        PriorityQueue<Candidate> top = new PriorityQueue<>(Math.min(k, 1024),
            byDistance.reversed());
        for (Candidate candidate : state.latestByKey.values()) {
            if (candidate == null) {
                continue;
            }
            if (top.size() < k) {
                top.add(candidate);
            } else if (candidate.result().distance() < top.peek().result().distance()) {
                top.poll();
                top.add(candidate);
            }
        }
        List<Candidate> kept = new ArrayList<>(top);
        kept.sort(byDistance);
        return kept;
    }

    /**
     * Reads back the chunk text of an entry the scan kept.
     *
     * <p>Read again rather than held from the pass, so the scan holds text for the k results it
     * returns rather than for every candidate it measured. The log is append-only, so the entry
     * at the offset is the one that was measured.</p>
     *
     * @param writeLog The log scanned
     * @param offset The entry's offset
     * @return The UTF-8 text bytes, or null when the entry carries none
     */
    private byte[] textAt(WriteLog writeLog, long offset) {
        LogEntry entry = readEntryAtOffset(writeLog, offset);
        return entry == null ? null : chunkText(entry.value());
    }

    /**
     * Rejects a scan whose range could not describe a stretch of write log.
     *
     * <p>Only the arguments a range needs, because an empty range is answered without a log or
     * a distance function ever being touched: a caller with nothing to scan should not have to
     * supply either.</p>
     *
     * @param queryVector The query vector to compare against
     * @param startOffset The starting byte offset
     * @param endOffset The ending byte offset
     * @param k The maximum number of results to return
     * @throws NullPointerException if queryVector is null
     * @throws IllegalArgumentException if the offsets are invalid or k is negative
     */
    private static void requireScannableRange(float[] queryVector, long startOffset,
                                              long endOffset, int k) {
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
    }

    /**
     * Walks the range entry by entry until it ends, or until the scan must stop short.
     *
     * <p>Both ways of stopping short are recorded on the state rather than thrown, because the
     * results found before stopping are still worth returning - what the caller must not do is
     * read them as the whole tail.</p>
     *
     * @param writeLog The write log to scan
     * @param startOffset The first offset to read
     * @param scanEnd The offset to stop before
     * @param queryVector The query vector to compare against
     * @param distanceFunction The distance function to use for comparison
     * @param state The counters and candidates for this scan
     */
    private void scanEntries(WriteLog writeLog, long startOffset, long scanEnd,
                             float[] queryVector, DistanceFunction distanceFunction,
                             ScanState state) {
        long currentOffset = startOffset;

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
                writeLog, currentOffset, queryVector, distanceFunction, state);

            if (nextOffset < 0) {
                break;
            }
            currentOffset = nextOffset;
        }
    }

    /**
     * Reports how the scan ended, loudly when it did not cover the whole tail.
     *
     * @param state The counters for the finished scan
     * @param resultCount How many results it is returning
     */
    private void logScanComplete(ScanState state, int resultCount) {
        if (state.backpressureTriggered) {
            LOGGER.warn("Tail scan complete with backpressure: {} entries scanned (limit: {}), " +
                       "{} vectors found, {} results returned. Some tail entries were not scanned.",
                       state.entriesScanned, backpressureEntryLimit, state.vectorEntriesFound, resultCount);
        } else {
            LOGGER.trace("Tail scan complete: {} entries scanned, {} vectors found, {} results returned",
                state.entriesScanned, state.vectorEntriesFound, resultCount);
        }
    }

    /**
     * Processes the entry at {@code offset}: a PUT becomes its key's candidate, measured when
     * it carries a vector of the expected dimension, and a DELETE leaves its key none.
     *
     * @return The offset to continue from, or -1 to abort the scan
     */
    private long scanOne(
            WriteLog writeLog,
            long offset,
            float[] queryVector,
            DistanceFunction distanceFunction,
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

        String key = chunkIdOf(entry.key());
        Candidate candidate = null;
        if (entry.isPut() && entry.valueLength() > ChunkPayloadCodec.HEADER_BYTES) {
            candidate = measure(entry, offset, key, queryVector, distanceFunction, state);
        }
        if (entry.isPut() || entry.isDelete()) {
            state.supersede(key, candidate);
        }

        return offset + entry.size();
    }

    /**
     * Measures one write-log entry against the query.
     *
     * <p>An entry whose value holds no readable vector, or that measures to a value which
     * cannot be ordered, yields no candidate and is not counted as a vector found: the tail was
     * still read past it, and the vector tally is what says how much of it was searchable.</p>
     *
     * @param entry The PUT entry to measure
     * @param offset The entry's offset, which its synthetic node ID is derived from
     * @param key The entry's chunk id
     * @param queryVector The query vector to compare against
     * @param distanceFunction The distance function to use for comparison
     * @param state The counters for this scan
     * @return The entry as a result, or null when it cannot be ranked
     */
    private Candidate measure(LogEntry entry, long offset, String key, float[] queryVector,
                              DistanceFunction distanceFunction, ScanState state) {
        float[] vector = extractVector(entry.value(), queryVector.length);
        if (vector == null) {
            return null;
        }

        // Exact dissimilarity: smaller is closer for every metric
        float distance = distanceFunction.distance(queryVector, vector);

        if (!Float.isFinite(distance)) {
            LOGGER.debug("Skipping tail entry at offset {} with non-finite distance {}",
                offset, distance);
            return null;
        }

        // Clamp on the metric's natural scale, then convert back to the
        // ordered scale so the value stays comparable with HNSW results.
        DistanceFunction.Ordering ordering = distanceFunction.ordering();
        distance = ordering.toDistance(DistanceValidator.validateAndClamp(
            ordering.fromDistance(distance), distanceFunction));

        // Synthetic node ID (high bit tagged) plus the chunk the entry was written under
        long nodeId = encodeNodeId(offset, entry.lsn());
        state.vectorEntriesFound++;
        return new Candidate(new HnswSearchResult(nodeId, distance, key), offset);
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
            LOGGER.warn("Tail scan aborted at offset {}: no entry boundary was found within {} "
                       + "bytes, so the rest of the tail was not searched and {} entries were "
                       + "scanned before aborting. The scan starts at a checkpoint, so this is "
                       + "an offset that does not name the start of an entry - a checkpoint "
                       + "written against a different log, or bytes that did not survive.",
                       offset, MAX_RESYNC_BYTES, state.entriesScanned);
            return -1;
        }
        return offset + 1;
    }

    /**
     * An entry measured against the query, and where to read its text back from.
     *
     * @param result The entry as a search result
     * @param offset The entry's offset in the log
     */
    private record Candidate(HnswSearchResult result, long offset) {
    }

    /**
     * Mutable counters and candidates for a single scan.
     */
    private static final class ScanState {
        /**
         * Each key's latest entry so far: its candidate, or null when that entry ranks nothing
         * (a DELETE, or a PUT with no usable vector). Every key the scan touched is present.
         */
        final Map<String, Candidate> latestByKey = new HashMap<>();
        int entriesScanned;
        int vectorEntriesFound;
        int resyncBytes;
        boolean backpressureTriggered;
        boolean cancelled;

        /**
         * Records an entry as its key's latest, displacing whatever the key had before.
         *
         * @param key The entry's chunk id
         * @param candidate The entry as a result, or null when it ranks nothing
         */
        void supersede(String key, Candidate candidate) {
            latestByKey.put(key, candidate);
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
     * @param touchedKeys Every key an entry the scan read was written under, PUT or DELETE;
     *                    the graph's node for any of them, if it has one, holds an older value
     *                    than the tail does
     */
    public record TailScanOutcome(
        List<HnswSearchResult> results,
        int entriesScanned,
        boolean backpressureTriggered,
        boolean cancelled,
        Map<Long, byte[]> payloads,
        Set<String> touchedKeys
    ) {
        public TailScanOutcome {
            results = results == null ? List.of() : List.copyOf(results);
            payloads = payloads == null ? Map.of() : Map.copyOf(payloads);
            touchedKeys = touchedKeys == null ? Set.of() : Set.copyOf(touchedKeys);
            if (entriesScanned < 0) {
                throw new IllegalArgumentException("entriesScanned must be non-negative: " + entriesScanned);
            }
        }

        /**
         * Returns an outcome describing a scan that did no work.
         */
        static TailScanOutcome empty() {
            return new TailScanOutcome(List.of(), 0, false, false, Map.of(), Set.of());
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
     * Decodes the chunk id a write-log entry was written under.
     *
     * <p>The key is reported whole, as the HNSW integration worker attributes the node the same
     * entry later becomes, so a tail-scan hit and that node carry one identity: they deduplicate
     * against each other, and the document atomicity filter derives the document from both the
     * same way.</p>
     *
     * <p>Every entry has one: {@link LogEntry} refuses an empty key when it is written and when
     * it is read back.</p>
     *
     * @param key The entry key bytes (UTF-8 encoded)
     * @return The chunk id
     */
    private static String chunkIdOf(byte[] key) {
        return new String(key, StandardCharsets.UTF_8);
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
