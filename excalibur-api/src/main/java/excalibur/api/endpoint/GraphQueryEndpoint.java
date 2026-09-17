package excalibur.api.endpoint;

import java.util.Locale;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import excalibur.api.protocol.JsonText;
import excalibur.api.protocol.ResponseBody;
import excalibur.api.protocol.RequestBody;
import io.excalibur.core.trace.QueryTrace;
import io.excalibur.graph.temporal.TemporalFilter;
import io.excalibur.core.trace.Stage;
import io.excalibur.planner.ConversationContext;
import io.excalibur.planner.Grammar;
import io.excalibur.retrieval.pipeline.GraphRagPipeline;
import io.excalibur.retrieval.pipeline.GraphRagResult;

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
 *   <li><b>{@code answered}</b> — context to put in a prompt, with a calibrated confidence.</li>
 *   <li><b>{@code needs_input}</b> — a pronoun could not be bound. The body carries the question to
 *       put to the user. Returned as 200, not an error: the request was well-formed and the system
 *       is working as intended.</li>
 *   <li><b>{@code declined}</b> — the evidence does not support an answer, with the reason.
 *       Deliberately not an empty {@code answered}, which a client would render as "no results
 *       found" when the truth may be "found something and did not trust it".</li>
 * </ul>
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

    /**
     * Most conversation entities accepted in one request.
     *
     * <p>Every mention is a candidate each pronoun in the question is compared against, and a name
     * that can appear in the disambiguation question this endpoint answers with - so an unbounded
     * array is both unbounded work and an unbounded response. The context window is three turns of
     * a conversation; a hundred and twenty-eight entities inside it is already far more than a
     * person mentions.</p>
     */
    private static final int MAX_CONTEXT_ENTITIES = 128;

    /** Longest single conversation entity accepted, in characters. Longer than this is not a name. */
    private static final int MAX_CONTEXT_ENTITY_LENGTH = 256;

    private final java.util.function.Supplier<java.util.Optional<GraphRagPipeline>> pipelineSource;

    /** The namespaces to answer from, when this node is multi-tenant; null otherwise. */
    private final excalibur.api.bootstrap.GraphTenants tenants;

    public GraphQueryEndpoint(GraphRagPipeline pipeline) {
        Objects.requireNonNull(pipeline, "pipeline cannot be null");
        this.pipelineSource = () -> java.util.Optional.of(pipeline);
        this.tenants = null;
    }

    /**
     * Creates the endpoint over every namespace's graph.
     *
     * <p>The namespace comes from a request header and defaults to the shared one, so a node that
     * never sees the header answers exactly as a single-tenant node would.</p>
     *
     * @param tenants The namespaces to answer from
     */
    public GraphQueryEndpoint(excalibur.api.bootstrap.GraphTenants tenants) {
        this.tenants = Objects.requireNonNull(tenants, "tenants cannot be null");
        this.pipelineSource = null;
    }

    /**
     * Creates the endpoint over a pipeline that may be replaced, or may not exist yet.
     *
     * <p>A node can be built for graph answering and have no graph: nothing ingested, or ingested
     * and never built. Resolving per request is what lets a build make the route answer without a
     * restart, and lets a query before the first build say so rather than 404.
     *
     * @param pipelineSource Supplies the pipeline currently being served
     */
    public GraphQueryEndpoint(
            java.util.function.Supplier<java.util.Optional<GraphRagPipeline>> pipelineSource) {
        this.pipelineSource = Objects.requireNonNull(
            pipelineSource, "pipelineSource cannot be null");
        this.tenants = null;
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

        String question = JsonText.string(body, "query");
        if (question == null || question.isBlank()) {
            send(exchange, 400, "{\"error\":\"'query' is required\"}");
            return;
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            send(exchange, 400, "{\"error\":\"'query' exceeds " + MAX_QUESTION_LENGTH
                + " characters\"}");
            return;
        }

        String[] mentions = JsonText.stringArray(body, "context_entities");
        String violation = contextViolation(mentions);
        if (violation != null) {
            send(exchange, 400, "{\"error\":" + quoted(violation) + "}");
            return;
        }
        ConversationContext context = contextFrom(mentions);

        String asOfText = JsonText.string(body, "as_of");
        TemporalFilter asOf;
        try {
            asOf = temporalFilterFrom(asOfText);
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "{\"error\":" + quoted(e.getMessage()) + "}");
            return;
        }

        if (tenants != null) {
            answerFromTenant(exchange, question, context, asOf);
            return;
        }

        java.util.Optional<GraphRagPipeline> pipeline = pipelineSource.get();
        if (pipeline.isEmpty()) {
            // The node can answer graph questions and has no graph to answer from: nothing has
            // been ingested, or facts are staged and no build has run. Saying so is the whole
            // difference between "not configured for this" and "ask me again after a build".
            send(exchange, 503,
                "{\"error\":\"No graph is being served; POST /v1/graph/build to build one from "
                    + "the facts ingestion has staged\"}");
            return;
        }

        try {
            QueryTrace trace = new QueryTrace();
            GraphRagResult result = pipeline.get().answer(question, context, asOf, trace);
            send(exchange, 200, render(result, trace, asOf));
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "{\"error\":" + quoted(e.getMessage()) + "}");
        } catch (RuntimeException e) {
            // Logged with detail, answered without it: the message can name internal paths,
            // host names and entity ids that a client has no business seeing.
            LOGGER.error("graph query failed", e);
            send(exchange, 500, "{\"error\":\"Internal Server Error\"}");
        }
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
     * @param asOf     The valid-time instant to answer at, or null for the question's own
     * @throws IOException if the response cannot be written
     */
    private void answerFromTenant(HttpExchange exchange, String question,
                                  ConversationContext context, TemporalFilter asOf)
            throws IOException {
        io.excalibur.tenancy.NamespaceId namespace;
        try {
            namespace = excalibur.api.bootstrap.GraphTenants.namespaceOf(
                exchange.getRequestHeaders().getFirst(
                    excalibur.api.bootstrap.GraphTenants.NAMESPACE_HEADER));
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "{\"error\":" + quoted(e.getMessage()) + "}");
            return;
        }

        java.util.Optional<
            io.excalibur.tenancy.NamespaceRegistry.Lease<excalibur.api.bootstrap.GraphTenant>>
            borrowed;
        try {
            borrowed = tenants.borrowIfPresent(namespace);
        } catch (RuntimeException e) {
            LOGGER.error("graph query failed for namespace {}", namespace, e);
            send(exchange, 500, "{\"error\":\"Internal Server Error\"}");
            return;
        }
        if (borrowed.isEmpty()) {
            // Deliberately the same answer an existing namespace with no built graph gets. Every
            // tenant on this node presents the same bearer token, so a reply that separated the
            // two would tell one tenant which namespaces the others have.
            sendNoGraph(exchange);
            return;
        }

        try (io.excalibur.tenancy.NamespaceRegistry.Lease<excalibur.api.bootstrap.GraphTenant> lease
                 = borrowed.get()) {
            java.util.Optional<GraphRagPipeline> pipeline = lease.resource().holder().pipeline();
            if (pipeline.isEmpty()) {
                sendNoGraph(exchange);
                return;
            }

            QueryTrace trace = new QueryTrace();
            GraphRagResult result = pipeline.get().answer(question, context, asOf, trace);
            send(exchange, 200, render(result, trace, asOf));
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "{\"error\":" + quoted(e.getMessage()) + "}");
        } catch (RuntimeException e) {
            LOGGER.error("graph query failed for namespace {}", namespace, e);
            send(exchange, 500, "{\"error\":\"Internal Server Error\"}");
        }
    }

    /**
     * Says that this namespace has no graph to answer from, whatever the reason.
     *
     * @param exchange The exchange
     * @throws IOException if the response cannot be written
     */
    private void sendNoGraph(HttpExchange exchange) throws IOException {
        send(exchange, 503,
            "{\"error\":\"No graph is being served for this namespace; POST "
                + "/v1/graph/build to build one from the facts ingestion has staged\"}");
    }

    /**
     * Rebuilds the conversation from what the client sent.
     *
     * <p>The client owns the history; the server holds no session. That keeps the endpoint
     * stateless and horizontally scalable, and it means a client can replay or edit the context it
     * is being answered against — which is the only way to correct a mis-bound pronoun.
     */
    private ConversationContext contextFrom(String[] mentions) {
        ConversationContext context = new ConversationContext();
        if (mentions != null) {
            for (String mention : mentions) {
                if (mention != null && !mention.isBlank()) {
                    // Shape unknown from the wire, which binds any pronoun. Refusing to bind here
                    // would ask the user about something a person would find obvious.
                    context.mention(mention, Grammar.UNKNOWN);
                }
            }
            context.nextTurn();
        }
        return context;
    }

    /**
     * Says why a conversation context is not acceptable, or null when it is.
     *
     * <p>Refused rather than truncated: dropping half a context silently changes which entity a
     * pronoun binds to, and the caller would read the answer as though it had been given the whole
     * conversation.</p>
     */
    private static String contextViolation(String[] mentions) {
        if (mentions == null) {
            return null;
        }
        if (mentions.length > MAX_CONTEXT_ENTITIES) {
            return "'context_entities' holds " + mentions.length + " entries; at most "
                + MAX_CONTEXT_ENTITIES + " are accepted";
        }
        for (String mention : mentions) {
            if (mention != null && mention.length() > MAX_CONTEXT_ENTITY_LENGTH) {
                return "'context_entities' holds an entry of " + mention.length()
                    + " characters; at most " + MAX_CONTEXT_ENTITY_LENGTH + " are accepted";
            }
        }
        return null;
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

    /** How a resolved instant is reported back. */
    private static String textOf(TemporalFilter asOf) {
        return asOf.isUnbounded()
            ? ALL_HISTORY
            : java.time.LocalDate.ofEpochDay(asOf.asOfDays()).toString();
    }

    private String render(GraphRagResult result, QueryTrace trace, TemporalFilter asOf) {
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
        } else if (result.needsUserInput()) {
            var ask = result.askUser().orElseThrow();
            json.append("\"status\":\"needs_input\"")
                .append(",\"reason\":\"").append(ask.reason().name().toLowerCase(Locale.ROOT)).append('"')
                .append(",\"question\":").append(quoted(ask.question()))
                .append(",\"candidates\":[");
            for (int i = 0; i < ask.candidates().size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append(quoted(ask.candidates().get(i)));
            }
            json.append(']');
        } else {
            json.append("\"status\":\"declined\"");
            result.declined().ifPresent(outcome -> json.append(",\"reason\":\"")
                .append(reasonOf(outcome)).append('"'));
        }

        json.append(",\"plan\":").append(result.plan()
            .map(plan -> quoted(plan.shape().getClass().getSimpleName().toLowerCase(Locale.ROOT)))
            .orElse("null"));
        // Only when the caller named one. Echoing the instant a node happened to answer at
        // when nobody asked for one would read as a promise that the value was honoured.
        if (asOf != null) {
            json.append(",\"as_of\":").append(quoted(textOf(asOf)));
        }
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
        json.append(",\"total\":")
            .append(JsonText.number(trace.elapsedNanos() / 1_000_000.0, 3)).append('}');

        return json.append('}').toString();
    }

    private String reasonOf(io.excalibur.retrieval.abstain.RetrievalOutcome outcome) {
        return switch (outcome) {
            case io.excalibur.retrieval.abstain.RetrievalOutcome.NoResults none ->
                none.reason().name().toLowerCase(Locale.ROOT);
            case io.excalibur.retrieval.abstain.RetrievalOutcome.Ambiguous ignored -> "ambiguous";
            case io.excalibur.retrieval.abstain.RetrievalOutcome.Results ignored -> "answered";
        };
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        ResponseBody.send(exchange, status, bytes);
    }
}
