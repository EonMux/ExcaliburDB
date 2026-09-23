package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.protocol.RequestBody;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.api.reindex.ReindexCoordinator;
import com.eonmux.excalibur.core.util.StopSignal;
import com.eonmux.excalibur.ingest.pipeline.DocumentReindexer;
import com.eonmux.excalibur.ingest.source.SourceResolver;
import com.eonmux.excalibur.shard.allocator.VolumeManager;
import com.eonmux.excalibur.core.logging.StructuredLoggingHelper;
import com.eonmux.excalibur.store.SegmentCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * HTTP endpoint for the /v1/reindex API.
 *
 * <p>ReindexEndpoint handles POST requests to /v1/reindex for forcing
 * a re-crawl and re-index of a registered source. The reindex operation
 * runs in the background and never takes the source out of service.</p>
 *
 * <h2>Request Format</h2>
 * <pre>{@code
 * {
 *   "source_id": "my-docs"
 * }
 * }</pre>
 *
 * <h2>Response Format</h2>
 * <pre>{@code
 * {
 *   "source_id": "my-docs",
 *   "status": "reindexing",
 *   "message": "Reindex started in background"
 * }
 * }</pre>
 *
 * <h2>Zero-Downtime Reindexing</h2>
 * <p>Each changed document's previous version keeps serving until its replacement has been
 * published, and a document's chunks are published together. A node with a segment catalog
 * first asks for a segment to rebuild the source into; the storage engine never offers one,
 * so the run rebuilds in place and retires no segment. Why a writable one would be refused
 * rather than filled is set out on {@code RollingReplacement}.</p>
 *
 * <h2>Mutual Exclusion</h2>
 * <p>A source can also be reindexed by the scheduler. Both entry points take the same
 * per-source claim from {@link com.eonmux.excalibur.api.reindex.ReindexCoordinator}: two runs
 * over one source compare against the same registry entries, and each retires the version it
 * replaced - which can be the one the other has just published. A request for a source that is
 * already being reindexed is answered with 409.</p>
 *
 * <h2>Removing the Source</h2>
 * <p>Deregistering a source, and erasing one, stops its running reindex through the same claim
 * and waits for it to end - see {@code InMemorySourceRegistry#remove}. The claim is therefore
 * taken before registration is last checked, so a request cannot slip a new run in after the
 * removal has finished waiting.</p>
 *
 * <h2>Without a Reindexer</h2>
 * <p>If no {@link DocumentReindexer} is wired, the endpoint answers 503. Accepting the
 * request with 202 and marking the source in progress would leave it "reindexing" for
 * the lifetime of the process while nothing ran.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Reindex operations are tracked per source.</p>
 *
 * @author ExcaliburDB
 * @since 9.0
 */
public class ReindexEndpoint implements HttpHandler, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReindexEndpoint.class);

    /** What {@link #close()} waits for: whatever it asked to stop, for as long as that takes. */
    private static final Duration UNTIL_STOPPED = Duration.ofNanos(Long.MAX_VALUE);

    private final SourceRegistry sourceRegistry;
    private final ReindexStatusRegistry reindexStatusRegistry;

    // Optional components for actual reindexing; null on a node where POST answers 503
    private final DocumentReindexer documentReindexer;
    private final SourceResolver sourceResolver;
    private final ExecutorService reindexExecutor;

    // Optional components for zero-downtime rolling replacement
    private final VolumeManager volumeManager;
    private final SegmentCatalog segmentCatalog;

    /**
     * What actually runs a reindex once this endpoint has claimed the source, or null on a node
     * where reindexing is not configured and every request is answered 503.
     */
    private final ReindexRun reindexRun;

    /**
     * The authoritative mutual exclusion for reindex operations. Shared with the
     * scheduled reindex handler so a timer tick and an HTTP request cannot start two
     * rolling replacements of the same source, each tombstoning the other's segments.
     */
    private final ReindexCoordinator coordinator;

    /**
     * Creates a new ReindexEndpoint with full reindexing capability.
     *
     * <p>When DocumentReindexer and SourceResolver are provided, reindex
     * operations will execute in background virtual threads, invoking
     * the actual reindexing pipeline.</p>
     *
     * @param sourceRegistry The registry for looking up sources
     * @param reindexStatusRegistry The registry for tracking reindex status
     * @param documentReindexer The document reindexer for executing reindex operations
     * @param sourceResolver The source resolver for obtaining Source instances
     * @throws NullPointerException if sourceRegistry or reindexStatusRegistry is null
     */
    public ReindexEndpoint(SourceRegistry sourceRegistry,
                           ReindexStatusRegistry reindexStatusRegistry,
                           DocumentReindexer documentReindexer,
                           SourceResolver sourceResolver) {
        this(sourceRegistry, reindexStatusRegistry, documentReindexer, sourceResolver, null, null);
    }

    /**
     * Creates a new ReindexEndpoint with zero-downtime rolling replacement support.
     *
     * <p>When VolumeManager and SegmentCatalog are provided in addition to
     * DocumentReindexer, reindex operations will use rolling replacement:</p>
     * <ol>
     *   <li>New segments are created in BOOTSTRAPPING state</li>
     *   <li>Old segments continue serving queries during reindexing</li>
     *   <li>New segments progress through INDEXING → WARMING → ACTIVE</li>
     *   <li>Old segments are tombstoned after new segments become ACTIVE</li>
     * </ol>
     *
     * <p><strong>Zero-Downtime Guarantee:</strong> Data is never unavailable
     * during reindexing because old segments remain queryable until new
     * segments are fully active.</p>
     *
     * @param sourceRegistry The registry for looking up sources
     * @param reindexStatusRegistry The registry for tracking reindex status
     * @param documentReindexer The document reindexer for executing reindex operations
     * @param sourceResolver The source resolver for obtaining Source instances
     * @param volumeManager The volume manager for selecting volumes for new segments (may be null)
     * @param segmentCatalog The segment catalog for managing segment lifecycle (may be null)
     * @throws NullPointerException if sourceRegistry or reindexStatusRegistry is null
     */
    public ReindexEndpoint(SourceRegistry sourceRegistry,
                           ReindexStatusRegistry reindexStatusRegistry,
                           DocumentReindexer documentReindexer,
                           SourceResolver sourceResolver,
                           VolumeManager volumeManager,
                           SegmentCatalog segmentCatalog) {
        this(sourceRegistry, reindexStatusRegistry, documentReindexer, sourceResolver,
            volumeManager, segmentCatalog, new ReindexCoordinator(), null);
    }

    /**
     * Creates a new ReindexEndpoint that shares a reindex coordinator.
     *
     * <p>Production wiring passes the same {@link ReindexCoordinator} instance that
     * the scheduled reindex handler uses, so both entry points contend for the same
     * per-source claim.</p>
     *
     * @param sourceRegistry The registry for looking up sources
     * @param reindexStatusRegistry The registry for tracking reindex status
     * @param documentReindexer The document reindexer for executing reindex operations
     * @param sourceResolver The source resolver for obtaining Source instances
     * @param volumeManager The volume manager for selecting volumes for new segments (may be null)
     * @param segmentCatalog The segment catalog for managing segment lifecycle (may be null)
     * @param coordinator The shared reindex mutual-exclusion primitive
     * @param ingestionStatusRegistry Where a run records how many documents it passed over
     *                                because they are not text, for {@code entries_skipped} on
     *                                {@code GET /v1/ingest/status}; null on a node that keeps no
     *                                ingestion status
     * @throws NullPointerException if sourceRegistry, reindexStatusRegistry or coordinator is null
     */
    public ReindexEndpoint(SourceRegistry sourceRegistry,
                           ReindexStatusRegistry reindexStatusRegistry,
                           DocumentReindexer documentReindexer,
                           SourceResolver sourceResolver,
                           VolumeManager volumeManager,
                           SegmentCatalog segmentCatalog,
                           ReindexCoordinator coordinator,
                           IngestStatusEndpoint.IngestionStatusRegistry ingestionStatusRegistry) {
        this.sourceRegistry = Objects.requireNonNull(sourceRegistry, "SourceRegistry cannot be null");
        this.reindexStatusRegistry = Objects.requireNonNull(reindexStatusRegistry, "ReindexStatusRegistry cannot be null");
        this.coordinator = Objects.requireNonNull(coordinator, "ReindexCoordinator cannot be null");
        this.documentReindexer = documentReindexer;
        this.sourceResolver = sourceResolver;
        this.reindexExecutor = documentReindexer != null ? Executors.newVirtualThreadPerTaskExecutor() : null;
        this.volumeManager = volumeManager;
        this.segmentCatalog = segmentCatalog;
        // Built only where it can run. A reindexer without a resolver has no way to reach the
        // source's documents, which is the same "not configured on this server" that no
        // reindexer at all is, and handle() answers both of them 503 before reaching this.
        this.reindexRun = documentReindexer != null && sourceResolver != null
            ? new ReindexRun(documentReindexer, sourceResolver, volumeManager, segmentCatalog,
                reindexStatusRegistry, ingestionStatusRegistry)
            : null;

        boolean hasRollingReplacement = volumeManager != null && segmentCatalog != null;
        LOGGER.info("ReindexEndpoint configured with document reindexer: {}, rolling replacement: {}",
            documentReindexer != null ? "enabled" : "disabled (POST answers 503)",
            hasRollingReplacement ? "enabled" : "disabled");
    }

    /**
     * Creates a new ReindexEndpoint on a node where reindexing is not configured.
     *
     * <p>{@code POST /v1/reindex} answers 503 for a registered source, and nothing is recorded
     * in the status registry; {@code DELETE} has nothing to cancel.</p>
     *
     * @param sourceRegistry The registry for looking up sources
     * @param reindexStatusRegistry The registry for tracking reindex status
     */
    public ReindexEndpoint(SourceRegistry sourceRegistry, ReindexStatusRegistry reindexStatusRegistry) {
        this(sourceRegistry, reindexStatusRegistry, new ReindexCoordinator());
    }

    /**
     * Creates an endpoint without reindexing capability that shares a coordinator.
     *
     * @param sourceRegistry        The registry for looking up sources
     * @param reindexStatusRegistry The registry for tracking reindex status
     * @param coordinator           The shared reindex mutual-exclusion primitive
     */
    public ReindexEndpoint(SourceRegistry sourceRegistry,
                           ReindexStatusRegistry reindexStatusRegistry,
                           ReindexCoordinator coordinator) {
        this.sourceRegistry = Objects.requireNonNull(sourceRegistry, "SourceRegistry cannot be null");
        this.reindexStatusRegistry = Objects.requireNonNull(reindexStatusRegistry, "ReindexStatusRegistry cannot be null");
        this.coordinator = Objects.requireNonNull(coordinator, "ReindexCoordinator cannot be null");
        this.documentReindexer = null;
        this.sourceResolver = null;
        this.reindexExecutor = null;
        this.volumeManager = null;
        this.segmentCatalog = null;
        this.reindexRun = null;
    }

    /**
     * Creates a new ReindexEndpoint with default in-memory registries and no reindexer.
     *
     * <p>As with {@link #ReindexEndpoint(SourceRegistry, ReindexStatusRegistry)}, every
     * {@code POST} for a registered source is answered 503.</p>
     */
    public ReindexEndpoint() {
        this(new InMemorySourceRegistry(), new InMemoryReindexStatusRegistry());
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        if ("DELETE".equalsIgnoreCase(method)) {
            cancelReindex(exchange);
            return;
        }
        if (!"POST".equalsIgnoreCase(method)) {
            sendError(exchange, 405,
                "Method Not Allowed - Use POST to start a reindex or DELETE to stop one");
            return;
        }

        String correlationId = UUID.randomUUID().toString().substring(0, 8);

        // Use MDC for structured logging - context is automatically cleaned up in finally block
        try (StructuredLoggingHelper.ScopedContext ctx = StructuredLoggingHelper.scopedContext()
                .withCorrelationId(correlationId)
                .build()) {

            try {
                // Parse request body
                String requestBody = readRequestBody(exchange);
                ReindexRequest request = parseRequest(requestBody);

                // Set source ID in MDC for per-source logging
                StructuredLoggingHelper.setSourceId(request.sourceId);

                LOGGER.info("Reindex requested for source");

                // Only a registered crawl has a target to re-read. Documents supplied through
                // POST /v1/documents are recorded under their source id too, but nothing stands
                // behind it to crawl, so the answer says what replaces a reindex for those.
                if (!sourceRegistry.isRegistered(request.sourceId)) {
                    sendError(exchange, 404, "Source not found: " + request.sourceId
                        + ". Only a source registered with POST /v1/ingest can be reindexed; "
                        + "documents supplied with POST /v1/documents are replaced by posting "
                        + "them again");
                    return;
                }

                // Without a reindexer there is nothing to run. Reporting 202 and
                // marking the source in progress would leave it permanently "reindexing"
                // while nothing happens, and would block every later request.
                if (documentReindexer == null || sourceResolver == null || reindexExecutor == null) {
                    LOGGER.warn("Reindex requested but no DocumentReindexer is configured");
                    sendError(exchange, 503, "Reindexing is not configured on this server");
                    return;
                }

                // Claim the source atomically. Reading the status and then starting is
                // a check-then-act: two concurrent requests both see "not in progress"
                // and each launch a rolling replacement that tombstones the other's
                // new segment.
                final String sourceId = request.sourceId;
                ReindexCoordinator.Lease lease = coordinator.tryAcquire(sourceId);
                if (lease == null && !coordinator.isAccepting()) {
                    sendError(exchange, 503, "The node is shutting down and is not reindexing");
                    return;
                }
                if (lease == null) {
                    sendError(exchange, 409, "Reindex already in progress for source: " + sourceId);
                    return;
                }
                // Asked again now the claim is held. Removing a source stops and waits for the
                // reindex holding its claim, so a request that saw the source registered and
                // claimed it only after a removal had finished waiting would otherwise open a
                // status the removal has cleared, for a source nothing can name any more.
                if (!sourceRegistry.isRegistered(sourceId)) {
                    lease.release();
                    sendError(exchange, 404, "Source not found: " + sourceId);
                    return;
                }

                // Whether the claim is now the run's to release, or has been dealt with here;
                // anything else that ends this block leaves it to the finally below.
                boolean settled = false;
                try {
                    reindexStatusRegistry.startReindex(sourceId);
                    // execute, not submit. A cancelled Future on a virtual-thread-per-task
                    // executor skips its task body outright, so a release living in that body's
                    // finally is skipped with it - and a DELETE arriving before the run thread
                    // is scheduled left the source claimed forever, which is the state this
                    // endpoint's DELETE exists to clear. With execute there is no future to
                    // cancel, the body always runs, and the release is reached on every outcome.
                    reindexExecutor.execute(() -> runReindex(sourceId, lease));
                    settled = true;
                    LOGGER.info("Async reindex task submitted for source");
                } catch (RejectedExecutionException e) {
                    // The claim was taken just before a shutdown's drain closed the executor.
                    // That is the node going down, the same answer a claim refused a moment
                    // later gets - not a server fault - and the run it ends was stopped by the
                    // shutdown, not failed: nothing was attempted, and a retry against a node
                    // that is up will do all of it.
                    reindexStatusRegistry.cancelReindex(sourceId);
                    lease.release();
                    settled = true;
                    sendError(exchange, 503, "The node is shutting down and is not reindexing");
                    return;
                } finally {
                    if (!settled) {
                        reindexStatusRegistry.failReindex(sourceId, "Reindex could not be started");
                        lease.release();
                    }
                }

                // Send success response
                ReindexResponse response = new ReindexResponse(
                    request.sourceId,
                    "reindexing",
                    "Reindex started in background"
                );
                sendResponse(exchange, 202, response);

                LOGGER.info("Reindex started for source");

            } catch (RequestBody.TooLargeException e) {
                // Well-formed and too big is not the same failure as malformed: 400 tells the
                // caller not to send it again, 413 tells it to split the payload.
                LOGGER.warn("Refused an oversized reindex body: {}", e.getMessage());
                sendError(exchange, 413, e.getMessage());

            } catch (IllegalArgumentException e) {
                LOGGER.warn("Invalid reindex request: {}", e.getMessage());
                sendError(exchange, 400, "Bad Request: " + e.getMessage());

            } catch (Exception e) {
                LOGGER.error("Error processing reindex request: {}", e.getMessage(), e);
                sendError(exchange, 500, "Internal Server Error");
            }
        }
        // MDC context is automatically cleared when ctx.close() is called
    }

    /**
     * Reads the request body from the HTTP exchange.
     */
    private String readRequestBody(HttpExchange exchange) throws IOException {
        return RequestBody.read(exchange);
    }

    /**
     * Parses the JSON request body into a ReindexRequest.
     */
    private ReindexRequest parseRequest(String json) {
        String sourceId = JsonText.string(json, "source_id");
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("Field 'source_id' is required");
        }
        String violation = SourceIds.violation(sourceId, "source_id");
        if (violation != null) {
            throw new IllegalArgumentException(violation);
        }
        return new ReindexRequest(sourceId);
    }

    /**
     * Sends a JSON response.
     */
    private void sendResponse(HttpExchange exchange, int statusCode, ReindexResponse response) throws IOException {
        String json = toJson(response);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        ResponseBody.send(exchange, statusCode, bytes);
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
     * Converts ReindexResponse to JSON.
     */
    private String toJson(ReindexResponse response) {
        return String.format(
            "{\"source_id\": \"%s\", \"status\": \"%s\", \"message\": \"%s\"}",
            JsonText.escape(response.sourceId),
            JsonText.escape(response.status),
            JsonText.escape(response.message)
        );
    }

    // ==================== Async Reindex Execution ====================

    /**
     * Runs one reindex, and releases its claim however it ends.
     *
     * <p>The claim is released here and nowhere else, so every outcome reaches it: a run that
     * finished, one that threw, and one cancelled before it began. The last is the one that used
     * to be missed, and missing it left the source permanently unreindexable.</p>
     *
     * <p>Cancellation is observed twice, because it can arrive on either side of this thread
     * starting. Before: the lease already says so and the run is never begun. During: the
     * canceller requests the run's stop signal, and a cancellation that lands between the check
     * and the registration is replayed by the lease onto the canceller it is given.</p>
     *
     * <p>The canceller never interrupts this thread. The run writes chunks, tombstones, the
     * lexical index and the registry through channels other threads share, and an interrupt
     * reaching any of those writes closes the channel for every thread for the life of the
     * process. The signal is read between documents, and interrupts only the fetch and the
     * embedding request, which touch nothing shared.</p>
     *
     * @param sourceId The source to reindex
     * @param lease    This run's exclusive claim on it
     */
    private void runReindex(String sourceId, ReindexCoordinator.Lease lease) {
        try {
            if (lease.isCancelled()) {
                // Cancelled, not failed. The caller asked for this, and the readback it polls
                // is the only place the two are distinguishable.
                LOGGER.info("Reindex of {} was cancelled before it started", sourceId);
                reindexStatusRegistry.cancelReindex(sourceId);
                return;
            }
            StopSignal stop = new StopSignal();
            lease.onCancel(stop::request);
            reindexRun.execute(sourceId, stop);
        } finally {
            lease.release();
        }
    }

    /**
     * {@code DELETE /v1/reindex} - asks a running reindex to stop.
     *
     * <h3>Why this exists</h3>
     * <p>A rolling replacement holds its source's claim until it ends, and one that hangs - a
     * source whose fetch never returns - holds it for the life of the process. Every later
     * reindex of that source is then answered 409 forever, and the only recovery is restarting
     * the node, which is not a thing an operator should have to do to a serving node.</p>
     *
     * <h3>What it does not do</h3>
     * <p>It does not release the claim, and the reply does not say the reindex has stopped. The
     * run stops at its next checkpoint and releases its own claim then; releasing it here would
     * let a second rolling replacement start alongside the first, which is the data loss the
     * claim exists to prevent. {@code GET /v1/reindex/status} is what says when it has ended.</p>
     *
     * @param exchange The request to answer
     */
    private void cancelReindex(HttpExchange exchange) throws IOException {
        String sourceId;
        try {
            sourceId = parseRequest(readRequestBody(exchange)).sourceId;
        } catch (RequestBody.TooLargeException e) {
            sendError(exchange, 413, e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            sendError(exchange, 400, "Bad Request: " + e.getMessage());
            return;
        }

        if (!sourceRegistry.isRegistered(sourceId)) {
            sendError(exchange, 404, "Source not found: " + sourceId);
            return;
        }

        boolean cancelled = coordinator.cancel(sourceId);
        if (cancelled) {
            LOGGER.info("Reindex of {} was asked to stop", sourceId);
        }
        // 200 either way, with the fact in the body. A caller clearing a stuck reindex wants to
        // know whether there was one, and "nothing was running" is an answer rather than an
        // error - the state it was asking for is the state it now has.
        ResponseBody.sendJson(exchange, 200,
            "{\"source_id\": \"" + JsonText.escape(sourceId) + "\", "
                + "\"cancelled\": " + cancelled + ", "
                + "\"message\": \"" + (cancelled
                    ? "Reindex was asked to stop; poll /v1/reindex/status for when it has"
                    : "No reindex of this source is running") + "\"}");
    }

    /**
     * Stops accepting new reindex work, asks what is already running to stop, and waits for it.
     *
     * <p>In-flight reindexing writes into the storage engine. Letting those runs go on while the
     * engine and the shared arena are closed is a crash, not an exception, so shutdown waits for
     * them - and says whether they ended, because the caller's next step is to close what they
     * write into. False is the answer that has to be acted on: the storage must then stay
     * open.</p>
     *
     * <p>Every run is covered, whichever trigger started it: the scheduled reindex takes its
     * claim from the same coordinator, so refusing claims there stops a timer tick starting one
     * while this waits, and asking through the claims reaches a scheduled run as surely as one
     * this endpoint started. The runs are asked through their stop signals and never
     * interrupted, for the reason {@link #runReindex(String, ReindexCoordinator.Lease)} gives;
     * each finishes the document it is on and writes out what it got through, so the next
     * reindex picks up where it left off.</p>
     *
     * <p>How long to wait is the caller's: a node's shutdown passes what its budget leaves the
     * drains, so a longer budget is a longer wait. A stopped run finishes one document's fetch,
     * embedding and writes plus a registry write, not the rest of the crawl.</p>
     *
     * @param wait How long to wait for the runs asked to stop to end
     * @return true if no reindex is still running
     * @throws NullPointerException if wait is null
     */
    public boolean drain(Duration wait) {
        Objects.requireNonNull(wait, "wait must not be null");
        coordinator.stopAccepting();
        if (reindexExecutor != null) {
            reindexExecutor.shutdown();
        }
        int asked = coordinator.cancelAll();
        if (asked > 0) {
            LOGGER.info("Asked {} running reindex(es) to stop for shutdown", asked);
        }
        // Wraps rather than saturates for a wait near Long.MAX_VALUE nanoseconds, which the
        // subtraction in remainingNanos undoes: the difference is still the wait.
        long deadline = System.nanoTime() + wait.toNanos();
        try {
            // Rounded up, so the run is never given less than the wait it was promised.
            boolean idle = coordinator.awaitIdle(
                Math.ceilDiv(remainingNanos(deadline), TimeUnit.MILLISECONDS.toNanos(1)));
            boolean terminated = reindexExecutor == null
                || reindexExecutor.awaitTermination(remainingNanos(deadline),
                    TimeUnit.NANOSECONDS);
            if (idle && terminated) {
                return true;
            }
            LOGGER.error("{} reindex(es) still running {}ms after being asked to stop; the "
                    + "storage they write into must stay open", coordinator.inFlightCount(),
                wait.toMillis());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return coordinator.inFlightCount() == 0
                && (reindexExecutor == null || reindexExecutor.isTerminated());
        }
    }

    /** Nanoseconds left before a deadline read from {@link System#nanoTime()}, never negative. */
    private static long remainingNanos(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    /**
     * As {@link #drain(Duration)}, waiting for as long as the runs take to stop, for an owner
     * with no deadline and no use for the answer - the way {@code ExecutorService.close} waits.
     */
    @Override
    public void close() {
        drain(UNTIL_STOPPED);
    }


    // ==================== Inner Classes ====================

    /**
     * Request to reindex a source.
     */
    private record ReindexRequest(String sourceId) {}

    /**
     * Response after starting reindex.
     */
    private record ReindexResponse(String sourceId, String status, String message) {}

    /**
     * Reindex status for a source.
     */
    public static class ReindexStatus {

        /** Reported for a run that stopped without finishing; {@code lastError} says why. */
        public static final String STATE_FAILED = "failed";

        /**
         * Reported for a run that stopped because someone asked it to.
         *
         * <p>Distinct from {@link #STATE_FAILED}, and the distinction is the point.
         * {@code DELETE /v1/reindex} answers "poll /v1/reindex/status for when it has" and is
         * the documented way to clear a wedged reindex, so the readback it points at is where
         * a caller learns the stop worked. Reported as a failure, a deliberate stop is an
         * incident an operator is expected to investigate, and the only thing separating the
         * two was the wording of a message this endpoint deliberately does not carry.</p>
         */
        public static final String STATE_CANCELLED = "cancelled";

        private final String sourceId;
        private volatile String state;
        private volatile double progress;
        private volatile long startTimeMs;
        private volatile long finishedAtMs;
        private volatile long entriesProcessed;
        private volatile long entriesTotal;
        private volatile String lastError;

        public ReindexStatus(String sourceId) {
            this.sourceId = sourceId;
            this.state = "idle";
            this.progress = 0.0;
            this.startTimeMs = 0;
            this.finishedAtMs = 0;
            this.entriesProcessed = 0;
            this.entriesTotal = 0;
            this.lastError = null;
        }

        public String sourceId() { return sourceId; }
        public String state() { return state; }
        public double progress() { return progress; }
        public long startTimeMs() { return startTimeMs; }
        public long entriesProcessed() { return entriesProcessed; }
        public long entriesTotal() { return entriesTotal; }
        public String lastError() { return lastError; }

        /**
         * When this run stopped, however it stopped, or 0 while it is still going.
         *
         * <p>Served beside the start time because between them they are how long the run took,
         * which is the number an operator compares against the next run. A start time on its
         * own says only when someone asked.</p>
         *
         * @return Epoch milliseconds, or 0 for a run that has not ended
         */
        public long finishedAtMs() { return finishedAtMs; }

        /**
         * Whether this run stopped because it was asked to.
         *
         * @return true for a cancelled run
         */
        public boolean cancelled() { return STATE_CANCELLED.equals(state); }

        public boolean isInProgress() {
            return "crawling".equals(state) || "embedding".equals(state) || "indexing".equals(state);
        }

        public void start() {
            this.state = "crawling";
            this.progress = 0.0;
            this.startTimeMs = System.currentTimeMillis();
            this.finishedAtMs = 0;
            this.entriesProcessed = 0;
            this.entriesTotal = 0;
            this.lastError = null;
        }

        /**
         * Records reported progress, holding the same bounds the ingest status does.
         *
         * <p>The two status objects describe the same kind of work to the same clients, so a
         * count that is negative or a progress outside [0.0, 1.0] should be impossible in both
         * rather than in whichever one happened to be written more carefully.</p>
         */
        public void updateState(String newState, double progress, long processed, long total) {
            this.state = newState;
            this.progress = Double.isNaN(progress)
                ? 0.0 : Math.max(0.0, Math.min(1.0, progress));
            this.entriesProcessed = Math.max(0, processed);
            this.entriesTotal = Math.max(0, total);
        }

        public void complete() {
            this.state = "idle";
            this.progress = 1.0;
            this.finishedAtMs = System.currentTimeMillis();
        }

        /**
         * Records that the run finished, and that some of its documents failed on the way.
         *
         * <p>{@code idle} like any finished run, because it did finish and what it indexed is
         * published - reporting it {@link #STATE_FAILED} would have an operator treat a run that
         * reindexed four hundred documents and lost one as a run that reindexed nothing. But not
         * silently: this readback is the only place the outcome of a background run is visible,
         * and a run that left documents behind has to be distinguishable from one that did
         * not.</p>
         *
         * @param error What failed, in the terms the readback may carry
         */
        public void completeWithErrors(String error) {
            complete();
            this.lastError = error;
        }

        /**
         * Records that the run stopped because {@code DELETE /v1/reindex} asked it to.
         *
         * <p>The counters are left as the run last published them: how far a cancelled run got
         * before it stopped is exactly what the caller who stopped it wants to know, and it is
         * also what says whether cancelling again is needed.</p>
         */
        public void cancel() {
            this.state = STATE_CANCELLED;
            this.finishedAtMs = System.currentTimeMillis();
            this.lastError = null;
        }

        /**
         * Records that the run stopped without finishing.
         *
         * <p>A state of its own rather than {@code idle}: this status is served over
         * {@code GET /v1/reindex/status}, and a failed run reported as idle-with-an-error
         * reads as a run that finished and left a note. The two are different verdicts and a
         * caller acts differently on each.</p>
         *
         * @param error Why it stopped
         */
        public void fail(String error) {
            this.state = STATE_FAILED;
            this.lastError = error;
            this.finishedAtMs = System.currentTimeMillis();
        }
    }

    /**
     * Registry interface for tracking reindex status.
     */
    public interface ReindexStatusRegistry {
        ReindexStatus getStatus(String sourceId);
        void startReindex(String sourceId);
        void updateProgress(String sourceId, String state, double progress, long processed, long total);
        void completeReindex(String sourceId);

        /**
         * Records that a run finished with some of its documents failed.
         *
         * <p>Separate from both {@link #completeReindex(String)} and
         * {@link #failReindex(String, String)}: the run finished and its result is published,
         * and it left documents behind, and the readback has to say both.</p>
         *
         * @param sourceId The source whose run finished
         * @param error    How many documents failed, in the terms the readback may carry
         */
        void completeReindexWithErrors(String sourceId, String error);

        void failReindex(String sourceId, String error);

        /**
         * Records that a run stopped because it was asked to.
         *
         * <p>Separate from {@link #failReindex(String, String)} because a caller acts
         * differently on the two, and the readback is the only place either is visible.</p>
         *
         * @param sourceId The source whose run was cancelled
         */
        void cancelReindex(String sourceId);
        Map<String, ReindexStatus> getAllStatuses();

        /**
         * Forgets a source's reindex status.
         *
         * <p>Called when the source is deregistered. Without it the status outlives the source
         * it describes - {@code GET /v1/reindex/status} keeps answering for something that is
         * gone - and the map grows for the life of the process.</p>
         *
         * @param sourceId The source to forget
         * @return true if a status was removed
         */
        boolean remove(String sourceId);
    }

    /**
     * In-memory implementation of ReindexStatusRegistry.
     */
    public static class InMemoryReindexStatusRegistry implements ReindexStatusRegistry {
        private final ConcurrentHashMap<String, ReindexStatus> statuses = new ConcurrentHashMap<>();

        @Override
        public ReindexStatus getStatus(String sourceId) {
            return statuses.get(sourceId);
        }

        @Override
        public boolean remove(String sourceId) {
            return statuses.remove(sourceId) != null;
        }

        @Override
        public void startReindex(String sourceId) {
            ReindexStatus status = new ReindexStatus(sourceId);
            status.start();
            statuses.put(sourceId, status);
        }

        @Override
        public void updateProgress(String sourceId, String state, double progress, long processed, long total) {
            ReindexStatus status = statuses.get(sourceId);
            if (status != null) {
                status.updateState(state, progress, processed, total);
            }
        }

        @Override
        public void completeReindex(String sourceId) {
            ReindexStatus status = statuses.get(sourceId);
            if (status != null) {
                status.complete();
            }
        }

        @Override
        public void completeReindexWithErrors(String sourceId, String error) {
            ReindexStatus status = statuses.get(sourceId);
            if (status != null) {
                status.completeWithErrors(error);
            }
        }

        @Override
        public void failReindex(String sourceId, String error) {
            ReindexStatus status = statuses.get(sourceId);
            if (status != null) {
                status.fail(error);
            }
        }

        @Override
        public void cancelReindex(String sourceId) {
            ReindexStatus status = statuses.get(sourceId);
            if (status != null) {
                status.cancel();
            }
        }

        @Override
        public Map<String, ReindexStatus> getAllStatuses() {
            return Collections.unmodifiableMap(new HashMap<>(statuses));
        }
    }
}
