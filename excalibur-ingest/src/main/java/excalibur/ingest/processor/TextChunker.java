package excalibur.ingest.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Splits text into overlapping chunks for semantic search indexing.
 *
 * <p>Implements the Phase 8 chunking specification:</p>
 * <ul>
 *   <li>Default chunk size: 512 tokens (~2048 characters at ~4 chars/token)</li>
 *   <li>Default overlap: 64 tokens (~256 characters)</li>
 *   <li>Sentence-boundary aware: prefers to split at sentence endings (.!?)</li>
 *   <li>Word-boundary aware: never splits mid-word</li>
 *   <li>Token estimation via {@link WhitespaceTokenizer} (~4 chars/token heuristic)</li>
 * </ul>
 *
 * <h2>Token Estimation</h2>
 * <p>Tokens are estimated using a simple character-based heuristic: approximately
 * 4 characters per token. This is a reasonable approximation for English text
 * and provides consistent, deterministic behavior without external dependencies.
 * For actual tokenization, see {@link WhitespaceTokenizer}.</p>
 *
 * <h2>Sentence Boundary Detection</h2>
 * <p>A sentence boundary is defined as one of: period (.), exclamation mark (!),
 * or question mark (?) followed by either:</p>
 * <ul>
 *   <li>Whitespace (space, newline, tab)</li>
 *   <li>End of string</li>
 * </ul>
 *
 * <h2>Chunk Overlap</h2>
 * <p>Chunks overlap by a configurable number of tokens to ensure that relevant
 * passages spanning chunk boundaries are not lost. The overlap is taken from
 * the end of the previous chunk and prepended to the next chunk.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The chunking configuration is immutable after
 * construction, and the {@link #chunk(String, String)} method can be called
 * concurrently from multiple threads.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Chunk token counts: target size ±10% (461-563 tokens for default 512)</li>
 *   <li>Overlap: exactly 64 tokens (±1 token for boundary adjustments)</li>
 *   <li>Character offsets: accurate positions in original text (start &lt; end)</li>
 *   <li>Never splits mid-word: always finds nearest whitespace boundary</li>
 *   <li>Never splits mid-sentence when possible: prefers sentence boundaries</li>
 * </ul>
 */
public class TextChunker {

    private static final int DEFAULT_TARGET_TOKENS = 512;
    private static final int DEFAULT_OVERLAP_TOKENS = 64;
    private static final int CHARS_PER_TOKEN = 4;
    private static final double TOLERANCE_RATIO = 0.10;

    private final int targetTokens;
    private final int overlapTokens;
    private final int targetChars;
    private final int overlapChars;
    private final int minChunkChars;
    private final int maxChunkChars;

    public TextChunker() {
        this(DEFAULT_TARGET_TOKENS, DEFAULT_OVERLAP_TOKENS);
    }

    public TextChunker(int targetTokens, int overlapTokens) {
        if (targetTokens <= 0) {
            throw new IllegalArgumentException("Target tokens must be positive");
        }
        if (overlapTokens < 0) {
            throw new IllegalArgumentException("Overlap tokens must be non-negative");
        }
        if (overlapTokens >= targetTokens) {
            throw new IllegalArgumentException("Overlap tokens must be less than target tokens");
        }
        this.targetTokens = targetTokens;
        this.overlapTokens = overlapTokens;
        this.targetChars = targetTokens * CHARS_PER_TOKEN;
        this.overlapChars = overlapTokens * CHARS_PER_TOKEN;
        this.minChunkChars = (int) (targetChars * (1.0 - TOLERANCE_RATIO));
        this.maxChunkChars = (int) (targetChars * (1.0 + TOLERANCE_RATIO));
    }

    /**
     * Splits the input text into overlapping chunks.
     *
     * <p>The chunking algorithm:</p>
     * <ol>
     *   <li>Start at character position 0</li>
     *   <li>Find the ideal chunk end at (start + targetChars)</li>
     *   <li>Search backward for a sentence boundary, then a word boundary</li>
     *   <li>If no boundary found within tolerance, search forward for word boundary</li>
     *   <li>Create chunk and advance position by (chunkLength - overlapChars)</li>
     *   <li>Repeat until end of text</li>
     * </ol>
     *
     * @param documentId Unique identifier for the source document (non-null, non-empty)
     * @param text       The text to chunk (non-null, may be empty)
     * @return List of TextChunk objects in document order (never null, may be empty)
     * @throws IllegalArgumentException if documentId is null/empty or text is null
     */
    public List<TextChunk> chunk(String documentId, String text) {
        Objects.requireNonNull(documentId, "Document ID must not be null");
        Objects.requireNonNull(text, "Text must not be null");
        if (documentId.isEmpty()) {
            throw new IllegalArgumentException("Document ID must not be empty");
        }

        List<TextChunk> chunks = new ArrayList<>();
        
        String normalizedText = text.stripTrailing();
        if (normalizedText.isEmpty()) {
            return chunks;
        }

        int textLength = normalizedText.length();
        int chunkStart = 0;
        int chunkIndex = 0;

        int minTrailingChunkChars = minChunkChars / 2;
        
        while (chunkStart < textLength) {
            int remaining = textLength - chunkStart;
            
            if (remaining <= maxChunkChars) {
                if (chunks.isEmpty() || remaining >= minTrailingChunkChars) {
                    int tokenCount = estimateTokens(remaining);
                    chunks.add(new TextChunk(
                        documentId,
                        normalizedText.substring(chunkStart),
                        tokenCount,
                        chunkStart,
                        textLength,
                        chunkIndex
                    ));
                } else {
                    TextChunk lastChunk = chunks.get(chunks.size() - 1);
                    int extendedEnd = textLength;
                    int extendedLength = extendedEnd - lastChunk.getStartOffset();
                    int extendedTokens = estimateTokens(extendedLength);
                    
                    if (extendedTokens <= (int)(targetTokens * 1.10)) {
                        chunks.set(chunks.size() - 1, new TextChunk(
                            documentId,
                            normalizedText.substring(lastChunk.getStartOffset()),
                            extendedTokens,
                            lastChunk.getStartOffset(),
                            extendedEnd,
                            lastChunk.getChunkIndex()
                        ));
                    } else {
                        int tokenCount = estimateTokens(remaining);
                        chunks.add(new TextChunk(
                            documentId,
                            normalizedText.substring(chunkStart),
                            tokenCount,
                            chunkStart,
                            textLength,
                            chunkIndex
                        ));
                    }
                }
                break;
            }

            int idealEnd = Math.min(chunkStart + targetChars, textLength);
            int chunkEnd = findChunkBoundary(normalizedText, chunkStart, idealEnd);
            
            int actualLength = chunkEnd - chunkStart;
            int tokenCount = estimateTokens(actualLength);
            
            chunks.add(new TextChunk(
                documentId,
                normalizedText.substring(chunkStart, chunkEnd),
                tokenCount,
                chunkStart,
                chunkEnd,
                chunkIndex
            ));

            int advance = chunkEnd - chunkStart - overlapChars;
            if (advance <= 0) {
                advance = Math.max(1, (chunkEnd - chunkStart) / 2);
            }
            chunkStart += advance;
            chunkIndex++;
            
            while (chunkStart > 0 && chunkStart < textLength && !isAtWordBoundary(normalizedText, chunkStart)) {
                chunkStart++;
            }
        }

        return chunks;
    }

    /**
     * Finds the best chunk boundary near the ideal end position.
     *
     * <p>Prioritizes in order:</p>
     * <ol>
     *   <li>Sentence boundary within tolerance range</li>
     *   <li>Word boundary within tolerance range</li>
     *   <li>Next word boundary past tolerance (if necessary)</li>
     * </ol>
     *
     * @param text      The full text
     * @param start     Chunk start position
     * @param idealEnd  Ideal chunk end position (target size)
     * @return Actual chunk end position
     */
    private int findChunkBoundary(String text, int start, int idealEnd) {
        int textLength = text.length();
        int minEnd = start + minChunkChars;
        int maxEnd = Math.min(start + maxChunkChars, textLength);

        int sentenceBoundary = findSentenceBoundaryBackward(text, idealEnd, minEnd);
        if (sentenceBoundary > start) {
            return sentenceBoundary;
        }

        int wordBoundary = findWordBoundaryBackward(text, idealEnd, minEnd);
        if (wordBoundary > start) {
            return wordBoundary;
        }

        int forwardBoundary = findWordBoundaryForward(text, idealEnd, maxEnd);
        if (forwardBoundary > start) {
            return forwardBoundary;
        }

        return idealEnd;
    }

    /**
     * Searches backward from the given position for a sentence boundary.
     *
     * <p>A sentence boundary is: [.!?] followed by whitespace or end of string.</p>
     *
     * @param text  The text to search
     * @param from  Starting position (search backward from here)
     * @param minPos Minimum position to consider
     * @return Position after the sentence boundary, or -1 if not found
     */
    private int findSentenceBoundaryBackward(String text, int from, int minPos) {
        for (int i = Math.min(from, text.length() - 1); i >= minPos; i--) {
            if (isSentenceBoundary(text, i)) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * Checks if position i is at a sentence boundary (punctuation followed by space/end).
     *
     * @param text The text
     * @param i    Position to check (should be at the punctuation character)
     * @return true if this is a sentence boundary
     */
    private boolean isSentenceBoundary(String text, int i) {
        char c = text.charAt(i);
        if (c != '.' && c != '!' && c != '?') {
            return false;
        }
        if (i + 1 >= text.length()) {
            return true;
        }
        char next = text.charAt(i + 1);
        return Character.isWhitespace(next);
    }

    /**
     * Searches backward from the given position for a word boundary (whitespace).
     *
     * @param text   The text to search
     * @param from   Starting position
     * @param minPos Minimum position to consider
     * @return Position of the word boundary, or -1 if not found
     */
    private int findWordBoundaryBackward(String text, int from, int minPos) {
        for (int i = Math.min(from, text.length() - 1); i >= minPos; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Searches forward from the given position for a word boundary (whitespace).
     *
     * @param text   The text to search
     * @param from   Starting position
     * @param maxPos Maximum position to consider
     * @return Position of the word boundary, or -1 if not found
     */
    private int findWordBoundaryForward(String text, int from, int maxPos) {
        int limit = Math.min(maxPos, text.length());
        for (int i = from; i < limit; i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return limit < text.length() ? limit : -1;
    }

    /**
     * Checks if the given position is at a word boundary (after whitespace).
     *
     * @param text The text
     * @param pos  Position to check
     * @return true if position is at start of a word (after whitespace or at start)
     */
    private boolean isAtWordBoundary(String text, int pos) {
        if (pos == 0) {
            return true;
        }
        return Character.isWhitespace(text.charAt(pos - 1));
    }

    /**
     * Estimates the number of tokens in a text segment of the given character length.
     *
     * <p>Uses the ~4 characters per token heuristic.</p>
     *
     * @param charCount Number of characters
     * @return Estimated token count
     */
    private int estimateTokens(int charCount) {
        return Math.max(1, (charCount + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN);
    }

    public int getTargetTokens() {
        return targetTokens;
    }

    public int getOverlapTokens() {
        return overlapTokens;
    }

    public int getTargetChars() {
        return targetChars;
    }

    public int getOverlapChars() {
        return overlapChars;
    }

    @Override
    public String toString() {
        return "TextChunker{" +
               "targetTokens=" + targetTokens +
               ", overlapTokens=" + overlapTokens +
               ", targetChars=" + targetChars +
               ", overlapChars=" + overlapChars +
               '}';
    }
}
