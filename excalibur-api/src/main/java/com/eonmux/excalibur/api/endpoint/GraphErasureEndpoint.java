package com.eonmux.excalibur.api.endpoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.eonmux.excalibur.api.bootstrap.GraphErasureService;
import com.eonmux.excalibur.api.bootstrap.GraphRagFactory;
import com.eonmux.excalibur.api.bootstrap.GraphTenant;
import com.eonmux.excalibur.api.bootstrap.GraphTenants;
import com.eonmux.excalibur.api.bootstrap.NamespaceRefusedException;
import com.eonmux.excalibur.api.protocol.ErrorCode;
import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.api.protocol.RequestBody;
import com.eonmux.excalibur.api.protocol.ResponseBody;
import com.eonmux.excalibur.graph.dictionary.LabelDictionary;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * {@code POST /v1/graph/erasure} - accepts an obligation to remove data from the graph.
 *
 * <p>The request is recorded durably before this answers. An erasure obligation outlives the
 * process that accepted it: a client told "accepted" and then losing the request to a restart is
 * the one outcome that must not happen, because nothing afterwards would notice the data was still
 * there.</p>
 *
 * <p>Accepting is not removing. The edges are rewritten out of the store by a compaction, which the
 * policy schedules against how much has accumulated and how long the oldest request has waited -
 * rewriting the whole store per request would make erasure a denial-of-service. The response says
 * which of the two happened.</p>
 *
 * <h3>Request</h3>
 * <pre>
 * {"scope": "entity",   "entity_id": 42}
 * {"scope": "entity",   "entity_name": "Ada Lovelace"}
 * {"scope": "source",   "source_type": "DOCUMENT", "source_id": 1234}
 * {"scope": "extractor","version": 7}
 * </pre>
 *
 * <h3>Naming a person rather than an integer</h3>
 * <p>An entity id is an internal number. A graph answer carries names and prose, not ids; the
 * node hands one out only where a name alone cannot say which entity is meant - a
 * {@code needs_input} candidate for a shared name, and this endpoint's own 409. Requiring an id
 * therefore meant "erase everything about this person" could ordinarily only be asked by someone
 * who had read it out of one of those. {@code entity_name} closes that: it names the person the
 * way the request that created the obligation names them.
 *
 * <p>A name is resolved against a namespace that already exists, never one created to look in.
 * A namespace that does not exist holds nobody, so the answer is the same 404 an existing
 * namespace gives, and the header naming it spends no directory and no open slot.
 *
 * <p>Resolved by exact match, ignoring case and surrounding space, against the entity names this
 * namespace knows - the served entity dictionary and the staged labels it is built from, which
 * between them cover a person whose facts are built and one whose facts are only staged.
 * Deliberately <em>not</em> against the {@code labels} Lucene index, which is the other obvious
 * place to look: that index exists to rank near matches, and its whole value at query time - that
 * "Lovelace" finds "Ada Lovelace" - is a defect here. An erasure is irreversible, so the only
 * acceptable resolution is one that cannot return somebody whose name merely resembles the one
 * given.
 *
 * <p>A name no entity has is a 404, and a name two entities share is a 409 listing their ids,
 * because guessing which of two people to erase is not a decision this endpoint may take.
 *
 * <h3>An id must name an entity</h3>
 * <p>An {@code entity_id} is accepted only when something in the namespace holds it: the staged
 * labels, or a graph the namespace serves. Anything else is a 404 and nothing is recorded. The
 * request records the id and nothing else, and ids are handed out in order, so an obligation
 * accepted for an id nobody holds yet would erase whichever entity is given it next. Like a name,
 * an id is looked for only in a namespace that already exists, and a namespace that does not is
 * answered with the same 404. How the check is ordered against a label being assigned and a
 * graph being replaced is {@link GraphErasureService#submit}'s to say.
 *
 * <p>The scopes that select by provenance - a source, an extractor version - name no entity.
 * They are recorded whatever the namespace holds, and create it, because recording an obligation
 * is one of the things that bring a namespace into being.
 *
 * <p>Optionally {@code "compact": true} to run the compaction immediately rather than leaving it to
 * the policy - for a caller under a deadline that must be able to say the data is gone.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; the service it calls serialises compactions.</p>
 *
 * @author ExcaliburDB
 */
public class GraphErasureEndpoint implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphErasureEndpoint.class);

    private final GraphTenants tenants;

    /**
     * Creates the endpoint.
     *
     * @param tenants The namespaces whose graphs can be erased from
     */
    public GraphErasureEndpoint(GraphTenants tenants) {
        this.tenants = Objects.requireNonNull(tenants, "tenants cannot be null");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            ResponseBody.sendJson(exchange, 405, "{\"error\": \"Method not allowed; use POST\"}");
            return;
        }

        String body;
        try {
            body = RequestBody.read(exchange);
        } catch (RequestBody.TooLargeException e) {
            ResponseBody.sendJson(exchange, 413, "{\"error\": \"Request body too large\"}");
            return;
        }

        NamespaceId namespace;
        Requested requested;
        boolean compactNow;
        try {
            namespace = GraphTenants.namespaceOf(
                exchange.getRequestHeaders().getFirst(GraphTenants.NAMESPACE_HEADER));
        } catch (IllegalArgumentException e) {
            ResponseBody.sendError(exchange, 400, ErrorCode.INVALID_NAMESPACE, e.getMessage());
            return;
        }
        try {
            requested = scopeFrom(body);
            // Every field of the request is read before any of it is acted on. Reading this one
            // after the obligation was submitted meant a body with a malformed "compact" was
            // durably recorded and then answered 500, so the caller retried and recorded it
            // twice.
            compactNow = JsonText.booleanValue(body, "compact", false);
        } catch (IllegalArgumentException e) {
            ResponseBody.sendJson(exchange, 400,
                "{\"error\": \"" + JsonText.escape(e.getMessage()) + "\"}");
            return;
        }

        try {
            Optional<NamespaceRegistry.Lease<GraphTenant>> borrowed = leaseFor(namespace, requested);
            if (borrowed.isEmpty()) {
                // A namespace that was never created holds nobody, so the name or id resolves to
                // no one - answered exactly as one missing from a namespace that does exist, so
                // the reply cannot be used to learn which namespaces other tenants have.
                sendNotFound(exchange, requested);
                return;
            }
            accept(exchange, namespace, requested, compactNow, borrowed.get());
        } catch (GraphErasureService.UnknownEntityException e) {
            sendNotFound(exchange, requested);
        } catch (NamespaceRefusedException e) {
            // A source or extractor scope creates the namespace it is recorded in, so it is
            // refused for the same reasons as any other creation, before anything is recorded.
            ResponseBody.sendError(exchange, e.reason().statusCode(), e.reason().code(),
                e.getMessage());
        } catch (IOException | RuntimeException e) {
            // RuntimeException is not defensive padding: a namespace that cannot be opened
            // arrives here as an UncheckedIOException from the registry, and catching only
            // IOException would let it escape the handler with no response sent at all.
            //
            // Logged with detail, answered without it: the message names the path the node
            // failed on, which a client has no business seeing.
            LOGGER.error("Erasure request failed", e);
            ResponseBody.sendJson(exchange, 500, "{\"error\": \"Internal Server Error\"}");
        }
    }

    /**
     * Borrows the namespace a request is about, creating it only when the request will record an
     * obligation there whatever the namespace holds.
     *
     * <p>A source or extractor scope is always recorded, and recording one is what brings a
     * namespace into being. An entity is different, by name or by id: it has to be found first,
     * and a namespace that does not exist cannot contain anybody, so creating it to find that out
     * would write a directory and claim an open slot - evicting a real tenant - for a request
     * that ends in a 404 with nothing recorded. The namespace arrives in a header, so that would
     * be any caller spending all three on any name or id it chose.</p>
     *
     * @param namespace The namespace the header named
     * @param requested What the body asked for
     * @return The lease, or empty when an entity was named in a namespace that does not exist
     */
    private Optional<NamespaceRegistry.Lease<GraphTenant>> leaseFor(NamespaceId namespace,
                                                                    Requested requested) {
        if (requested.scope() == null || requested.scope() instanceof ErasureScope.Entity) {
            return tenants.borrowIfPresent(namespace);
        }
        return Optional.of(tenants.borrow(namespace));
    }

    /**
     * Resolves, records and answers one request against a namespace already borrowed.
     *
     * @param exchange   The request, answered here
     * @param namespace  The namespace the lease is over
     * @param requested  What the body asked for
     * @param compactNow Whether the caller asked for the compaction to run now
     * @param borrowed   The lease, closed here
     * @throws IOException if the names cannot be read or the reply cannot be written
     */
    private void accept(HttpExchange exchange, NamespaceId namespace, Requested requested,
                        boolean compactNow, NamespaceRegistry.Lease<GraphTenant> borrowed)
            throws IOException {
        try (NamespaceRegistry.Lease<GraphTenant> lease = borrowed) {
            ErasureScope scope = requested.scope();
            if (scope == null) {
                // Resolved here rather than at parse time because it takes this namespace's
                // names, and which namespace that is comes from the header.
                scope = resolved(exchange, lease.resource(), requested.entityName());
                if (scope == null) {
                    return;
                }
            }
            GraphErasureService erasureService = lease.resource().erasureService();
            // Refuses an entity id nothing in the namespace holds, recording nothing; the
            // handler answers that 404.
            long id = erasureService.submit(scope);

            // Past here the obligation is durable, so the request has been accepted whatever
            // happens next, and a failure below is reported in the answer rather than raised as
            // a 500. A 500 would be read as "nothing was recorded", the caller would retry, and
            // the retry would record a second obligation covering data the first already covers.
            // This is the rule the "compact" field above already follows, applied to the half of
            // the operation that does the work: the obligation stands, the sweep will come back
            // to it, and the response says so.
            GraphErasureService.ErasureOutcome outcome;
            try {
                outcome = compactNow
                    ? erasureService.compactNow()
                    : erasureService.compactIfDue();
                if (outcome.ran()) {
                    // The compaction reopened the rewritten store into this tenant's holder
                    // already. Only the accounting is left: the store it now maps is not the one
                    // the budget was told about.
                    tenants.recost(namespace);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.error("erasure request {} was recorded durably, but the compaction that "
                    + "followed it failed; the obligation stands and the erasure sweep will "
                    + "retry it", id, e);
                outcome = new GraphErasureService.ErasureOutcome(false, 0, 0, 1,
                    "the obligation was recorded; the compaction that followed it failed and "
                        + "will be retried by the next sweep");
            }

            ResponseBody.sendJson(exchange, 202,
                "{"
                    + "\"request_id\": " + id + ", "
                    + "\"accepted\": true, "
                    + "\"compacted\": " + outcome.ran() + ", "
                    + "\"edges_removed\": " + outcome.edgesRemoved() + ", "
                    + "\"requests_satisfied\": " + outcome.requestsSatisfied() + ", "
                    // Reported beside the satisfied count, never folded into it: a request this
                    // could not fully honour stays outstanding, and a caller under an obligation
                    // has to be able to see that rather than read the silence as completion.
                    + "\"requests_withheld\": " + outcome.requestsWithheld() + ", "
                    + "\"outstanding\": " + erasureService.outstandingCount() + ", "
                    + "\"namespace\": \"" + JsonText.escape(namespace.value()) + "\", "
                    + "\"detail\": \"" + JsonText.escape(outcome.reason()) + "\""
                    + "}");
        }
    }

    /**
     * What a request asked for: a scope, or a name still to be resolved against a namespace.
     *
     * <p>Two fields rather than a resolved scope, because resolving a name needs the namespace's
     * entity names and the namespace is not known until the header has been read. Exactly one is
     * ever set.
     *
     * @param scope      The scope, or null when a name has to be resolved first
     * @param entityName The name to resolve, or null when the scope is already known
     */
    private record Requested(ErasureScope scope, String entityName) {
    }

    /**
     * Reads the scope a request names.
     *
     * @param body The request body
     * @return The scope, or the name that stands in for one
     * @throws IllegalArgumentException if the scope is missing, unknown, or missing its field
     */
    private static Requested scopeFrom(String body) {
        String scope = JsonText.string(body, "scope");
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException(
                "'scope' is required and must be one of: entity, source, extractor");
        }

        return switch (scope.trim().toLowerCase(Locale.ROOT)) {
            case "entity" -> entityScopeFrom(body);
            case "source" -> new Requested(new ErasureScope.Source(
                sourceTypeFrom(body),
                requireLong(body, "source_id", "scope 'source' requires 'source_id'")), null);
            case "extractor" ->
                new Requested(new ErasureScope.ExtractorVersion(extractorVersionFrom(body)),
                    null);
            default -> throw new IllegalArgumentException(
                "unknown scope '" + scope + "'; expected entity, source or extractor");
        };
    }

    /**
     * Reads which entity an entity-scoped request is about, by id or by name.
     *
     * <p>Both at once is refused rather than reconciled. They can disagree, and the way to find
     * out which the caller meant is not to pick one and erase whoever it names.
     *
     * @param body The request body
     * @return The scope, or the name to resolve
     * @throws IllegalArgumentException if neither is given, or both are
     */
    private static Requested entityScopeFrom(String body) {
        String name = JsonText.string(body, "entity_name");
        boolean named = name != null && !name.isBlank();
        long id = JsonText.longValue(body, "entity_id", Long.MIN_VALUE);
        if (named && id != Long.MIN_VALUE) {
            throw new IllegalArgumentException(
                "scope 'entity' takes 'entity_id' or 'entity_name', not both: they can name "
                    + "different entities and an erasure is not reversible");
        }
        if (named) {
            return new Requested(null, name.trim());
        }
        if (id == Long.MIN_VALUE) {
            throw new IllegalArgumentException(
                "scope 'entity' requires 'entity_id' or 'entity_name'");
        }
        return new Requested(new ErasureScope.Entity(id), null);
    }

    /**
     * Turns a name into the one entity that has it, or answers the caller and returns null.
     *
     * @param exchange The request, answered here when the name resolves to none or to several
     * @param tenant   The namespace's graph
     * @param name     The name to resolve
     * @return The scope, or null when this has already replied
     * @throws IOException if the names cannot be read or the reply cannot be written
     */
    private static ErasureScope resolved(HttpExchange exchange, GraphTenant tenant, String name)
            throws IOException {
        SortedSet<Long> ids = idsNamed(tenant, name);
        if (ids.isEmpty()) {
            sendNotNamed(exchange, name);
            return null;
        }
        if (ids.size() > 1) {
            // Listing the ids is what makes this answerable: the caller repeats the request with
            // the entity_id of the one they meant. Withholding them would leave a person who
            // shares a name with somebody unerasable through this endpoint.
            ResponseBody.sendJson(exchange, 409,
                "{\"error\": \"" + ids.size() + " entities are named \\\""
                    + JsonText.escape(name) + "\\\"; repeat the request with the 'entity_id' "
                    + "of the one to erase\", \"code\": \""
                    + ErrorCode.AMBIGUOUS_ENTITY.wireName() + "\", \"candidates\": " + ids + "}");
            return null;
        }
        return new ErasureScope.Entity(ids.first());
    }

    /**
     * Answers that no entity in the namespace is the one the request named, by name or by id.
     *
     * @param exchange  The request to answer
     * @param requested What the body asked for: a name, or an entity scope
     * @throws IOException if the reply cannot be written
     */
    private static void sendNotFound(HttpExchange exchange, Requested requested)
            throws IOException {
        if (requested.scope() instanceof ErasureScope.Entity entity) {
            ResponseBody.sendError(exchange, 404, ErrorCode.ENTITY_NOT_FOUND,
                new GraphErasureService.UnknownEntityException(entity.entityId()).getMessage());
            return;
        }
        sendNotNamed(exchange, requested.entityName());
    }

    /**
     * Answers that no entity in the namespace has the name.
     *
     * @param exchange The request to answer
     * @param name     The name that resolved to nobody
     * @throws IOException if the reply cannot be written
     */
    private static void sendNotNamed(HttpExchange exchange, String name) throws IOException {
        ResponseBody.sendError(exchange, 404, ErrorCode.ENTITY_NOT_FOUND,
            "no entity in this namespace is named \"" + name
                + "\"; erasure resolves a name exactly rather than approximately");
    }

    /**
     * Every entity id this namespace calls {@code name}.
     *
     * <p>Both the served dictionary and the staged labels, because they answer different halves
     * of the question. The dictionary names what the node is serving now; the staged labels name
     * what it will serve after the next build, which includes a person whose facts have been
     * ingested and not yet built - and an obligation about them is exactly as real.
     *
     * @param tenant The namespace's graph
     * @param name   The name, already trimmed
     * @return The ids, in order, possibly empty
     * @throws IOException if the staged labels or the dictionary cannot be read
     */
    private static SortedSet<Long> idsNamed(GraphTenant tenant, String name) throws IOException {
        SortedSet<Long> ids = new TreeSet<>();
        List<String> staged = tenant.staging().entityLabels();
        for (int id = 0; id < staged.size(); id++) {
            if (name.equalsIgnoreCase(staged.get(id))) {
                ids.add((long) id);
            }
        }
        try (LabelDictionary dictionary = LabelDictionary.openIfPresent(
                tenant.directory(), GraphRagFactory.ENTITY_DICTIONARY, "E")) {
            if (dictionary != null) {
                dictionary.forEach((id, served) -> {
                    if (name.equalsIgnoreCase(served)) {
                        ids.add(id);
                    }
                });
            }
        }
        return ids;
    }

    /**
     * Reads the extractor version an extractor-scoped request names.
     *
     * <p>The store keeps the version in 32 bits, so a value outside that range has to be
     * refused rather than narrowed. Narrowing is not a cosmetic loss here: {@code 2^32 + 1}
     * becomes {@code 1}, and the request would be accepted as an obligation to erase
     * everything version 1 ever extracted - irreversibly, and reported to the caller as
     * {@code "accepted": true}.</p>
     *
     * @param body The request body
     * @return The version
     * @throws IllegalArgumentException if it is missing or outside the range the store holds
     */
    private static int extractorVersionFrom(String body) {
        long version = requireLong(body, "version", "scope 'extractor' requires 'version'");
        if (version < 0 || version > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                "'version' must be between 0 and " + Integer.MAX_VALUE + ", got " + version);
        }
        return (int) version;
    }

    /**
     * Reads the source type a source-scoped request names.
     *
     * @param body The request body
     * @return The source type
     * @throws IllegalArgumentException if it is missing or not a known type
     */
    private static SourceType sourceTypeFrom(String body) {
        String value = JsonText.string(body, "source_type");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("scope 'source' requires 'source_type'");
        }
        try {
            return SourceType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "unknown source_type '" + value + "'; expected one of UNKNOWN, KNOWLEDGE_BASE, "
                    + "DOCUMENT, CONVERSATION, USER_ASSERTED, INFERRED");
        }
    }

    /**
     * Reads a required numeric field.
     *
     * @param body    The request body
     * @param field   The field name
     * @param message What to say when it is absent
     * @return The value
     * @throws IllegalArgumentException if the field is absent
     */
    private static long requireLong(String body, String field, String message) {
        // The sentinel is what says "absent": the parser reports a default rather than null, and
        // a negative id is not a value any of these fields can legitimately take.
        long value = JsonText.longValue(body, field, Long.MIN_VALUE);
        if (value == Long.MIN_VALUE) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
