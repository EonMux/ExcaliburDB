package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.protocol.JsonText;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which request fields one answer accepted and did not act on.
 *
 * <h2>Why every answer says</h2>
 * <p>{@code POST /v1/query} validates every field it is sent, whichever route will answer, and
 * the route is chosen by what the node holds and by what failed on the way - not by the caller.
 * A field that is accepted, validated and then has no effect is the answer that misleads: a
 * {@code threshold} a BM25 answer never applied reads exactly like one that cut nothing. So
 * every answer names what it left unacted on, on every route and on the empty timed-out and
 * cancelled answers too, and an empty list is a statement that everything sent was
 * honoured.</p>
 *
 * <h2>Only what was sent</h2>
 * <p>A field the caller did not send is a default the caller did not choose, and naming it
 * would report a decision nobody made. {@code enable_llm_scoring} is named only when it asked
 * for scoring; sent as false it asked for none, and none is what it got.</p>
 *
 * <h2>What each route cannot act on</h2>
 * <ul>
 *   <li>{@code threshold} and {@code include_partial_segments} steer vector search. An answer
 *       from BM25 alone applied neither: its scores are reciprocal-rank sums, not
 *       similarities, so a floor tuned against cosine would cut an arbitrary part of the
 *       ranking, and the lexical index has no partial segments to include. On a fused answer
 *       both are applied, to the vector signal, before fusion.</li>
 *   <li>{@code enable_llm_scoring} and {@code llm_threshold} are named on any route where no
 *       scoring was planned - a node with no chat model, or with no chunk text for one to
 *       read. Scoring that was planned and then failed or ran out of time is not named: it is
 *       reported as degraded coverage, because the field was acted on and the stage broke.</li>
 *   <li>{@code oversampling_factor} widens each segment's HNSW search on every answer vector
 *       search contributed to, so it is only ever unacted on by BM25 alone - and there only when
 *       no scoring was planned, since scoring is the other thing it sizes.</li>
 * </ul>
 *
 * @author ExcaliburDB
 */
final class IgnoredFields {

    private IgnoredFields() {
    }

    /**
     * The fields one answer left unacted on.
     *
     * @param requestBody    The raw request, read for which fields the caller actually sent
     * @param parsed         The validated request
     * @param lexicalOnly    Whether BM25 alone produced the answer
     * @param scoringPlanned Whether relevancy scoring was going to run for this answer
     * @return The entries for {@code ignored_fields}, in a stable order
     */
    static List<String> of(String requestBody, QueryRequestParser.ParsedQuery parsed,
                           boolean lexicalOnly, boolean scoringPlanned) {
        List<String> ignored = new ArrayList<>(5);
        if (lexicalOnly) {
            addIfSent(ignored, requestBody, "threshold");
            addIfSent(ignored, requestBody, "include_partial_segments");
        }
        if (!scoringPlanned) {
            if (parsed.enableLlmScoring()) {
                addIfSent(ignored, requestBody, "enable_llm_scoring");
            }
            addIfSent(ignored, requestBody, "llm_threshold");
            if (lexicalOnly) {
                addIfSent(ignored, requestBody, "oversampling_factor");
            }
        }
        return List.copyOf(ignored);
    }

    private static void addIfSent(List<String> ignored, String requestBody, String field) {
        if (JsonText.has(requestBody, field)) {
            ignored.add(field);
        }
    }
}
