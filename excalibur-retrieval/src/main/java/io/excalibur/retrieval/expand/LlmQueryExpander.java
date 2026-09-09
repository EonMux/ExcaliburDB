package io.excalibur.retrieval.expand;

import io.excalibur.llm.client.LLMHttpClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Generates search formulations with a chat model, and lets the model weight them.
 *
 * <h2>What the model is asked for</h2>
 * <p>Search terms, not an answer. The model never sees the corpus and is not asked to reason about
 * it; it is asked to translate one sentence into the vocabulary a document would actually use. That
 * is a short, constrained generation - a few dozen tokens - which is why this can sit on a query
 * path at all, and why it is affordable in a way that embedding every chunk of a corpus is not.</p>
 *
 * <h2>Why the model weights them</h2>
 * <p>Relevance of an expansion is a judgement about language, which is the thing the model is for.
 * A hand-tuned weight per expansion would be a constant standing in for that judgement, and it
 * would be wrong differently for every query. The weights are clamped below the user's own query,
 * which always counts most: an expansion is a guess about vocabulary and must not be able to
 * outvote the words actually typed.</p>
 *
 * <h2>Failure</h2>
 * <p>Every failure returns the original query alone. A model that is down, slow, or returns
 * something unparseable costs the expansion, never the search - which is the difference between
 * this being an optimisation and being a dependency.</p>
 */
public final class LlmQueryExpander implements QueryExpander {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmQueryExpander.class);

    /** Cap on formulations returned, the original included. */
    public static final int DEFAULT_MAX_FORMULATIONS = 8;

    /**
     * Weight below which a formulation is discarded rather than searched.
     *
     * <p>The model is asked how sure it is that a term appears in a matching document, and its
     * own answer is used as the filter. Measured on a real corpus, the tail of a generated list
     * is where the terms describing the document rather than appearing in it collect - and every
     * one of those is a clause that matches unrelated documents, so a weak expansion does not
     * merely fail to help, it dilutes the query it was added to.</p>
     */
    public static final double MIN_USEFUL_WEIGHT = 0.55;

    /** Longest query this will send to the model; longer ones are searched as typed. */
    public static final int MAX_QUERY_CHARS = 512;

    private static final String INSTRUCTION = """
        You expand a search query into the exact terms a matching document would contain.
        You are NOT answering the question.

        Output ONLY a JSON array of 5 to %d objects, nothing before or after it:
        [{"q":"term","w":0.9},{"q":"term","w":0.8}]

        - "q" must be words that appear VERBATIM inside a matching document.
        - "w" is 0.1 to 0.9: how sure you are those exact words appear in it.
        - Include acronyms, error codes, identifiers and domain synonyms.
        - NEVER output words describing the document rather than appearing in it. Terms like
          "plot summary", "literary analysis", "character development", "overview",
          "explanation", "research paper" or "documentation" are always wrong.
        - No prose, no markdown, no repeat of the user's own wording.
        Start your reply with [ and end with ].
        """;

    private final LLMHttpClient client;
    private final int maxFormulations;

    public LlmQueryExpander(LLMHttpClient client) {
        this(client, DEFAULT_MAX_FORMULATIONS);
    }

    public LlmQueryExpander(LLMHttpClient client, int maxFormulations) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        if (maxFormulations < 1) {
            throw new IllegalArgumentException("maxFormulations must be positive: " + maxFormulations);
        }
        this.maxFormulations = maxFormulations;
    }

    @Override
    public List<SearchFormulation> expand(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String trimmed = query.trim();
        List<SearchFormulation> out = new ArrayList<>();
        out.add(SearchFormulation.original(trimmed));

        if (trimmed.length() > MAX_QUERY_CHARS) {
            // A query this long is already specific. Expanding it is unlikely to add recall and
            // the model call is no longer short.
            return List.copyOf(out);
        }

        String response;
        try {
            response = client.completeWithCircuitBreaker(
                String.format(Locale.ROOT, INSTRUCTION, maxFormulations - 1), trimmed, null);
        } catch (RuntimeException e) {
            LOGGER.debug("Query expansion unavailable, searching the query as typed: {}",
                e.toString());
            return List.copyOf(out);
        }

        List<SearchFormulation> parsed = parse(response, trimmed);
        if (parsed.isEmpty()) {
            LOGGER.debug("Query expansion returned nothing usable; searching the query as typed");
            return List.copyOf(out);
        }
        out.addAll(parsed);
        return List.copyOf(out.subList(0, Math.min(out.size(), maxFormulations)));
    }

    /**
     * Reads the model's array, treating every field as untrusted.
     *
     * <p>Hand-parsed rather than bound to a schema because the input is a language model's best
     * effort at JSON: it may be fenced in markdown, truncated mid-array, or carry a weight outside
     * the range it was asked for. None of those is a reason to fail a search, so anything that
     * cannot be read is dropped and whatever is left is used.</p>
     */
    private List<SearchFormulation> parse(String response, String original) {
        List<SearchFormulation> found = new ArrayList<>();
        if (response == null || response.isBlank()) {
            return found;
        }
        // Deduplicated case-insensitively, and never re-emitting the user's own words: a
        // duplicate is a second vote for the same evidence, which quietly doubles its weight.
        Map<String, SearchFormulation> unique = new LinkedHashMap<>();
        String originalKey = original.toLowerCase(Locale.ROOT);

        int at = 0;
        while (found.size() < maxFormulations) {
            int qKey = response.indexOf("\"q\"", at);
            if (qKey < 0) {
                break;
            }
            String text = stringAfter(response, qKey);
            int wKey = response.indexOf("\"w\"", qKey);
            int nextQ = response.indexOf("\"q\"", qKey + 3);
            double weight = 0.5;
            if (wKey >= 0 && (nextQ < 0 || wKey < nextQ)) {
                Double read = numberAfter(response, wKey);
                if (read != null) {
                    weight = read;
                }
            }
            at = qKey + 3;
            if (text == null || text.isBlank()) {
                continue;
            }
            text = text.trim();
            String key = text.toLowerCase(Locale.ROOT);
            if (key.equals(originalKey) || unique.containsKey(key)) {
                continue;
            }
            // Clamped, not rejected: a model that returns 1.5 meant "very relevant", and the
            // ceiling below ORIGINAL_WEIGHT is what keeps a guess from outranking the real query.
            double clamped = Math.max(0.05, Math.min(0.9, weight));
            if (clamped < MIN_USEFUL_WEIGHT) {
                // The model's own judgement, used as the filter.
                continue;
            }
            SearchFormulation formulation = new SearchFormulation(text, clamped);
            unique.put(key, formulation);
            found.add(formulation);
        }
        found.sort((a, b) -> Double.compare(b.weight(), a.weight()));
        return found;
    }

    /** The JSON string value following a key position, or null. */
    private static String stringAfter(String json, int keyPos) {
        int colon = json.indexOf(':', keyPos);
        if (colon < 0) {
            return null;
        }
        int open = json.indexOf('"', colon);
        if (open < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = open + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                sb.append(switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> next;
                });
                continue;
            }
            if (c == '"') {
                return sb.toString();
            }
            sb.append(c);
        }
        return null;
    }

    /** The JSON number following a key position, or null. */
    private static Double numberAfter(String json, int keyPos) {
        int colon = json.indexOf(':', keyPos);
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < json.length()
               && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '.'
                   || json.charAt(i) == '-' || json.charAt(i) == 'e' || json.charAt(i) == 'E')) {
            i++;
        }
        if (start == i) {
            return null;
        }
        try {
            return Double.valueOf(json.substring(start, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
