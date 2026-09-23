package com.eonmux.excalibur.retrieval.expand;

import com.eonmux.excalibur.llm.client.CircuitBreaker;
import com.eonmux.excalibur.llm.client.LLMHttpClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 *
 * <p>"Slow" is enforced here rather than left to the client. The client's timeout is sized for
 * ingestion, where nothing is holding a socket open, and it is spent again on each retry; one
 * call on that budget outlasts every query budget the API will accept, so the caller gets an
 * empty timed-out answer while the abandoned search keeps running behind a concurrency permit
 * that says it is free. The deadline below is the query's, and the call is abandoned on it.</p>
 *
 * <p>An interrupt is the one thing that is not degraded. It means the request this expansion
 * belongs to has been withdrawn, and returning the original query would have the search carry
 * on for an answer nobody will read - holding, for the length of a full BM25 pass, the permit
 * that bounds how much of that work can accumulate.</p>
 *
 * <h2>How long a query may be</h2>
 * <p>Not bounded here. The query is the body of an outbound call the operator is billed for,
 * and the bound on it belongs where the text enters the process: {@code /v1/query} refuses a
 * query longer than {@code excalibur.api.max-query-chars} before anything is sent anywhere.
 * A second bound here would either repeat that one, and never apply, or sit below it, and make
 * a longer question be answered by plain BM25 while a shorter one is answered by an expanded
 * search - two retrieval strategies behind one endpoint, chosen by a length the caller cannot
 * see. A caller embedding this outside the API bounds its own input.</p>
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

    /**
     * Weight given to a term the model returned without one.
     *
     * <p>The lowest weight that is still searched, which is the only defensible reading of a
     * term the model chose to emit and said nothing about: it is a term, so it is searched,
     * and an unstated confidence must not outrank a stated one. Anything beneath
     * {@link #MIN_USEFUL_WEIGHT} would mean a model that emits terms and forgets the weights -
     * an ordinary formatting failure - silently disables expansion altogether, on the
     * deployment whose entire semantic capability it is.</p>
     */
    public static final double UNWEIGHTED_TERM_WEIGHT = MIN_USEFUL_WEIGHT;

    /**
     * How long one expansion call may take, by default.
     *
     * <p>The generation is a few dozen tokens and completes in around two seconds against a
     * local endpoint, so this is roughly twice the headroom it needs while leaving the larger
     * part of a default 30 second query budget to the retrieval the expansion is for. It is a
     * query-path deadline, not the client's own: the difference is whether somebody is waiting
     * on the socket.</p>
     */
    public static final long DEFAULT_TIMEOUT_MS = 5_000L;

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
    private final long timeoutMs;

    /**
     * Creates an expander on the built-in bounds.
     *
     * @param client The chat endpoint to ask
     */
    public LlmQueryExpander(LLMHttpClient client) {
        this(client, DEFAULT_MAX_FORMULATIONS, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Creates an expander on a deployment's own expansion deadline.
     *
     * @param client    The chat endpoint to ask
     * @param timeoutMs How long one expansion call may take
     */
    public LlmQueryExpander(LLMHttpClient client, long timeoutMs) {
        this(client, DEFAULT_MAX_FORMULATIONS, timeoutMs);
    }

    /**
     * Creates an expander on a deployment's own bounds.
     *
     * @param client          The chat endpoint to ask
     * @param maxFormulations Cap on formulations returned, the original included
     * @param timeoutMs       How long one expansion call may take
     * @throws NullPointerException     if client is null
     * @throws IllegalArgumentException if any bound is not positive
     */
    public LlmQueryExpander(LLMHttpClient client, int maxFormulations, long timeoutMs) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        if (maxFormulations < 1) {
            throw new IllegalArgumentException("maxFormulations must be positive: " + maxFormulations);
        }
        if (timeoutMs < 1) {
            throw new IllegalArgumentException("timeoutMs must be positive: " + timeoutMs);
        }
        this.maxFormulations = maxFormulations;
        this.timeoutMs = timeoutMs;
    }

    /**
     * The circuit breaker this expander's calls are made through.
     *
     * <p>Exposed so the node can report it. When it opens, every query is searched as typed,
     * with none of the formulations that widen its recall, and the node goes on answering 200 -
     * so without this the only evidence that recall has narrowed is a debug log line.</p>
     *
     * @return The breaker guarding query expansion
     */
    public CircuitBreaker getCircuitBreaker() {
        return client.getCircuitBreaker();
    }

    @Override
    public List<SearchFormulation> expand(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String trimmed = query.trim();
        List<SearchFormulation> out = new ArrayList<>();
        out.add(SearchFormulation.original(trimmed));

        String response = ask(trimmed);
        if (response == null) {
            return List.copyOf(out);
        }
        List<SearchFormulation> parsed = parse(response, trimmed);
        if (parsed.isEmpty()) {
            LOGGER.warn("The chat model returned nothing usable for expansion; this query is "
                + "searched as plain BM25");
            return List.copyOf(out);
        }
        out.addAll(parsed);
        return List.copyOf(out.subList(0, Math.min(out.size(), maxFormulations)));
    }

    /**
     * Asks the model, abandoning the call when the query's own deadline passes.
     *
     * <p>On its own thread because the client offers no per-call deadline: its timeout belongs
     * to the configuration, is sized for ingestion, and is spent again on every retry. The
     * cancellation interrupts the HTTP call rather than leaving it running for a reply nothing
     * will read.</p>
     *
     * @param query The query to expand
     * @return The model's reply, or null when it could not be had in time
     * @throws CancellationException if the calling request was withdrawn while waiting
     */
    private String ask(String query) {
        FutureTask<String> call = new FutureTask<>(() -> client.completeWithCircuitBreaker(
            String.format(Locale.ROOT, INSTRUCTION, maxFormulations - 1), query, null));
        Thread.ofVirtual().name("query-expansion").start(call);
        try {
            return call.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            LOGGER.warn("The chat model did not answer inside the {}ms expansion budget; this "
                + "query is searched as plain BM25", timeoutMs);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.debug("Query expansion unavailable, searching the query as typed: {}",
                cause.toString());
        } catch (InterruptedException e) {
            // Not degraded. The request this belongs to has been withdrawn, and carrying on
            // would run a full search for an answer nobody reads - behind the permit that
            // bounds how much abandoned search can accumulate.
            Thread.currentThread().interrupt();
            call.cancel(true);
            throw new CancellationException(
                "query expansion interrupted; the request it belongs to was withdrawn");
        }
        return null;
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
        int unweighted = 0;
        while (found.size() < maxFormulations) {
            int qKey = response.indexOf("\"q\"", at);
            if (qKey < 0) {
                break;
            }
            String text = stringAfter(response, qKey);
            Double stated = statedWeight(response, qKey);
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
            double clamped = Math.max(0.05, Math.min(SearchFormulation.MAX_EXPANSION_WEIGHT,
                stated == null ? UNWEIGHTED_TERM_WEIGHT : stated));
            if (clamped < MIN_USEFUL_WEIGHT) {
                // The model's own judgement, used as the filter.
                continue;
            }
            if (stated == null) {
                unweighted++;
            }
            SearchFormulation formulation = new SearchFormulation(text, clamped);
            unique.put(key, formulation);
            found.add(formulation);
        }
        if (unweighted > 0) {
            // Said rather than absorbed: the terms are searched at the weakest weight that is
            // still searched, which is a different search from the one the model was asked for,
            // and an operator whose model has stopped emitting weights has no other sign of it.
            LOGGER.warn("The chat model returned {} expansion term(s) with no confidence; they "
                + "are searched at {}", unweighted, UNWEIGHTED_TERM_WEIGHT);
        }
        found.sort((a, b) -> Double.compare(b.weight(), a.weight()));
        return found;
    }

    /**
     * The confidence the model attached to the term at a key position, if it attached one.
     *
     * <p>Bounded to the object the term belongs to: a {@code "w"} sitting past the next
     * {@code "q"} belongs to the following term, and reading it here would give one term
     * another's confidence rather than admit that this one carries none.</p>
     *
     * @param response The model's reply
     * @param qKey     Where this term's {@code "q"} key starts
     * @return The weight the model stated, or null when it stated none this side of the next term
     */
    private static Double statedWeight(String response, int qKey) {
        int wKey = response.indexOf("\"w\"", qKey);
        if (wKey < 0) {
            return null;
        }
        int nextQ = response.indexOf("\"q\"", qKey + 3);
        if (nextQ >= 0 && wKey > nextQ) {
            return null;
        }
        return numberAfter(response, wKey);
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
