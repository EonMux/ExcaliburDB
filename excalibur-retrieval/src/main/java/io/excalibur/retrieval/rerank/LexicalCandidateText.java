package io.excalibur.retrieval.rerank;

import java.io.IOException;

import io.excalibur.retrieval.lexical.LexicalIndex;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a candidate to the text a reranking model should judge, from the same
 * {@link LexicalIndex} that produced the candidate.
 *
 * <p>Reading the text back out of the retrieval index, rather than from a store beside it, is the
 * point. The two would otherwise have to be kept in step by convention: a document replaced in the
 * index but not in the store is served under the previous version's text, and the model then
 * judges - convincingly - a document that no longer exists. There is one copy here, written by
 * {@link LexicalIndex#put}, so the text a candidate is judged on is by construction the text it was
 * matched on.
 *
 * <p>This needed the index to store the field. An analysed Lucene field holds terms and not the
 * string they came from, so before {@link LexicalIndex#FIELD_TEXT} was stored there was nothing to
 * return, and every candidate would have gone to the model as no text at all.
 *
 * <h2>An unreadable candidate</h2>
 * A failed index read yields null and a warning, not an exception. {@link BatchedLlmReranker}
 * already handles a candidate with no text by leaving it at its fused rank, so one unreadable
 * document costs one document's chance of being reranked. Throwing would instead abandon the whole
 * query, including the ranking the other signals had already earned.
 *
 * <h2>Thread Safety</h2>
 * Immutable and thread-safe, holding only the index. {@link LexicalIndex} serves reads through the
 * searcher published by its last refresh, so concurrent lookups are safe and every one of them
 * sees the same snapshot the ranking came from.
 */
public final class LexicalCandidateText implements CandidateText {

    private static final Logger LOGGER = LoggerFactory.getLogger(LexicalCandidateText.class);

    private final LexicalIndex index;

    /**
     * Candidate text out of {@code index}.
     *
     * <p>Give it the index the candidates were ranked from. Pointed at another one, a document id
     * still resolves - ids are integers and every integer is valid - to a different corpus's
     * document, so the reranker is fed plausible text about the wrong thing.
     *
     * @param index the index holding the candidates' text
     * @throws IllegalArgumentException if {@code index} is null
     */
    public LexicalCandidateText(LexicalIndex index) {
        if (index == null) {
            throw new IllegalArgumentException("index must not be null");
        }
        this.index = index;
    }

    /**
     * The text of one candidate, as of the index's last refresh.
     *
     * @param documentId the candidate
     * @return its text, or null if the index has none for it or could not be read
     */
    @Override
    public String textOf(long documentId) {
        try {
            return index.textOf(documentId);
        } catch (IOException e) {
            LOGGER.warn("could not read the text of document {}; it goes unjudged and keeps its "
                + "existing rank", documentId, e);
            return null;
        } catch (RuntimeException e) {
            // Lucene's AlreadyClosedException is an IllegalStateException, and the index this
            // reads can be closed under it: a build or a compaction replaces the served stack
            // while queries are in flight. The reranker calls this while assembling its batch,
            // outside the guard that catches a failing model, so letting it out ends the request
            // with a 500 - for a candidate that would simply have kept its existing rank.
            LOGGER.warn("the lexical index could not serve document {} ({}); it goes unjudged "
                + "and keeps its existing rank", documentId, e.toString());
            return null;
        }
    }
}
