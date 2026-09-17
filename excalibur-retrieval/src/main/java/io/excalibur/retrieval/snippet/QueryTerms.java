package io.excalibur.retrieval.snippet;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The words of a query, as a snippet and an explanation have to see them.
 *
 * <h2>Why this exists rather than the index's analyzer</h2>
 * <p>The question these terms answer is not "what did the index match" but "what of the query
 * does this passage contain", and the passage is a string held by chunk storage rather than a
 * document the index will hand back. Asking the search engine to re-analyze text it is not
 * looking at would tie the answer to whichever engine happened to run - and a node answering
 * from vector search alone has no lexical analyzer at all, while still owing its caller an
 * explanation.</p>
 *
 * <p>So the rule here is deliberately simple and is the same rule on both sides: a term is a
 * maximal run of letters or digits, compared without case. No stemming and no stop-word list,
 * because both would report a term as unmatched that the caller can plainly see in the text -
 * a highlight that disagrees with the passage beside it is worse than no highlight.</p>
 *
 * <h2>The bound</h2>
 * <p>The number of terms is set by how long a query the caller types, and every term costs a
 * pass over every returned passage. It is therefore a caller-controlled size and is refused at
 * a ceiling rather than silently trimmed: a query explained against half of its own words
 * would report terms as absent from a passage that contains them.</p>
 */
public final class QueryTerms {

    /**
     * Most distinct terms one query may be explained or highlighted against.
     *
     * <p>Chosen against the shape of a search query rather than against a machine limit: past
     * a few dozen distinct words a query is prose rather than a search, and every term is a
     * scan of every returned passage. A caller past this is told, because the alternative is
     * an explanation that quietly covers some of what was asked.</p>
     */
    public static final int MAX_TERMS = 64;

    private QueryTerms() {
    }

    /**
     * The distinct terms of a query, lower-cased, in the order they were typed.
     *
     * @param query    The query text
     * @param maxTerms Most distinct terms allowed, past which the query is refused
     * @return The terms, never null and possibly empty for a query of punctuation alone
     * @throws IllegalArgumentException if the query holds more distinct terms than allowed
     */
    public static List<String> of(String query, int maxTerms) {
        if (maxTerms <= 0) {
            throw new IllegalArgumentException("maxTerms must be positive: " + maxTerms);
        }
        if (query == null || query.isEmpty()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        int start = -1;
        for (int i = 0; i <= query.length(); i++) {
            boolean word = i < query.length() && Character.isLetterOrDigit(query.charAt(i));
            if (word && start < 0) {
                start = i;
            } else if (!word && start >= 0) {
                terms.add(query.substring(start, i).toLowerCase(Locale.ROOT));
                start = -1;
            }
        }
        if (terms.size() > maxTerms) {
            throw new IllegalArgumentException("the query holds " + terms.size()
                + " distinct terms and at most " + maxTerms + " can be explained or "
                + "highlighted: shorten the query, or drop 'explain' and 'snippet'");
        }
        return List.copyOf(terms);
    }

    /**
     * Where each of the given terms occurs in a passage.
     *
     * <p>Whole terms only, by the same rule that produced them: a run of letters or digits in
     * the text is compared with the term as a whole, so {@code oom} does not report a match
     * inside {@code room}. Occurrences come back in the order they appear.</p>
     *
     * @param text     The passage to scan, or null
     * @param terms    The terms to look for, lower-cased
     * @param maxFound Most occurrences to report, past which the scan stops
     * @return The occurrences, never null
     * @throws IllegalArgumentException if maxFound is not positive
     */
    public static List<TermOccurrence> occurrences(String text, List<String> terms, int maxFound) {
        if (maxFound <= 0) {
            throw new IllegalArgumentException("maxFound must be positive: " + maxFound);
        }
        if (text == null || text.isEmpty() || terms == null || terms.isEmpty()) {
            return List.of();
        }
        Set<String> wanted = Set.copyOf(terms);
        List<TermOccurrence> found = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= text.length() && found.size() < maxFound; i++) {
            boolean word = i < text.length() && Character.isLetterOrDigit(text.charAt(i));
            if (word && start < 0) {
                start = i;
            } else if (!word && start >= 0) {
                String token = text.substring(start, i).toLowerCase(Locale.ROOT);
                if (wanted.contains(token)) {
                    found.add(new TermOccurrence(start, i - start, token));
                }
                start = -1;
            }
        }
        return List.copyOf(found);
    }
}
