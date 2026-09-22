package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.ingest.source.Source;

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
 */
final class SourceIds {

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
}
