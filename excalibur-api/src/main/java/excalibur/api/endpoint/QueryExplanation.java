package excalibur.api.endpoint;

import java.util.List;

/**
 * Everything one answer says about why it is the answer.
 *
 * <p>Two levels, because the two facts belong to different things. The expansion happened once,
 * to the query, before any document was looked at. The signals and matched terms belong to
 * individual results. Holding them in one object is what keeps a renderer from having to be
 * told twice whether the request asked to be explained.</p>
 *
 * <p>The per-result list runs parallel to the results of the page it explains, in the same
 * order. That is checked where it is built rather than trusted: an explanation shifted by one
 * is worse than none, because it looks right.</p>
 *
 * @param expansion What the query was widened with
 * @param results   One explanation per result of the page, in the page's order
 */
public record QueryExplanation(QueryExpansion expansion, List<ResultExplanation> results) {

    /**
     * Copies the list so an explanation cannot change after the answer claims it.
     */
    public QueryExplanation {
        if (expansion == null) {
            throw new IllegalArgumentException("expansion must not be null");
        }
        results = List.copyOf(results);
    }

    /**
     * The explanation of one result of the page.
     *
     * @param index Position in the page, counting from zero
     * @return The explanation, or null when the page is longer than what was explained
     */
    public ResultExplanation at(int index) {
        return index >= 0 && index < results.size() ? results.get(index) : null;
    }
}
