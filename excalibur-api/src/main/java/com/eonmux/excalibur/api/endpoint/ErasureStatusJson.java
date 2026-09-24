package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.graph.erasure.ErasureScope;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * How the status routes spell one graph erasure request.
 *
 * <p>Shared by {@code GET /v1/graph/erasure/{request_id}} and {@code GET /v1/erasure/{id}}, which
 * report the same obligations - the second as the graph half of a document erasure - and must
 * not describe one differently depending on which route was asked.</p>
 *
 * <p>Only what the log holds is written: a scope is an entity id, a source's type and numeric id,
 * or an extractor version. The log never holds a name, so nothing written here can carry one.</p>
 */
final class ErasureStatusJson {

    private ErasureStatusJson() {
    }

    /**
     * The state name a response uses.
     *
     * @param state The log's state
     * @return {@code pending} or {@code discharged}
     */
    static String stateName(ErasureLog.State state) {
        return state.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Appends the fields of one request, without the enclosing braces.
     *
     * @param json   Where to write
     * @param status The request's status
     * @return {@code json}
     */
    static StringBuilder appendFields(StringBuilder json, ErasureLog.RequestStatus status) {
        json.append("\"request_id\": ").append(status.id())
            .append(", \"state\": \"").append(stateName(status.state())).append('"');
        appendScope(json, status.scope());
        json.append(", \"requested_at_ms\": ").append(millis(status.requestedAt()))
            .append(", \"discharged_at_ms\": ").append(millis(status.completedAt()))
            // Says whether the nulls above are "not yet" or "no longer known", which the
            // values alone cannot: a discharged request past the retained window has neither
            // timestamp, exactly as a pending one lacks the second.
            .append(", \"detail_retained\": ").append(status.scope().isPresent());
        return json;
    }

    /**
     * Appends the scope fields, or a null scope when the log no longer retains it.
     *
     * @param json  Where to write
     * @param scope The scope, when retained
     */
    private static void appendScope(StringBuilder json, Optional<ErasureScope> scope) {
        if (scope.isEmpty()) {
            json.append(", \"scope\": null");
            return;
        }
        switch (scope.get()) {
            case ErasureScope.Entity entity -> json.append(", \"scope\": \"entity\"")
                .append(", \"entity_id\": ").append(entity.entityId());
            case ErasureScope.Source source -> json.append(", \"scope\": \"source\"")
                .append(", \"source_type\": \"").append(source.sourceType().name()).append('"')
                .append(", \"source_id\": ").append(source.sourceId());
            case ErasureScope.ExtractorVersion version -> json
                .append(", \"scope\": \"extractor\"")
                .append(", \"version\": ").append(version.version());
        }
    }

    private static String millis(Optional<Instant> at) {
        return at.map(instant -> Long.toString(instant.toEpochMilli())).orElse("null");
    }
}
