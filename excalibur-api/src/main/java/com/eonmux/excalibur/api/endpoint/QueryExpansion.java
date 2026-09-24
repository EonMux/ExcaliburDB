package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.retrieval.expand.Expansion;
import com.eonmux.excalibur.retrieval.expand.SearchFormulation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * What the query was widened into before anything was searched for it.
 *
 * <h2>Why a caller needs this</h2>
 * <p>On a node that expands queries, the search that ran is not the search that was typed. A
 * question about a service dying when the machine runs out of memory is answered by documents
 * saying {@code OOM killer} and {@code ENOMEM}, and a caller seeing those results with no idea
 * the query was widened has no way to tell a good expansion from a wrong one - or to see that
 * the reason its query returned nothing is that the expansion went somewhere else entirely.</p>
 *
 * <h2>Why it is reported for the answer, not for each result</h2>
 * <p>The query is widened once, before a single document is looked at. Repeating the list on
 * every result would say it happened per result, which is both larger and false.</p>
 *
 * <h2>Four states, not two</h2>
 * <p>"Not widened", "could not be widened" and "cannot say" are different facts and are named
 * differently. A node whose expander adds nothing did not widen the query; a node whose model
 * failed, was too slow or answered with nothing usable meant to and could not, and searched the
 * query as typed; a node whose lexical half never ran, or that wires no expander, cannot report
 * what an expander would have done. An empty list under any of them would read as the first.
 * The status is what keeps a caller from inferring one from another.</p>
 *
 * <h2>Read from the answer, never generated again</h2>
 * <p>The report is built from the expansion the search carried with it, which a cached answer
 * keeps too. Expansion is a generation from a chat model: asking again to describe it would
 * bill the operator for a second call, on a replayed answer for a call the replay existed to
 * save, and could describe terms the search never used.</p>
 *
 * @param status    {@link #APPLIED}, {@link #NOT_APPLIED}, {@link #FELL_BACK} or
 *                  {@link #UNREPORTED}
 * @param terms     The terms the query was widened with, the typed query itself excluded
 * @param truncated Whether terms were left out to meet {@link #MAX_TERMS}
 */
public record QueryExpansion(String status, List<Term> terms, boolean truncated) {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryExpansion.class);

    /** The query was widened, and {@code terms} is what with. */
    public static final String APPLIED = "applied";

    /** The query was searched as typed: this node widens nothing, or widened nothing here. */
    public static final String NOT_APPLIED = "not_applied";

    /**
     * The node widens queries and could not widen this one - its model failed, was too slow, or
     * answered with nothing usable - so the query was searched as typed.
     */
    public static final String FELL_BACK = "fell_back";

    /** This answer cannot say: no expander was wired, or no signal that uses one ran. */
    public static final String UNREPORTED = "unreported";

    /**
     * Most expansion terms one answer reports.
     *
     * <p>An expander's own cap is a node setting rather than a request one, so without a bound
     * here the size of the block is decided by whoever configured the model and not by whoever
     * is serving the response. Well above what any sane expander emits - the built-in one stops
     * at eight - so reaching it means something is wrong, which is why it is logged rather than
     * passed over.</p>
     */
    public static final int MAX_TERMS = 32;

    /** An answer that cannot say what the query was widened with. */
    public static final QueryExpansion UNKNOWN =
        new QueryExpansion(UNREPORTED, List.of(), false);

    /**
     * Copies the terms so a report cannot change after the answer claims it.
     */
    public QueryExpansion {
        terms = List.copyOf(terms);
    }

    /**
     * One term the query was widened with.
     *
     * @param text   The search term
     * @param weight How strongly a match on it counts, relative to the typed query's 1.0
     */
    public record Term(String text, double weight) {
    }

    /**
     * What one query was widened into, as the search that answered it used it.
     *
     * @param used  The expansion the lexical search carried, or null when no search that widens
     *              queries ran for this answer or its service wires no expander
     * @param query The query text, as validated
     * @return The report, never null
     */
    public static QueryExpansion of(Expansion used, String query) {
        if (used == null || query == null) {
            return UNKNOWN;
        }
        if (used.fellBack()) {
            return new QueryExpansion(FELL_BACK, List.of(), false);
        }
        return report(used.formulations(), query);
    }

    /**
     * Turns the expander's formulations into the terms that were added to the query.
     *
     * @param formulations What the expander produced, the typed query among them
     * @param query        The query text, as validated
     * @return The report
     */
    private static QueryExpansion report(List<SearchFormulation> formulations, String query) {
        if (formulations == null || formulations.isEmpty()) {
            return new QueryExpansion(NOT_APPLIED, List.of(), false);
        }
        String typed = query.trim();
        List<Term> added = new ArrayList<>();
        boolean truncated = false;
        for (SearchFormulation formulation : formulations) {
            if (typed.equalsIgnoreCase(formulation.text())) {
                continue;
            }
            if (added.size() == MAX_TERMS) {
                truncated = true;
                LOGGER.warn("The expander produced more than {} terms for one query; the "
                    + "answer reports the first {}", MAX_TERMS, MAX_TERMS);
                break;
            }
            added.add(new Term(formulation.text(), formulation.weight()));
        }
        return new QueryExpansion(added.isEmpty() ? NOT_APPLIED : APPLIED, added, truncated);
    }
}
