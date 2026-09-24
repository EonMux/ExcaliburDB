package com.eonmux.excalibur.api.endpoint;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CancellationException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.api.protocol.RequestBody;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.config.VisibilityConfig;
import com.eonmux.excalibur.core.trace.QueryTrace;
import com.eonmux.excalibur.core.util.QueryBudget;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;
import com.eonmux.excalibur.core.trace.Stage;
import com.eonmux.excalibur.planner.ConversationContext;
import com.eonmux.excalibur.planner.QueryPlan;
import com.eonmux.excalibur.query.executor.QueryRegistry;
import com.eonmux.excalibur.retrieval.pipeline.Aggregation;
import com.eonmux.excalibur.retrieval.pipeline.GraphRagPipeline;
import com.eonmux.excalibur.retrieval.pipeline.GraphRagResult;
import com.eonmux.excalibur.retrieval.pipeline.InputRequest;
import com.eonmux.excalibur.retrieval.pipeline.TraversalDirection;
import com.eonmux.excalibur.retrieval.pipeline.WalkTruncation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * {@code POST /v1/graph/query} — answers a question from the graph.
 *
 * <p>Runs the full path: classify the question, link its mentions to entities, walk the graph at
 * the requested instant, fuse the retrieval signals, decide whether the evidence supports an
 * answer, and serialise what survives into a token budget.
 *
 * <p>The response distinguishes the three ways a request can end, because a client has to treat
 * them differently:
 * <ul>
 *   <li><b>{@code answered}</b> — context to put in a prompt, with a calibrated confidence. A
 *       count, earliest or latest question also carries an {@code aggregate} object: the value
 *       the graph computed, which the context leads with too.</li>
 *   <li><b>{@code needs_input}</b> — the question cannot be answered until the user says which
 *       thing it means: a pronoun could not be bound, no entity was named, or a name matched
 *       several entities the evidence could not tell apart. The body carries the question to put
 *       to the user and the candidates to choose between; sending the chosen one back in
 *       {@code context_entities} with the same query answers it. Returned as 200, not an error:
 *       the request was well-formed and the system is working as intended.</li>
 *   <li><b>{@code declined}</b> — the evidence does not support an answer, with the reason.
 *       Deliberately not an empty {@code answered}, which a client would render as "no results
 *       found" when the truth may be "found something and did not trust it".</li>
 * </ul>
 *
 * <p>An optional {@code context_entities} carries the conversation the question is asked in, so
 * a pronoun in it can be bound: either plain names, or objects that also give each entity's
 * grammatical shape and the turn it was mentioned in. {@link GraphConversation} reads both forms
 * and says what each field decides.
 *
 * <p>An optional {@code direction} - {@code forward}, {@code reverse} or {@code both}, the
 * default - restricts which way the walk may step along an edge from the entities the question
 * names: forward reads the facts they assert, reverse the facts asserted about them.
 *
 * <p>A one-way walk needs the graph's relation vocabulary, and a pipeline embedded without one can
 * only walk both ways. Asking it for one direction is answered {@code 422}. Not {@code 400}: the
 * request is well formed, and the same body is answered by any node built with the vocabulary.
 * Not {@code 500}, nor the {@code 501} beside it: nothing on this node failed, and a server-error
 * status would page an operator over a request the caller settles by resending it without
 * {@code direction}. {@code 422} says exactly that - understood, and not processable here as
 * asked.</p>
 *
 * <h2>Deadline and cancellation</h2>
 * <p>An optional {@code timeout_ms} bounds the question, validated as {@code POST /v1/query}
 * validates it and defaulting to the same {@code excalibur.visibility.query-timeout-ms}, at most
 * {@code excalibur.api.max-query-timeout-ms}. The deadline reaches every stage that can wait: the
 * walk, the question's embedding and the model rerank, each of which is abandoned when it passes
 * rather than allowed the model client's own timeout and retries. What was gathered by then is
 * judged as usual and answered with {@code "timed_out": true}; a deadline that passes before the
 * graph is walked leaves nothing to judge, and is {@code declined} with reason
 * {@code timed_out}.</p>
 *
 * <p>Every question is registered for as long as it runs: it is listed by
 * {@code GET /v1/query/status} with {@code "kind": "graph"}, and {@code DELETE
 * /v1/query/{query_id}} withdraws it - stopping the walk and interrupting any model call in
 * flight - after which it answers {@code "status": "cancelled"}. Every response carries the
 * {@code query_id}.</p>
 *
 * <h2>Walk limits</h2>
 * <p>An answer whose walk was cut short by {@code excalibur.graph.max-frontier} or
 * {@code max-walk-facts} says so in a {@code walk} object: {@code truncated}, and how many
 * entities and facts were left out.</p>
 *
 * <p>Per-stage latency is returned with every response, including the ones that answered nothing —
 * a refusal that took two seconds is as much a performance question as an answer that did.
 */
