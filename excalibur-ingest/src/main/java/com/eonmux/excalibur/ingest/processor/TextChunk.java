package com.eonmux.excalibur.ingest.processor;

import com.eonmux.excalibur.core.text.TermExtractor;

import java.util.List;
import java.util.Objects;

/**
 * Represents a text chunk produced by the chunker.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. Its {@linkplain #terms() terms} are worked out on
 * first use and kept.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Text: the chunk content (never null, may be empty)</li>
 *   <li>Token count: estimated token count based on ~4 chars/token (always &ge; 0)</li>
 *   <li>Start offset: character position in original document (always &ge; 0)</li>
 *   <li>Chunk index: position in the chunk sequence (always &ge; 0)</li>
 *   <li>Document ID: identifier of the source document (non-null, non-empty)</li>
 * </ul>
 */
public final class TextChunk {

    private final String documentId;
    private final String text;
    private final int tokenCount;
    private final int startOffset;
    private final int endOffset;
    private final int chunkIndex;

    /** The text's terms, once something has asked for them; see {@link #terms()}. */
    private List<String> terms;

    public TextChunk(String documentId, String text, int tokenCount, int startOffset, int endOffset, int chunkIndex) {
        if (documentId == null || documentId.isEmpty()) {
            throw new IllegalArgumentException("Document ID must be non-null and non-empty");
        }
        if (text == null) {
            throw new IllegalArgumentException("Text must be non-null");
        }
        if (tokenCount < 0) {
            throw new IllegalArgumentException("Token count must be >= 0");
        }
        if (startOffset < 0) {
            throw new IllegalArgumentException("Start offset must be >= 0");
        }
        if (endOffset < startOffset) {
            throw new IllegalArgumentException("End offset must be >= start offset");
        }
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("Chunk index must be >= 0");
        }
        this.documentId = documentId;
        this.text = text;
        this.tokenCount = tokenCount;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.chunkIndex = chunkIndex;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getText() {
        return text;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public int getStartOffset() {
        return startOffset;
    }

    public int getEndOffset() {
        return endOffset;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public int getCharLength() {
        return text.length();
    }

    /**
     * The chunk's terms, as {@link TermExtractor#extractTerms(String)} finds them in its text:
     * in order, with repetition.
     *
     * <p>Everything a write of this chunk feeds reads them from here - the segment's
     * term-frequency sketch counts every occurrence, the membership filter files each distinct
     * term - so the text is tokenised once per chunk rather than once per consumer, and the two
     * cannot come to disagree about what the chunk holds.</p>
     *
     * <p>Worked out on first use and kept. Synchronized so that holds under concurrent first
     * calls too; after the first, the cost is an uncontended lock.</p>
     *
     * @return The terms, unmodifiable; empty when the text holds none
     */
    public synchronized List<String> terms() {
        if (terms == null) {
            terms = List.copyOf(TermExtractor.extractTerms(text));
        }
        return terms;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TextChunk textChunk = (TextChunk) o;
        return tokenCount == textChunk.tokenCount &&
               startOffset == textChunk.startOffset &&
               endOffset == textChunk.endOffset &&
               chunkIndex == textChunk.chunkIndex &&
               Objects.equals(documentId, textChunk.documentId) &&
               Objects.equals(text, textChunk.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(documentId, text, tokenCount, startOffset, endOffset, chunkIndex);
    }

    @Override
    public String toString() {
        return "TextChunk{" +
               "documentId='" + documentId + '\'' +
               ", tokenCount=" + tokenCount +
               ", startOffset=" + startOffset +
               ", endOffset=" + endOffset +
               ", chunkIndex=" + chunkIndex +
               ", charLength=" + text.length() +
               '}';
    }
}
