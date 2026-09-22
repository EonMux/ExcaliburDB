package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.retrieval.ChunkTextResolver;
import com.eonmux.excalibur.retrieval.snippet.QueryTerms;
import com.eonmux.excalibur.retrieval.snippet.Snippet;
import com.eonmux.excalibur.retrieval.snippet.SnippetSelector;
import com.eonmux.excalibur.retrieval.snippet.TermOccurrence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The text behind the results of one request, read once and answered from.
 *
 * <h2>Why one object for two questions</h2>
 * <p>Two different parts of an answer need the same passage. The renderer needs what to show,
 * which is either the chunk from its start or the window the query matched. The explanation
 * needs which of the query's terms the passage actually contains, which is a question about
 * the whole chunk and not about whichever part of it is being shown. Answered separately they
 * would be two storage reads and two scans per result; answered here they are one of each,
 * and the two can never disagree about what the passage said.</p>
 *
 * <h2>Why the chunk is read whole</h2>
 * <p>A window cannot be chosen out of text that was already clipped: clipping to the bound
 * first and then looking for the query in it is exactly the behaviour the window exists to
 * replace. So the read is unbounded and the bound is applied to the window that comes out of
 * it, which is the thing the caller is actually sent.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Not thread-safe, and not meant to be. One of these belongs to one request and is read on
 * the thread building that request's answer; it caches what it has read, which is what makes
 * it worth having at all.</p>
 */
public final class ResultPassages {

    private final ChunkTextResolver source;
    private final int maxChars;
    private final int windowChars;
    private final boolean rendersText;
    private final List<String> terms;
    private final Map<String, Passage> read = new HashMap<>();

    private ResultPassages(ChunkTextResolver source, int maxChars, int windowChars,
                           boolean rendersText, List<String> terms) {
        this.source = source;
        this.maxChars = maxChars;
        this.windowChars = windowChars;
        this.rendersText = rendersText;
        this.terms = List.copyOf(terms);
    }

    /**
     * The reader one request needs, or null when it needs none.
     *
     * @param chunkText   Reads a chunk's text by identifier, as this node holds it
     * @param maxChars    The node's ceiling on result text,
     *                    {@code excalibur.api.max-result-text-chars}
     * @param includeText Whether the request asked for result text
     * @param options     The request's presentation fields
     * @param terms       The query's terms, empty when nothing needs them
     * @return The reader, or null when neither text nor an explanation was asked for
     * @throws NullPointerException     if chunkText is null and one of them was
     * @throws IllegalArgumentException if maxChars is not positive
     */
    public static ResultPassages forRequest(Function<String, String> chunkText, int maxChars,
                                            boolean includeText, QuerySearchOptions options,
                                            List<String> terms) {
        Objects.requireNonNull(options, "options must not be null");
        if (!options.readsPassages(includeText)) {
            return null;
        }
        Objects.requireNonNull(chunkText, "chunkText must not be null");
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive: " + maxChars);
        }
        // Unbounded on purpose: the bound belongs on the window, not on the read the window is
        // chosen out of. ChunkTextResolver is what turns a storage read that throws, or one
        // for a chunk erasure removed, into an answer rather than a failed search.
        return new ResultPassages(ChunkTextResolver.over(chunkText, Integer.MAX_VALUE),
            maxChars, options.snippet() ? Math.min(options.snippetChars(), maxChars) : 0,
            includeText, terms);
    }

    /**
     * Whether an answer built on this reader carries result text.
     *
     * @return true when the request asked for text
     */
    public boolean rendersText() {
        return rendersText;
    }

    /**
     * Whether the text this reader produces is a query-centred window.
     *
     * @return true when the request asked for a snippet
     */
    public boolean windowed() {
        return windowChars > 0;
    }

    /**
     * Everything one result's passage has to say, read at most once per request.
     *
     * @param chunkId The identifier the result carries
     * @return The passage; never null, though its text may be
     */
    public Passage of(String chunkId) {
        Passage cached = read.get(chunkId);
        if (cached != null) {
            return cached;
        }
        Passage passage = load(chunkId);
        read.put(chunkId, passage);
        return passage;
    }

    /**
     * Reads one chunk and works out what is worth saying about it.
     *
     * @param chunkId The identifier the result carries
     * @return The passage
     */
    private Passage load(String chunkId) {
        String full = source.resolve(chunkId).text();
        if (full == null) {
            return Passage.ABSENT;
        }
        List<TermOccurrence> occurrences = terms.isEmpty()
            ? List.of()
            : QueryTerms.occurrences(full, terms, SnippetSelector.MAX_MATCHES_PER_PASSAGE);
        if (!rendersText) {
            return new Passage(null, false, 0, List.of(), matchedTerms(occurrences));
        }
        Snippet snippet = windowed()
            ? SnippetSelector.around(full, occurrences, windowChars)
            : SnippetSelector.fromStart(full, maxChars);
        return new Passage(snippet.text(), snippet.truncated(), snippet.offset(),
            highlights(snippet.matches()), matchedTerms(occurrences));
    }

    /**
     * The query's terms this passage contains, in the order they were typed.
     *
     * <p>Typed order rather than the order they appear in the text: the list answers "which of
     * my words did this match", and a caller comparing two results reads the same term in the
     * same position for both.</p>
     *
     * @param occurrences Where the terms fall in the passage
     * @return The distinct matched terms
     */
    private List<String> matchedTerms(List<TermOccurrence> occurrences) {
        if (occurrences.isEmpty()) {
            return List.of();
        }
        Set<String> found = new HashSet<>();
        for (TermOccurrence occurrence : occurrences) {
            found.add(occurrence.term());
        }
        List<String> matched = new ArrayList<>(found.size());
        for (String term : terms) {
            if (found.contains(term)) {
                matched.add(term);
            }
        }
        return List.copyOf(matched);
    }

    /**
     * Where the matched terms sit inside the text that is actually being returned.
     *
     * @param matches The occurrences the window holds, already rebased to it
     * @return The spans a caller can mark without re-tokenising anything
     */
    private static List<Highlight> highlights(List<TermOccurrence> matches) {
        List<Highlight> spans = new ArrayList<>(matches.size());
        for (TermOccurrence match : matches) {
            spans.add(new Highlight(match.start(), match.length()));
        }
        return List.copyOf(spans);
    }

    /**
     * One result's passage, as an answer reports it.
     *
     * @param text         What to show, or null when storage no longer holds the chunk
     * @param truncated    Whether any of the chunk was left out of {@link #text}
     * @param offset       Where {@link #text} begins in the chunk it was cut from
     * @param highlights   Where the query's terms fall inside {@link #text}
     * @param matchedTerms Which of the query's terms the whole chunk contains
     */
    public record Passage(String text, boolean truncated, int offset, List<Highlight> highlights,
                          List<String> matchedTerms) {

        /** A chunk storage does not hold, or could not be read for. */
        static final Passage ABSENT = new Passage(null, false, 0, List.of(), List.of());

        /**
         * Copies the lists so a passage cannot change after the answer claims it.
         */
        public Passage {
            highlights = List.copyOf(highlights);
            matchedTerms = List.copyOf(matchedTerms);
        }
    }

    /**
     * One matched term's place in the text that was returned.
     *
     * @param start  Index of its first character in the returned text
     * @param length How many characters it spans
     */
    public record Highlight(int start, int length) {
    }
}
