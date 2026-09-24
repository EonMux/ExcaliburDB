package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.protocol.ErrorCode;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.QueryParams;
import com.eonmux.excalibur.api.protocol.ResponseBody;

import java.util.Locale;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.core.logging.StructuredLoggingHelper;
import com.eonmux.excalibur.ingest.registry.SourceStatusFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * HTTP endpoint for the /v1/ingest/status API.
 *
 * <p>IngestStatusEndpoint handles GET requests to /v1/ingest/status for
 * retrieving the status of ingestion operations per source.</p>
 *
 * <h2>Request Format</h2>
 * <p>Optional query parameter: {@code source_id} to get status for a specific source.
 * If not provided, returns status for all sources.</p>
 *
 * <pre>
 * GET /v1/ingest/status
 * GET /v1/ingest/status?source_id=my-docs
 * </pre>
 *
 * <h2>Response Format</h2>
 * <p>The two forms answer with different shapes. A named source is returned bare, because a caller
 * that asked for one source should not have to unwrap a collection to find it, and 404 when it was
 * never registered:</p>
 * <pre>{@code
 * GET /v1/ingest/status?source_id=my-docs
 * {
 *   "source_id": "my-docs",
 *   "state": "crawling",
 *   "progress": 0.7300,
 *   "entries_ingested": 450000,
 *   "entries_pending": 170000,
 *   "entries_skipped": 1204,
 *   "entries_disallowed": 37,
 *   "documents_failed": 2,
 *   "last_updated_ms": 1730000000000,
 *   "last_error": null
 * }
 * }</pre>
 * <p>Every source is wrapped, so the response stays one shape whether none or many are
 * registered:</p>
 * <pre>{@code
 * GET /v1/ingest/status
 * {"sources": [ ...the objects above... ]}
 * }</pre>
 *
 * <h2>Counts</h2>
 * <p>{@code entries_skipped} is the documents the run listed and passed over because their bytes
 * are not text. {@code entries_disallowed} is the documents it left alone because their publisher
 * forbids fetching them - for a web crawl, the URLs the site's robots.txt disallows, whether the
 * crawl met them as links or a fetch was redirected onto one. They are kept apart because they
 * answer different questions: a skipped image is ordinary, while a disallowed page is one the
 * site asked to be left out, which only its owner - or a registration made with
 * {@code respect_robots_txt: false} - can change. Neither is an error.
 * {@code documents_failed} is the documents the run listed and could not index, because a fetch
 * failed, a document was over the per-document limits, or its chunks could not all be embedded
 * and written. That is an error, and {@code last_error} says the same in words. All three count
 * documents, whatever their prefix; this one is named as {@code POST /v1/documents} names the
 * count it answers with. {@code last_error} is also set by a crawl whose listing had gaps - pages
 * it could not read for links - even with nothing failed, and names those pages: what lies only
 * behind them is not indexed.</p>
 *
 * <h2>States</h2>
 * <ul>
 *   <li>{@code idle} - No ingestion in progress</li>
 *   <li>{@code crawling} - Crawling documents from source</li>
 *   <li>{@code embedding} - Generating embeddings</li>
 *   <li>{@code indexing} - Building index structures</li>
 *   <li>{@code unconfigured} - Registered, but this node has no ingestion pipeline; the
 *       documents were counted into {@code entries_pending} and none were indexed, and
 *       {@code last_error} says what to configure</li>
 * </ul>
 *
 * <h2>Across a restart</h2>
 * <p>With a {@link SourceStatusFile} attached - see
 * {@link InMemoryIngestionStatusRegistry#persistTo(SourceStatusFile)} - every run that ends is
 * written down, and a source restored at startup reports that run: its counts, its error and its
 * {@code last_updated_ms}, rather than zeros stamped with the moment the node came up. A run the
 * restart interrupted never reached the file, so a restored source reports the last run that
 * finished and never one that claims to still be going.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Progress values are in range [0.0, 1.0]</li>
 *   <li>Entry counts are non-negative</li>
 *   <li>State transitions follow valid lifecycle</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Status reads use concurrent collections.</p>
 *
 * @author ExcaliburDB
 * @since 9.0
 */
public class IngestStatusEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestStatusEndpoint.class);

    private final IngestionStatusRegistry statusRegistry;

    /**
     * Creates a new IngestStatusEndpoint.
     *
     * @param statusRegistry The registry for ingestion status
     */
    public IngestStatusEndpoint(IngestionStatusRegistry statusRegistry) {
        this.statusRegistry = Objects.requireNonNull(statusRegistry, "IngestionStatusRegistry cannot be null");
    }

    /**
     * Creates a new IngestStatusEndpoint with an in-memory registry.
     */
    public IngestStatusEndpoint() {
        this(new InMemoryIngestionStatusRegistry());
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        if (!"GET".equalsIgnoreCase(method)) {
            sendError(exchange, 405, "Method Not Allowed");
            return;
        }

        String correlationId = UUID.randomUUID().toString().substring(0, 8);

        // Use MDC for structured logging - context is automatically cleaned up in finally block
        try (StructuredLoggingHelper.ScopedContext ctx = StructuredLoggingHelper.scopedContext()
                .withCorrelationId(correlationId)
                .build()) {

            try {
                // Parse query parameters
                // getRawQuery, not getQuery: the latter is already percent-decoded, so a
                // source id carrying an encoded "&" or "=" would be split on its own decoded
                // delimiter and the value silently truncated, and everything surviving that
                // would then be decoded a second time below.
                String query = exchange.getRequestURI().getRawQuery();
                String sourceId = QueryParams.value(query, "source_id");

                LOGGER.debug("Ingest status request for source: {}", sourceId != null ? sourceId : "all");

                if (sourceId != null) {
                    String violation = SourceIds.violation(sourceId, "source_id");
                    if (violation != null) {
                        sendError(exchange, 400, violation);
                        return;
                    }

                    // Get status for specific source
                    IngestionStatus status = statusRegistry.getStatus(sourceId);
                    if (status == null) {
                        ResponseBody.sendError(exchange, 404, ErrorCode.SOURCE_NOT_FOUND,
                            "Source not found: " + sourceId);
                        return;
                    }
                    sendSingleResponse(exchange, status);
                } else {
                    // Get status for all sources
                    Map<String, IngestionStatus> allStatuses = statusRegistry.getAllStatuses();
                    sendAllResponse(exchange, allStatuses);
                }

            } catch (Exception e) {
                LOGGER.error("Error processing ingest status request: {}", e.getMessage(), e);
                sendError(exchange, 500, "Internal Server Error");
            }
        }
        // MDC context is automatically cleared when ctx.close() is called
    }

    /**
     * Sends a response for a single source.
     */
    private void sendSingleResponse(HttpExchange exchange, IngestionStatus status) throws IOException {
        String json = toJson(status);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        ResponseBody.send(exchange, 200, bytes);
    }

    /**
     * Sends a response for all sources.
     */
    private void sendAllResponse(HttpExchange exchange, Map<String, IngestionStatus> statuses) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"sources\": [");

        boolean first = true;
        for (IngestionStatus status : statuses.values()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(toJson(status));
        }

        sb.append("]}");

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        ResponseBody.send(exchange, 200, bytes);
    }

    /**
     * Sends an error response.
     */
    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        String json = String.format("{\"error\": \"%s\"}", JsonText.escape(message));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        ResponseBody.send(exchange, statusCode, bytes);
    }

    /**
     * Converts IngestionStatus to JSON.
     *
     * <p>{@code last_updated_ms} is when this source last did anything - finished a crawl,
     * failed one, reported progress. The status object had no time of any kind on it, so a
     * source sitting at {@code idle} was indistinguishable whether it had been crawled a
     * minute ago or before a restart three weeks back, and "is the index following the
     * source?" could not be answered from the API at all.</p>
     */
    private String toJson(IngestionStatus status) {
        // Locale.ROOT, because the default locale picks the decimal separator: under de_DE
        // a progress of 0.73 is formatted "0,7300", and the comma ends the JSON value.
        return String.format(
            Locale.ROOT,
            "{" +
            "\"source_id\": \"%s\", " +
            "\"state\": \"%s\", " +
            "\"progress\": %.4f, " +
            "\"entries_ingested\": %d, " +
            "\"entries_pending\": %d, " +
            "\"entries_skipped\": %d, " +
            "\"entries_disallowed\": %d, " +
            "\"documents_failed\": %d, " +
            "\"last_updated_ms\": %d, " +
            "\"last_error\": %s" +
            "}",
            JsonText.escape(status.sourceId()),
            JsonText.escape(status.state()),
            status.progress(),
            status.entriesIngested(),
            status.entriesPending(),
            status.entriesSkipped(),
            status.entriesDisallowed(),
            status.documentsFailed(),
            status.lastUpdatedMs(),
            status.lastError() != null ? "\"" + JsonText.escape(status.lastError()) + "\"" : "null"
        );
    }


    // ==================== Inner Classes ====================

    /**
     * Ingestion status for a source.
     */
    public static class IngestionStatus {

        /**
         * Reported for a source this node has accepted but cannot ingest.
         *
         * <p>Registration succeeds without an ingestion pipeline, because it is bookkeeping. This
         * state is what keeps that from reading as an ingestion that worked: the source is known,
         * its documents were counted into {@code entries_pending}, and none of them were indexed.
         */
        public static final String STATE_UNCONFIGURED = "unconfigured";

        private final String sourceId;
        private volatile String state;
        private volatile double progress;
        private volatile long entriesIngested;
        private volatile long entriesPending;
        private volatile long entriesSkipped;
        private volatile long entriesDisallowed;
        private volatile long documentsFailed;
        private volatile String lastError;
        private volatile long lastUpdatedMs;

        /**
         * When a crawl of this source - its first crawl or a re-crawl - last ran to its end, or 0
         * while none has.
         *
         * <p>Not {@link #lastUpdatedMs}: a run stopped, broken or cut short also updates the
         * status on its way out. This is what the re-crawl schedule of a source restored at
         * startup counts its interval from, so it has to move only for a crawl whose listing was
         * whole and whose documents were all dealt with.</p>
         */
        private volatile long lastCompletedMs;

        /**
         * Told each time this status settles - reaches a state that ends a run - so that what
         * the run ended with can be written down; null when nothing keeps a history.
         */
        private volatile Consumer<IngestionStatus> onSettled;

        public IngestionStatus(String sourceId) {
            this.sourceId = Objects.requireNonNull(sourceId, "sourceId cannot be null");
            this.state = "idle";
            this.progress = 0.0;
            this.entriesIngested = 0;
            this.entriesPending = 0;
            this.entriesSkipped = 0;
            this.entriesDisallowed = 0;
            this.documentsFailed = 0;
            this.lastError = null;
            this.lastUpdatedMs = System.currentTimeMillis();
        }

        public String sourceId() { return sourceId; }
        public String state() { return state; }
        public double progress() { return progress; }
        public long entriesIngested() { return entriesIngested; }
        public long entriesPending() { return entriesPending; }
        public long entriesSkipped() { return entriesSkipped; }
        public long entriesDisallowed() { return entriesDisallowed; }
        public long documentsFailed() { return documentsFailed; }
        public String lastError() { return lastError; }
        public long lastUpdatedMs() { return lastUpdatedMs; }

        /**
         * When a crawl of this source last ran to its end.
         *
         * @return Epoch milliseconds, or 0 when no crawl of it on record has
         */
        public long lastCompletedMs() { return lastCompletedMs; }

        /**
         * Records that a re-crawl of this source ran to its end.
         *
         * <p>Called by the re-crawl itself, which reports nothing else here about how it went -
         * it has its own readback. A first crawl needs no call: reaching {@code idle} at full
         * progress, which is how the ingestion pipeline ends a run it finished, records it.</p>
         */
        public void recordCrawlCompleted() {
            long now = System.currentTimeMillis();
            this.lastCompletedMs = now;
            this.lastUpdatedMs = now;
            reportIfSettled();
        }

        /**
         * Whether this status describes a run that has ended rather than one under way.
         *
         * <p>{@code idle} and {@code unconfigured} are the two states a run is left in; every
         * other state is reported while one is going.</p>
         *
         * @return true once no run is in progress
         */
        public boolean isSettled() {
            String current = state;
            return "idle".equals(current) || STATE_UNCONFIGURED.equals(current);
        }

        /**
         * Hands a settled status to whoever keeps the history, if anyone does.
         *
         * <p>Called after every change rather than only after the last one of a run, because
         * which change is the last is the caller's to know: a run ends with an update to
         * {@code idle} and then, sometimes, a skipped count or an error. Each is written, so the
         * history is never behind what the readback says.</p>
         */
        private void reportIfSettled() {
            Consumer<IngestionStatus> listener = onSettled;
            if (listener != null && isSettled()) {
                listener.accept(this);
            }
        }

        /**
         * What this status would be remembered as.
         *
         * @return The entry the history keeps for it
         */
        SourceStatusFile.Entry snapshot() {
            return new SourceStatusFile.Entry(sourceId, state, progress, entriesIngested,
                entriesPending, entriesSkipped, entriesDisallowed, documentsFailed, lastUpdatedMs,
                lastCompletedMs, lastError);
        }

        /**
         * Rebuilds a status from what the history kept of it.
         *
         * <p>Only ended runs are written, so the state is one a run is left in. One that is not -
         * a file this build did not write - is read as the run it describes having been cut
         * short, rather than served as a run in progress that nothing is running.</p>
         *
         * @param entry What was remembered
         * @return The status, with the time it was last updated as remembered
         */
        static IngestionStatus restoredFrom(SourceStatusFile.Entry entry) {
            IngestionStatus status = new IngestionStatus(entry.sourceId());
            status.state = entry.state();
            status.progress = clampProgress(entry.progress());
            status.entriesIngested = entry.ingested();
            status.entriesPending = entry.pending();
            status.entriesSkipped = entry.skipped();
            status.entriesDisallowed = entry.disallowed();
            status.documentsFailed = entry.failed();
            status.lastError = entry.lastError();
            status.lastUpdatedMs = entry.lastUpdatedMs();
            status.lastCompletedMs = entry.lastCompletedMs();
            if (!status.isSettled()) {
                status.state = "idle";
                status.lastError = INTERRUPTED_BY_RESTART;
            }
            return status;
        }

        /** What a restored status says about a run that was recorded as still going. */
        static final String INTERRUPTED_BY_RESTART =
            "The run in progress when the node stopped did not finish";

        /**
         * Updates the status.
         *
         * <p>An update to any state but {@code idle} says work is under way, which retires the
         * error from the run before it. Nothing else does: {@code last_error} carries no run
         * identity, so an error left over from a run that has since been retried successfully
         * reads as a failure of the run that just finished. An update to {@code idle} is the
         * end of a run, and keeps what that run reported; one at full progress is the end of a
         * run that finished, and records when the source was last crawled to its end.</p>
         *
         * @param state Current state
         * @param progress Progress in range [0.0, 1.0]
         * @param entriesIngested Number of entries successfully ingested
         * @param entriesPending Number of entries waiting to be processed
         */
        public void update(String state, double progress, long entriesIngested, long entriesPending) {
            if (!"idle".equals(state)) {
                this.lastError = null;
            }
            this.state = state;
            this.progress = clampProgress(progress);
            this.entriesIngested = Math.max(0, entriesIngested);
            this.entriesPending = Math.max(0, entriesPending);
            long now = System.currentTimeMillis();
            if ("idle".equals(state) && this.progress >= 1.0) {
                this.lastCompletedMs = now;
            }
            this.lastUpdatedMs = now;
            reportIfSettled();
        }

        /**
         * Clamps a reported progress into [0.0, 1.0].
         *
         * <p>NaN is mapped to zero rather than left alone. Comparison against NaN is always
         * false, so {@code Math.max(0.0, Math.min(1.0, NaN))} is NaN: the arithmetic clamp
         * passes it straight through. This value is serialised with {@code %.4f}, and a body
         * carrying a bare {@code NaN} is not JSON at all - every client fails to parse the whole
         * response rather than reading one implausible number. The infinities need no special
         * case; they clamp correctly.</p>
         */
        private static double clampProgress(double progress) {
            if (Double.isNaN(progress)) {
                return 0.0;
            }
            return Math.max(0.0, Math.min(1.0, progress));
        }

        /**
         * Records how many of this run's documents were passed over because they are not text.
         *
         * <p>Left alone by {@link #update}, and by {@link #setError}: a run reports it when it
         * starts and again when it ends, so it describes the run being reported on and not the
         * one before it. Not an error - a crawl root holding images beside its documents is
         * ordinary - which is why it is a count of its own rather than a message.</p>
         *
         * @param entriesSkipped Documents listed whose bytes are not text; negative is read as
         *                       zero
         */
        public void setSkipped(long entriesSkipped) {
            this.entriesSkipped = Math.max(0, entriesSkipped);
            this.lastUpdatedMs = System.currentTimeMillis();
            reportIfSettled();
        }

        /**
         * Records how many of this run's documents were left alone because their publisher
         * forbids fetching them.
         *
         * <p>Kept like {@link #setSkipped}: left alone by {@link #update} and {@link #setError},
         * reported by the run it describes, and not an error.</p>
         *
         * @param entriesDisallowed Documents robots.txt, or its like, kept out of the run;
         *                          negative is read as zero
         */
        public void setDisallowed(long entriesDisallowed) {
            this.entriesDisallowed = Math.max(0, entriesDisallowed);
            this.lastUpdatedMs = System.currentTimeMillis();
            reportIfSettled();
        }

        /**
         * Records how many of this run's documents could not be indexed.
         *
         * <p>Kept like {@link #setSkipped}: left alone by {@link #update} and {@link #setError},
         * and reported by the run it describes. Unlike it, a failure: the run's error says the
         * same in words.</p>
         *
         * @param documentsFailed Documents listed and not indexed through a failure; negative is
         *                        read as zero
         */
        public void setFailed(long documentsFailed) {
            this.documentsFailed = Math.max(0, documentsFailed);
            this.lastUpdatedMs = System.currentTimeMillis();
            reportIfSettled();
        }

        /**
         * Sets an error state.
         */
        public void setError(String error) {
            this.lastError = error;
            this.state = "idle";
            this.lastUpdatedMs = System.currentTimeMillis();
            reportIfSettled();
        }

        /**
         * Records that this source will not be ingested until the node is configured for it.
         *
         * <p>Distinct from {@link #setError(String)}, which reports an ingestion that ran and
         * failed and correctly leaves the source idle. Nothing ran here, so settling on
         * {@code idle} would make a node that indexed nothing report the same state as one that
         * finished. The crawled count is carried as pending because those documents exist and are
         * waiting on configuration, not on work.</p>
         *
         * @param reason          What is missing, and what to change
         * @param entriesPending  How many documents the crawl found
         */
        public void setUnconfigured(String reason, long entriesPending) {
            this.state = STATE_UNCONFIGURED;
            this.progress = 0.0;
            this.entriesIngested = 0;
            this.entriesSkipped = 0;
            this.entriesDisallowed = 0;
            this.documentsFailed = 0;
            this.entriesPending = Math.max(0, entriesPending);
            this.lastError = reason;
            this.lastUpdatedMs = System.currentTimeMillis();
            reportIfSettled();
        }

        /**
         * Resets to idle state.
         */
        public void reset() {
            this.state = "idle";
            this.progress = 0.0;
            this.entriesIngested = 0;
            this.entriesPending = 0;
            this.entriesSkipped = 0;
            this.entriesDisallowed = 0;
            this.documentsFailed = 0;
            this.lastError = null;
            this.lastCompletedMs = 0;
            this.lastUpdatedMs = System.currentTimeMillis();
            reportIfSettled();
        }
    }

    /**
     * Registry interface for ingestion status.
     */
    public interface IngestionStatusRegistry {
        /**
         * Gets the status for a source.
         *
         * @param sourceId The source ID
         * @return The status, or null if not found
         */
        IngestionStatus getStatus(String sourceId);

        /**
         * Gets all statuses.
         *
         * @return Map of source ID to status
         */
        Map<String, IngestionStatus> getAllStatuses();

        /**
         * Creates or updates status for a source.
         *
         * @param sourceId The source ID
         * @return The status object to update
         */
        IngestionStatus getOrCreate(String sourceId);

        /**
         * Returns the status for a source brought back at startup, as its last run left it.
         *
         * <p>A registry that keeps no history has nothing to restore from, and answers as
         * {@link #getOrCreate(String)} does.</p>
         *
         * @param sourceId The source ID
         * @return The status object to update
         */
        default IngestionStatus restore(String sourceId) {
            return getOrCreate(sourceId);
        }

        /**
         * Removes status for a source.
         *
         * @param sourceId The source ID
         */
        void remove(String sourceId);
    }

    /**
     * In-memory implementation of IngestionStatusRegistry.
     *
     * <p>In memory is where the statuses are served from, not necessarily where they end. With a
     * {@link SourceStatusFile} attached through {@link #persistTo(SourceStatusFile)}, every run
     * that ends is written to it and every removal taken back out, and {@link #restore(String)}
     * brings a source's last run back at startup - the same arrangement the source registry has
     * with its catalog.</p>
     *
     * <h2>What the file holds</h2>
     * <p>One entry per source this process serves: the last run each one finished, or, for a
     * restored source that has not run since, what the previous process remembered of it. A
     * history no restored source claims - a source that could not be rebuilt at startup - is
     * dropped at the first write, so the file never describes more than the node serves.</p>
     *
     * <h2>Thread Safety</h2>
     * <p>Thread-safe. Reads are lock-free; writing the history holds a lock, so runs ending
     * together serialise on the file rather than racing to replace it, and a removal cannot be
     * undone by a run that was still reporting when it happened.</p>
     */
    public static class InMemoryIngestionStatusRegistry implements IngestionStatusRegistry {

        private static final Logger HISTORY_LOGGER =
            LoggerFactory.getLogger(InMemoryIngestionStatusRegistry.class);

        private final ConcurrentHashMap<String, IngestionStatus> statuses = new ConcurrentHashMap<>();

        /** Guards {@link #history} and {@link #remembered}, and serialises writes of the file. */
        private final Object historyLock = new Object();

        /** What the file is to hold: the last settled status of every source served. */
        private final Map<String, SourceStatusFile.Entry> history = new HashMap<>();

        /** What the previous process left, waiting for its source to be restored. */
        private final Map<String, SourceStatusFile.Entry> remembered = new HashMap<>();

        private volatile SourceStatusFile file;

        /**
         * Makes the outcome of each run outlast the process.
         *
         * <p>Attached before any source is restored: what the file holds is read once, here, and
         * handed out by {@link #restore(String)} as the sources it describes come back.</p>
         *
         * @param statusFile Where each source's last run is written
         * @throws NullPointerException if the file is null
         */
        public void persistTo(SourceStatusFile statusFile) {
            Objects.requireNonNull(statusFile, "statusFile cannot be null");
            synchronized (historyLock) {
                this.file = statusFile;
                remembered.clear();
                remembered.putAll(statusFile.loaded());
            }
        }

        @Override
        public IngestionStatus getStatus(String sourceId) {
            return statuses.get(sourceId);
        }

        @Override
        public Map<String, IngestionStatus> getAllStatuses() {
            return Collections.unmodifiableMap(new HashMap<>(statuses));
        }

        @Override
        public IngestionStatus getOrCreate(String sourceId) {
            return statuses.computeIfAbsent(sourceId, this::watched);
        }

        /**
         * Returns a source's status as its last run left it, before this process ran it.
         *
         * <p>Taken from what the file held when it was attached, once; a source with nothing
         * there - never crawled to the end, or registered on a node that kept no history - starts
         * idle and empty, as a fresh registration does. The history taken over is kept in the
         * file from then on even if the source does not run again before the next restart.</p>
         *
         * <p>Built outside the map's own locking and published with {@code putIfAbsent}: taking
         * the history lock inside {@code computeIfAbsent} would order it after the map's bin lock,
         * the reverse of {@link #remove(String)}, which takes the map's lock inside it.</p>
         */
        @Override
        public IngestionStatus restore(String sourceId) {
            IngestionStatus existing = statuses.get(sourceId);
            if (existing != null) {
                return existing;
            }
            SourceStatusFile.Entry entry;
            synchronized (historyLock) {
                entry = remembered.remove(sourceId);
            }
            IngestionStatus restored = entry == null
                ? watched(sourceId) : watch(IngestionStatus.restoredFrom(entry));
            IngestionStatus winner = statuses.putIfAbsent(sourceId, restored);
            if (winner != null) {
                return winner;
            }
            if (entry != null) {
                synchronized (historyLock) {
                    if (statuses.get(sourceId) == restored) {
                        history.putIfAbsent(sourceId, restored.snapshot());
                    }
                }
            }
            return restored;
        }

        @Override
        public void remove(String sourceId) {
            synchronized (historyLock) {
                statuses.remove(sourceId);
                remembered.remove(sourceId);
                if (history.remove(sourceId) != null) {
                    writeHistory();
                }
            }
        }

        /** A new, empty status whose settling is written down. */
        private IngestionStatus watched(String sourceId) {
            return watch(new IngestionStatus(sourceId));
        }

        private IngestionStatus watch(IngestionStatus status) {
            status.onSettled = this::settled;
            return status;
        }

        /**
         * Writes down what a run ended with.
         *
         * <p>Only for the status this registry still serves under that id: a run still reporting
         * after its source was removed is describing something that is gone, and writing it
         * would put back the history the removal took out.</p>
         *
         * @param status The status that settled
         */
        private void settled(IngestionStatus status) {
            if (file == null) {
                return;
            }
            synchronized (historyLock) {
                if (statuses.get(status.sourceId()) != status) {
                    return;
                }
                history.put(status.sourceId(), status.snapshot());
                writeHistory();
            }
        }

        /**
         * Replaces the file with the history as it now stands, and never fails the caller.
         *
         * <p>The caller is an ingestion run or a removal, and neither should fail because a
         * report could not be written: the run's result is in storage and the readback serves it
         * either way. What is lost is that the next restart does not remember it, which the log
         * says.</p>
         */
        private void writeHistory() {
            SourceStatusFile target = file;
            if (target == null) {
                return;
            }
            try {
                target.write(history.values());
            } catch (IOException | RuntimeException e) {
                HISTORY_LOGGER.warn("Could not write the ingest status history; the next "
                    + "restart will report these sources from their next run: {}",
                    e.toString());
            }
        }
    }
}
