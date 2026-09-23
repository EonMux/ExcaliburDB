package com.eonmux.excalibur.ingest.processor;

import java.util.Objects;

/**
 * Represents a text chunk produced by the chunker.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe.</p>
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