public class GraphQueryEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphQueryEndpoint.class);

    /** The {@code as_of} value asking for the whole history rather than a snapshot. */
    private static final String ALL_HISTORY = "all";

    /** Longest question accepted, in characters, to bound the work one request can start. */
    private static final int MAX_QUESTION_LENGTH = 4096;

    /** The one pipeline to answer from, when this node is not multi-tenant; null otherwise. */
    private final GraphRagPipeline pipeline;

    /** The namespaces to answer from, when this node is multi-tenant; null otherwise. */
    private final com.eonmux.excalibur.api.bootstrap.GraphTenants tenants;

    /** Where a running question is listed and withdrawn from; null where nothing can be. */
    private final QueryRegistry queryRegistry;

    /** How long a question may run when the request names no {@code timeout_ms}. */
    private final long defaultTimeoutMs;

    /** The longest {@code timeout_ms} a request may name. */
    private final long maxTimeoutMs;

    /**
     * Creates the endpoint over one pipeline, with the default deadline and nowhere to withdraw
     * a question from.
     *
     * @param pipeline The pipeline to answer from
     */
    public GraphQueryEndpoint(GraphRagPipeline pipeline) {
        this(pipeline, null, VisibilityConfig.DEFAULT_QUERY_TIMEOUT_MS,
            ApiSecurityConfig.DEFAULT_MAX_QUERY_TIMEOUT_MS);
    }

    /**
     * Creates the endpoint over one pipeline.
     *
     * @param pipeline         The pipeline to answer from
     * @param queryRegistry    Where running questions are listed and withdrawn from, or null
     * @param defaultTimeoutMs How long a question may run when it names no {@code timeout_ms}
     * @param maxTimeoutMs     The longest {@code timeout_ms} a request may name
     * @throws IllegalArgumentException if a timeout is not positive, or the default exceeds the
     *                                  maximum
     */
    public GraphQueryEndpoint(GraphRagPipeline pipeline, QueryRegistry queryRegistry,
                              long defaultTimeoutMs, long maxTimeoutMs) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline cannot be null");
        this.tenants = null;
        this.queryRegistry = queryRegistry;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.maxTimeoutMs = maxTimeoutMs;
        checkTimeouts(defaultTimeoutMs, maxTimeoutMs);
    }

    /**
     * Creates the endpoint over every namespace's graph, with the default deadline and nowhere to
     * withdraw a question from.
     *
     * <p>The namespace comes from a request header and defaults to the shared one, so a node that
     * never sees the header answers exactly as a single-tenant node would.</p>
     *
     * @param tenants The namespaces to answer from
     */
    public GraphQueryEndpoint(com.eonmux.excalibur.api.bootstrap.GraphTenants tenants) {
        this(tenants, null, VisibilityConfig.DEFAULT_QUERY_TIMEOUT_MS,
            ApiSecurityConfig.DEFAULT_MAX_QUERY_TIMEOUT_MS);
    }

    /**
     * Creates the endpoint over every namespace's graph.
     *
     * @param tenants          The namespaces to answer from
     * @param queryRegistry    Where running questions are listed and withdrawn from, or null
     * @param defaultTimeoutMs How long a question may run when it names no {@code timeout_ms}
     * @param maxTimeoutMs     The longest {@code timeout_ms} a request may name
     * @throws IllegalArgumentException if a timeout is not positive, or the default exceeds the
     *                                  maximum
     */
    public GraphQueryEndpoint(com.eonmux.excalibur.api.bootstrap.GraphTenants tenants,
                              QueryRegistry queryRegistry, long defaultTimeoutMs,
                              long maxTimeoutMs) {
        this.tenants = Objects.requireNonNull(tenants, "tenants cannot be null");
        this.pipeline = null;
        this.queryRegistry = queryRegistry;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.maxTimeoutMs = maxTimeoutMs;
        checkTimeouts(defaultTimeoutMs, maxTimeoutMs);
    }

    private static void checkTimeouts(long defaultTimeoutMs, long maxTimeoutMs) {
        if (defaultTimeoutMs <= 0 || maxTimeoutMs <= 0) {
            throw new IllegalArgumentException("timeouts must be positive, got a default of "
                + defaultTimeoutMs + "ms and a maximum of " + maxTimeoutMs + "ms");
        }
        if (defaultTimeoutMs > maxTimeoutMs) {
            throw new IllegalArgumentException("the default timeout (" + defaultTimeoutMs
                + "ms) exceeds the maximum a request may ask for (" + maxTimeoutMs + "ms)");
        }
    }

    /**
     * One response, decided before it is written.
     *
     * @param status The HTTP status
     * @param body   The JSON body
     */
    private record Reply(int status, String body) {
    }

    /**
     * What identifies one running question, and bounds it.
     *
     * @param queryId       The id it is listed and withdrawn under
     * @param correlationId The id its log lines carry
     * @param budget        Its deadline and cancellation
     * @param trace         Per-stage latency
     */
    private record Running(String queryId, String correlationId, QueryBudget budget,
                           QueryTrace trace) {
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        String body;
        try {
            body = RequestBody.read(exchange);
        } catch (RequestBody.TooLargeException e) {
            send(exchange, 413, "{\"error\":" + quoted(e.getMessage()) + "}");
            return;
        }

        // A field of the wrong JSON type is the caller's mistake, so it is answered 400 here
        // rather than escaping to the router, which can only call it a server fault.
        String question;
        ConversationContext context;
        String asOfText;
        TraversalDirection direction;
        long timeoutMs;
        try {
            question = JsonText.string(body, "query");
            context = GraphConversation.from(body);
            asOfText = JsonText.string(body, "as_of");
            direction = directionFrom(JsonText.string(body, "direction"));
            timeoutMs = timeoutFrom(JsonText.longValue(body, "timeout_ms", defaultTimeoutMs),
                maxTimeoutMs);
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "{\"error\":" + quoted(e.getMessage()) + "}");
            return;
        }
        if (question == null || question.isBlank()) {
            send(exchange, 400, "{\"error\":\"'query' is required\"}");
            return;
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            send(exchange, 400, "{\"error\":\"'query' exceeds " + MAX_QUESTION_LENGTH
                + " characters\"}");
            return;
        }

        TemporalFilter asOf;
        try {
            asOf = temporalFilterFrom(asOfText);
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "{\"error\":" + quoted(e.getMessage()) + "}");
            return;
        }

        Running running = new Running(UUID.randomUUID().toString(),
            UUID.randomUUID().toString().substring(0, 8), QueryBudget.ofMillis(timeoutMs),
            new QueryTrace());
        QueryWithdrawal withdrawal = QueryWithdrawal.open(queryRegistry, running.queryId(),
            running.correlationId(), QueryRegistry.Kind.GRAPH).stops(running.budget());
        Reply reply;
        boolean withdrawn;
        try {
            reply = tenants != null
                ? answerFromTenant(exchange, question, context, asOf, direction, running)
                : answerFromPipeline(question, context, asOf, direction, running);
        } finally {
            // Decided only once the registration is gone, so a DELETE cannot arrive after it.
            withdrawn = withdrawal.close();
        }
        send(exchange, withdrawn ? 200 : reply.status(),
            withdrawn ? renderCancelled(running) : reply.body());
    }

    /**
     * Reads the deadline a request named.
     *
     * @param timeoutMs    The {@code timeout_ms} the request carried, or the default
     * @param maxTimeoutMs The longest a request may name
     * @return The timeout
     * @throws IllegalArgumentException if it is not positive, or longer than the maximum
     */
    static long timeoutFrom(long timeoutMs, long maxTimeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("Field 'timeout_ms' must be positive: " + timeoutMs);
        }
        if (timeoutMs > maxTimeoutMs) {
            throw new IllegalArgumentException(
                "Field 'timeout_ms' must be at most " + maxTimeoutMs + ": " + timeoutMs);
        }
        return timeoutMs;
    }

    /**
     * Answers from the one pipeline this node serves.
     *
     * @return The response to write, unless the question is withdrawn before it is
     */
    private Reply answerFromPipeline(String question, ConversationContext context,
                                     TemporalFilter asOf, TraversalDirection direction,
                                     Running running) {
        if (!pipeline.supports(direction)) {
            return unsupportedDirection(direction);
        }
        try {
            GraphRagResult result = pipeline.answer(question, context, asOf, direction,
                running.trace(), running.budget());
            return new Reply(200, render(result, running, asOf));
        } catch (IllegalArgumentException e) {
            return new Reply(400, "{\"error\":" + quoted(e.getMessage()) + "}");
        } catch (RuntimeException e) {
            return failed(e, running, "graph query failed");
        }
    }

    /**
     * The reply to a question the pipeline did not finish.
     *
     * <p>A cancellation is the caller's withdrawal, which the handler answers once the
     * registration is closed; anything else is a fault, logged with detail and answered without
     * it: the message can name internal paths, host names and entity ids that a client has no
     * business seeing.</p>
     */
    private static Reply failed(RuntimeException e, Running running, String what) {
        if (e instanceof CancellationException && running.budget().isCancelled()) {
            LOGGER.debug("graph query {} withdrawn: {}", running.queryId(), e.getMessage());
        } else {
            LOGGER.error("{} (query {})", what, running.queryId(), e);
        }
        return new Reply(500, "{\"error\":\"Internal Server Error\"}");
    }

    /**
     * Answers from the namespace the request names.
     *
     * <p>Under a lease for the whole answer. Without one, an eviction made to stay inside the
     * mapping budget could unmap the store this query is walking - and a query reading unmapped
     * memory is the failure the budget exists to avoid, not one it is allowed to cause.</p>
     *
     * <p>A namespace nobody has created is not created here. Opening one writes its staging
     * directory, takes one of the node's open-graph slots and a share of the mapping budget, and
     * evicts the least recently used real tenant to get them; the name arrives in a header, so a
     * question would otherwise be enough to spend all three, once per name asked. A build is what
     * brings a namespace into being, and until one has run there is no graph to serve - which is
     * what the answer says.</p>
     *
     * @param exchange The exchange
     * @param question The question asked
     * @param context  The conversation the question is asked in
     * @param asOf      The valid-time instant to answer at, or null for the question's own
     * @param direction Which way along an edge the walk may step
     * @param running   The question's identity, deadline and trace
     * @return The response to write, unless the question is withdrawn before it is
     */
    private Reply answerFromTenant(HttpExchange exchange, String question,
                                   ConversationContext context, TemporalFilter asOf,
                                   TraversalDirection direction, Running running) {
        com.eonmux.excalibur.tenancy.NamespaceId namespace;
        try {
            namespace = com.eonmux.excalibur.api.bootstrap.GraphTenants.namespaceOf(
                exchange.getRequestHeaders().getFirst(
                    com.eonmux.excalibur.api.bootstrap.GraphTenants.NAMESPACE_HEADER));
        } catch (IllegalArgumentException e) {
            return new Reply(400, "{\"error\":" + quoted(e.getMessage()) + "}");
        }

        java.util.Optional<
            com.eonmux.excalibur.tenancy.NamespaceRegistry.Lease<com.eonmux.excalibur.api.bootstrap.GraphTenant>>
            borrowed;
        try {
            borrowed = tenants.borrowIfPresent(namespace);
        } catch (RuntimeException e) {
            return failed(e, running, "graph query failed for namespace " + namespace);
        }
        if (borrowed.isEmpty()) {
            // Deliberately the same answer an existing namespace with no built graph gets. Every
            // tenant on this node presents the same bearer token, so a reply that separated the
            // two would tell one tenant which namespaces the others have.
            return noGraph();
        }

        try (com.eonmux.excalibur.tenancy.NamespaceRegistry.Lease<com.eonmux.excalibur.api.bootstrap.GraphTenant> lease
                 = borrowed.get()) {
            java.util.Optional<com.eonmux.excalibur.api.bootstrap.GraphRagHolder.Reading> reading =
                lease.resource().holder().acquire();
            if (reading.isEmpty()) {
                return noGraph();
            }

            GraphRagResult result = null;
            boolean supported;
            // Released before the response is written: the stack is needed to answer, not to
            // send, and a slow client must not keep a replaced graph open.
            try (com.eonmux.excalibur.api.bootstrap.GraphRagHolder.Reading held = reading.get()) {
                supported = held.pipeline().supports(direction);
                if (supported) {
                    result = held.pipeline().answer(question, context, asOf, direction,
                        running.trace(), running.budget());
                }
            }
            if (!supported) {
                return unsupportedDirection(direction);
            }
            return new Reply(200, render(result, running, asOf));
        } catch (IllegalArgumentException e) {
            return new Reply(400, "{\"error\":" + quoted(e.getMessage()) + "}");
        } catch (RuntimeException e) {
            return failed(e, running, "graph query failed for namespace " + namespace);
        }
    }

    /**
     * Refuses a one-way walk the pipeline answering this request cannot take.
     *
     * <p>Worded as the remedy as well as the cause: the caller can do nothing about how the node
     * was assembled, and everything about the direction it asked for.</p>
     *
     * @param exchange  The exchange
     * @param direction The direction asked for
     * @return The refusal
     */
    private static Reply unsupportedDirection(TraversalDirection direction) {
        return new Reply(422, "{\"error\":" + quoted("'direction' \"" + direction.wireName()
            + "\" needs the graph's relation vocabulary, and the graph pipeline on this node was "
            + "built without one; omit 'direction', or send \"both\"") + "}");
    }

    /**
     * Says that this namespace has no graph to answer from, whatever the reason.
     *
     * @return The refusal
     */
    private static Reply noGraph() {
        return new Reply(503,
            "{\"error\":\"No graph is being served for this namespace; POST "
                + "/v1/graph/build to build one from the facts ingestion has staged\"}");
    }

    /**
     * A JSON string literal, quotes included.
     *
     * <p>{@link JsonText#escape} escapes characters but does not add the surrounding quotes, which
     * is easy to use wrongly — the result looks fine in a substring assertion and is invalid JSON.
     * Wrapping it once here means no call site has to remember.
     */
    private static String quoted(String value) {
        return "\"" + JsonText.escape(value) + "\"";
    }

    /**
     * Reads the valid-time instant a request named.
     *
     * <p>Three spellings, because they are three different questions. An ISO date is a day; an
     * ISO instant is the UTC day it falls in, since valid time is kept to day granularity; and
     * {@code all} is the whole history rather than a snapshot of it, which is what a caller
     * comparing a fact against the one it replaced wants. Anything else is refused rather than
     * ignored: a request that asked for 2019 and silently got today is the failure this
     * parameter exists to prevent.</p>
     *
     * @param text The raw {@code as_of} value, or null when the request carried none
     * @return The filter, or null to leave the question's own dates in charge
     * @throws IllegalArgumentException if the value is present and is not one of the three
     */
    static TemporalFilter temporalFilterFrom(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (ALL_HISTORY.equalsIgnoreCase(trimmed)) {
            return TemporalFilter.ALL;
        }
        try {
            return TemporalFilter.asOf(java.time.LocalDate.parse(trimmed));
        } catch (java.time.format.DateTimeParseException notADate) {
            // Not a date; an instant is the other accepted spelling.
        }
        try {
            return TemporalFilter.asOf(java.time.Instant.parse(trimmed));
        } catch (java.time.format.DateTimeParseException notAnInstant) {
            throw new IllegalArgumentException("'as_of' must be an ISO-8601 date (2019-03-01), "
                + "an ISO-8601 instant (2019-03-01T00:00:00Z) or \"" + ALL_HISTORY
                + "\" for the whole history");
        }
    }

    /**
     * Reads the traversal direction a request named.
     *
     * <p>Absent is {@link TraversalDirection#BOTH}, which is what a question about an edge almost
     * always means. Present and not one of the three is refused rather than defaulted: a caller
     * that asked for one direction and silently got both would read facts it excluded as though
     * they answered its question.</p>
     *
     * @param text The raw {@code direction} value, or null when the request carried none
     * @return The direction to walk in
     * @throws IllegalArgumentException if the value is present and is not a direction
     */
    static TraversalDirection directionFrom(String text) {
        return text == null ? TraversalDirection.BOTH : TraversalDirection.parse(text);
    }

    /** How a resolved instant is reported back. */
    private static String textOf(TemporalFilter asOf) {
        return asOf.isUnbounded()
            ? ALL_HISTORY
            : java.time.LocalDate.ofEpochDay(asOf.asOfDays()).toString();
    }

    /**
     * Reports the instant the graph was actually walked at, and where that instant came from.
     *
     * <p>Every walk happens at one: the caller's, the one the classifier read out of the
     * question's date words, or today when neither named one. Reporting only the first left a
     * caller who asked "who reported the Frankfurt outage in 2019" unable to tell whether the
     * classifier picked up the year or answered as of today - the same failure the 400 on an
     * unparseable {@code as_of} exists to prevent, arriving by a different route.
     *
     * <p>{@code as_of_source} is what keeps the echo from over-claiming. An instant nobody asked
     * for is reported as {@code question} or {@code default}, never bare, so a client can tell an
     * honoured request from an inference and act on the difference.
     *
     * <p>Absent on both fields when no plan was produced: a {@code needs_input} from planning - a
     * pronoun or a missing name - stops before the traversal, so there is no instant to report
     * rather than one worth defaulting. One from an ambiguous entity did walk, and says when.</p>
     *
     * @param json      the response being built
     * @param result    what the pipeline produced, carrying the plan the walk actually used
     * @param requested the caller's instant, or null when the request carried none
     */
    private static void appendAsOf(StringBuilder json, GraphRagResult result,
                                   TemporalFilter requested) {
        var plan = result.plan();
        if (plan.isEmpty()) {
            return;
        }
        TemporalFilter used = plan.get() instanceof QueryPlan.Temporal temporal
            ? temporal.asOf()
            : TemporalFilter.now();
        String source = requested != null ? "request"
            : plan.get() instanceof QueryPlan.Temporal ? "question"
            : "default";
        json.append(",\"as_of\":").append(quoted(textOf(used)))
            .append(",\"as_of_source\":").append(quoted(source));
    }

    private static String render(GraphRagResult result, Running running, TemporalFilter asOf) {
        StringBuilder json = new StringBuilder("{");

        if (result.hasAnswer()) {
            json.append("\"status\":\"answered\"")
                .append(",\"context\":").append(quoted(result.contextText()))
                .append(",\"confidence\":").append(JsonText.number(result.confidence(), 4));
            result.context().ifPresent(context -> json
                .append(",\"tokens\":").append(context.tokens())
                .append(",\"facts_included\":").append(context.edgesIncluded())
                .append(",\"facts_dropped\":").append(context.edgesDropped())
                .append(",\"truncated\":").append(context.wasTruncated())
                .append(",\"format\":").append(quoted(context.format())));
            result.aggregation().ifPresent(aggregation -> appendAggregate(json, aggregation));
        } else if (result.needsUserInput()) {
            InputRequest request = result.inputRequest().orElseThrow();
            json.append("\"status\":\"needs_input\"")
                .append(",\"reason\":")
                .append(quoted(request.reason().name().toLowerCase(Locale.ROOT)))
                .append(",\"question\":").append(quoted(request.question()))
                .append(",\"candidates\":");
            appendStrings(json, request.candidates());
        } else {
            json.append("\"status\":\"declined\"");
            result.declined().ifPresent(outcome -> json.append(",\"reason\":")
                .append(quoted(outcome.reason().name().toLowerCase(Locale.ROOT))));
        }

        json.append(",\"plan\":").append(result.plan()
            .map(plan -> quoted(plan.shape().getClass().getSimpleName().toLowerCase(Locale.ROOT)))
            .orElse("null"));
        appendAsOf(json, result, asOf);
        result.walk().ifPresent(walk -> appendWalk(json, walk));
        appendQuery(json, running, result.timedOut(), false);
        appendTimings(json, running.trace());
        return json.append('}').toString();
    }

    /**
     * The answer to a question withdrawn while it ran: no results, whatever it had reached.
     *
     * @param running The question
     * @return The body
     */
    private static String renderCancelled(Running running) {
        StringBuilder json = new StringBuilder("{\"status\":\"cancelled\"");
        appendQuery(json, running, false, true);
        appendTimings(json, running.trace());
        return json.append('}').toString();
    }

    /** The fields every answer carries about the question as a running query. */
    private static void appendQuery(StringBuilder json, Running running, boolean timedOut,
                                    boolean cancelled) {
        json.append(",\"query_id\":").append(quoted(running.queryId()))
            .append(",\"timed_out\":").append(timedOut)
            .append(",\"cancelled\":").append(cancelled);
    }

    /**
     * Reports what the walk's limits left out of the evidence.
     *
     * @param json the response being built
     * @param walk what the frontier and fact bounds dropped
     */
    private static void appendWalk(StringBuilder json, WalkTruncation walk) {
        json.append(",\"walk\":{\"truncated\":").append(walk.truncated())
            .append(",\"entities_dropped\":").append(walk.entitiesDropped())
            .append(",\"facts_dropped\":").append(walk.factsDropped())
            .append('}');
    }

    /** Per-stage latency, then the total. */
    private static void appendTimings(StringBuilder json, QueryTrace trace) {
        json.append(",\"timings_ms\":{");
        boolean first = true;
        for (Stage stage : Stage.values()) {
            long nanos = trace.nanosIn(stage);
            if (nanos == 0) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(stage.name().toLowerCase(Locale.ROOT)).append("\":")
                .append(JsonText.number(nanos / 1_000_000.0, 3));
        }
        json.append(first ? "" : ",").append("\"total\":")
            .append(JsonText.number(trace.elapsedNanos() / 1_000_000.0, 3)).append('}');
    }

    /**
     * Reports the value an aggregate question asked for, beside the context that leads with it.
     *
     * <p>Structured as well as written into the context, because a caller that asked "how many"
     * wants a number it can use without reading it back out of prose. {@code valid_from} is only
     * on an earliest or latest, and null there when nothing the walk found had a recorded
     * start.</p>
     *
     * @param json        the response being built
     * @param aggregation the computed aggregate
     */
    private static void appendAggregate(StringBuilder json, Aggregation aggregation) {
        json.append(",\"aggregate\":{\"function\":")
            .append(quoted(aggregation.function().name().toLowerCase(Locale.ROOT)))
            .append(",\"count\":").append(aggregation.count())
            .append(",\"facts\":").append(aggregation.factsConsidered());
        if (aggregation.function() != QueryPlan.AggregateFunction.COUNT) {
            json.append(",\"valid_from\":").append(aggregation.validFrom().isPresent()
                ? quoted(java.time.LocalDate.ofEpochDay(aggregation.validFrom().getAsInt())
                    .toString())
                : "null");
        }
        json.append(",\"entities\":");
        appendStrings(json, aggregation.entities());
        json.append('}');
    }

    /** A JSON array of strings. */
    private static void appendStrings(StringBuilder json, java.util.List<String> values) {
        json.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(quoted(values.get(i)));
        }
        json.append(']');
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        ResponseBody.send(exchange, status, bytes);
    }
}
