package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.core.text.SourceScope;
import com.eonmux.excalibur.ingest.source.Source;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The {@code source_id} rule, stated where requests arrive.
 *
 * <p>{@link Source#MAX_ID_LENGTH} is the bound, and both crawler implementations enforce it in
 * their constructors - which is where an embedded caller meets it, and the wrong place for an HTTP
 * one to. A registration whose id is too long would otherwise reach a crawler constructor, and this
 * endpoint answers a failed construction with {@code Invalid source configuration}: deliberately
 * opaque, because construction failures can name paths on this node. The caller learns nothing
 * about which field it got wrong. Checking here turns that into a message naming the field and the
 * bound, and does it before any allow-list decision, so the answer does not depend on the
 * target.</p>
 *
 * <p>The four routes that only look a source up check it too. Nothing is registered under an id
 * this long, so the request cannot succeed; refusing it says why, where a 404 would say only that
 * it was not found - and would echo the whole of the id back to say it.</p>
 *
 * <p>The list of sources a search is confined to is checked here too, by {@link #scope}, for
 * {@code source_ids} on {@code POST /v1/query} and for an embedded node's scoped query alike: a
 * bound one entry holds and the other does not is a request one of them answers and the other
 * refuses.</p>
 */
public final class SourceIds {

    private SourceIds() {
    }

    /**
     * Says why a source id is not acceptable, or null when it is.
     *
     * <p>A null id is not this method's business: each route words its own message for an id that
     * was never supplied.</p>
     *
     * @param sourceId The id as the request supplied it, or null if it did not
     * @param field    The field to quote back, spelled as the route's other messages spell it
     * @return The reason, ready to send to the caller, or null if there is none
     */
    static String violation(String sourceId, String field) {
        if (sourceId != null && sourceId.length() > Source.MAX_ID_LENGTH) {
            return "'" + field + "' must be at most " + Source.MAX_ID_LENGTH
                + " characters: " + sourceId.length();
        }
        return null;
    }

    /**
     * Checks the sources a search names, and builds the scope confining it to them.
     *
     * <p>Every refusal names the field and what is wrong with it: no source at all - a caller
     * that built the list from a filter matching nothing asked for nothing, and answering from
     * the whole corpus would hand it what it filtered out - more than
     * {@link SourceScope#MAX_SOURCES}, or an id that is null, blank or longer than
     * {@link Source#MAX_ID_LENGTH}. An id no source is registered under is not refused; it
     * scopes to nothing.</p>
     *
     * @param sourceIds The ids as the caller supplied them; duplicates are collapsed
     * @param field     The field or parameter to quote back
     * @return The scope over the named sources
     * @throws NullPointerException     if sourceIds is null
     * @throws IllegalArgumentException if the list is empty or too long, or names an id that is
     *                                  null, blank or too long
     */
    public static SourceScope scope(Collection<String> sourceIds, String field) {
        Objects.requireNonNull(sourceIds, "'" + field + "' must not be null");
        if (sourceIds.isEmpty()) {
            throw new IllegalArgumentException("'" + field + "' must name at least one source");
        }
        // Each named source is a prefix every candidate is compared against; SourceScope.of
        // holds the same bound, and this names the field in the message.
        if (sourceIds.size() > SourceScope.MAX_SOURCES) {
            throw new IllegalArgumentException("'" + field + "' must name at most "
                + SourceScope.MAX_SOURCES + " sources: " + sourceIds.size());
        }
        List<String> named = new ArrayList<>(sourceIds.size());
        for (String sourceId : sourceIds) {
            if (sourceId == null) {
                throw new IllegalArgumentException(
                    "'" + field + "' must not contain a null source id");
            }
            if (sourceId.isBlank()) {
                throw new IllegalArgumentException(
                    "'" + field + "' must not contain a blank source id");
            }
            String violation = violation(sourceId, field);
            if (violation != null) {
                throw new IllegalArgumentException(violation);
            }
            named.add(sourceId);
        }
        return SourceScope.of(named);
    }
}
