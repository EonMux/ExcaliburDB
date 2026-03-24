package excalibur.ingest.processor;

import excalibur.filter.sketch.CountMinSketch;
import io.excalibur.store.Segment;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Tracks term frequencies during document ingestion for query selectivity estimation.
 *
 * <p>This class is responsible for populating the Count-Min Sketch attached to
 * segments with term frequency data during the ingestion pipeline. The frequency
 * data enables the query planner to estimate selectivity and optimize query execution.</p>
 *
 * <h2>Integration with Ingestion Pipeline</h2>
 * <p>The TermFrequencyTracker should be called after text chunking:</p>
 * <ol>
 *   <li>Document is fetched from source</li>
 *   <li>Text is extracted and chunked via {@link TextChunker}</li>
 *   <li>Each chunk's tokens are tracked via {@link #trackTokens(Segment, List)}</li>
 *   <li>Vector embedding is generated</li>
 *   <li>Entry is written to segment</li>
 * </ol>
 *
 * <h2>Token Extraction</h2>
 * <p>Tokens are extracted using {@link WhitespaceTokenizer} for consistency with
 * the chunking process. Each token is converted to UTF-8 bytes and inserted into
 * the segment's Count-Min Sketch.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The underlying {@link CountMinSketch} supports
 * concurrent insertions via striped locking. Multiple threads can safely track
 * tokens for different segments concurrently.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Null segments are rejected with NullPointerException</li>
 *   <li>Segments without Count-Min Sketch are skipped gracefully</li>
 *   <li>Empty token lists are handled correctly</li>
 *   <li>Token frequency increments are always positive (1)</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 8.0
 */
public class TermFrequencyTracker {

    /**
     * Default Count-Min Sketch width for term frequency tracking.
     * Targets ~0.1% error rate (ε ≈ 2/width = 0.001).
     */
    public static final int DEFAULT_SKETCH_WIDTH = 2000;

    /**
     * Default Count-Min Sketch depth for term frequency tracking.
     * Targets ~99.9% confidence (δ ≈ 2^-depth = 0.001).
     */
    public static final int DEFAULT_SKETCH_DEPTH = 10;

    /**
     * Creates a new Count-Min Sketch with default parameters optimized
     * for term frequency tracking.
     *
     * @return A new CountMinSketch instance
     */
    public static CountMinSketch createSketch() {
        return new CountMinSketch(DEFAULT_SKETCH_WIDTH, DEFAULT_SKETCH_DEPTH);
    }

    /**
     * Creates a new Count-Min Sketch with custom parameters.
     *
     * @param width Number of counters per row (controls error rate)
     * @param depth Number of rows (controls confidence)
     * @return A new CountMinSketch instance
     * @throws IllegalArgumentException if width or depth is not positive
     */
    public static CountMinSketch createSketch(int width, int depth) {
        return new CountMinSketch(width, depth);
    }

    /**
     * Tracks term frequencies for a list of text chunks in a segment.
     *
     * <p>This method extracts tokens from each chunk and inserts them into
     * the segment's Count-Min Sketch. If the segment does not have a sketch
     * attached, one is created and attached automatically.</p>
     *
     * @param segment The segment to track frequencies for (non-null)
     * @param chunks The text chunks to extract tokens from (non-null)
     * @throws NullPointerException if segment or chunks is null
     */
    public void trackChunks(Segment segment, List<TextChunk> chunks) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        Objects.requireNonNull(chunks, "Chunks cannot be null");

        if (chunks.isEmpty()) {
            return;
        }

        // Ensure segment has a sketch
        CountMinSketch sketch = getOrCreateSketch(segment);

        // Track tokens from each chunk
        for (TextChunk chunk : chunks) {
            if (chunk == null || chunk.getText() == null || chunk.getText().isEmpty()) {
                continue;
            }
            trackTokensInternal(sketch, chunk.getText());
        }
    }

    /**
     * Tracks term frequencies for a list of tokens in a segment.
     *
     * <p>This method is useful when tokens have already been extracted
     * (e.g., during chunking) and you want to track them without
     * re-tokenization.</p>
     *
     * @param segment The segment to track frequencies for (non-null)
     * @param tokens The tokens to track (non-null)
     * @throws NullPointerException if segment or tokens is null
     */
    public void trackTokens(Segment segment, List<String> tokens) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        Objects.requireNonNull(tokens, "Tokens cannot be null");

        if (tokens.isEmpty()) {
            return;
        }

        CountMinSketch sketch = getOrCreateSketch(segment);

        for (String token : tokens) {
            if (token == null || token.isEmpty()) {
                continue;
            }
            byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
            sketch.insert(tokenBytes);
        }
    }

    /**
     * Tracks term frequencies for a single text in a segment.
     *
     * <p>Tokenizes the text using whitespace and inserts each token
     * into the segment's Count-Min Sketch.</p>
     *
     * @param segment The segment to track frequencies for (non-null)
     * @param text The text to tokenize and track (non-null)
     * @throws NullPointerException if segment or text is null
     */
    public void trackText(Segment segment, String text) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        Objects.requireNonNull(text, "Text cannot be null");

        if (text.isEmpty()) {
            return;
        }

        CountMinSketch sketch = getOrCreateSketch(segment);
        trackTokensInternal(sketch, text);
    }

    /**
     * Tracks term frequencies for a pre-tokenized text, incrementing by a custom count.
     *
     * <p>This method is useful for weighted frequency tracking, where some
     * tokens should count more than others (e.g., title words vs body words).</p>
     *
     * @param segment The segment to track frequencies for (non-null)
     * @param token The token to track (non-null, non-empty)
     * @param count The count to increment by (must be positive)
     * @throws NullPointerException if segment or token is null
     * @throws IllegalArgumentException if token is empty or count is not positive
     */
    public void trackTokenWithCount(Segment segment, String token, long count) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        Objects.requireNonNull(token, "Token cannot be null");

        if (token.isEmpty()) {
            throw new IllegalArgumentException("Token cannot be empty");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("Count must be positive: " + count);
        }

        CountMinSketch sketch = getOrCreateSketch(segment);
        byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        sketch.insert(tokenBytes, count);
    }

    /**
     * Gets or creates a Count-Min Sketch for a segment.
     *
     * <p>If the segment already has a sketch attached, returns it.
     * Otherwise, creates a new sketch with default parameters and attaches it.</p>
     *
     * @param segment The segment to get or create a sketch for
     * @return The Count-Min Sketch (never null)
     */
    private CountMinSketch getOrCreateSketch(Segment segment) {
        CountMinSketch sketch = segment.termFrequencySketch();
        if (sketch == null) {
            sketch = createSketch();
            segment.attachTermFrequencySketch(sketch);
        }
        return sketch;
    }

    /**
     * Tokenizes text and inserts tokens into the sketch.
     *
     * @param sketch The sketch to insert tokens into
     * @param text The text to tokenize
     */
    private void trackTokensInternal(CountMinSketch sketch, String text) {
        WhitespaceTokenizer tokenizer = WhitespaceTokenizer.tokenize(text);
        List<String> tokens = tokenizer.getTokens();

        for (String token : tokens) {
            if (!token.isEmpty()) {
                byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
                sketch.insert(tokenBytes);
            }
        }
    }

    /**
     * Estimates the frequency of a term in a segment.
     *
     * <p>This is a convenience method for querying the sketch directly.
     * Returns 0 if the segment has no sketch attached.</p>
     *
     * @param segment The segment to query
     * @param term The term to estimate frequency for
     * @return Estimated frequency, or 0 if no sketch
     */
    public long estimateFrequency(Segment segment, String term) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        Objects.requireNonNull(term, "Term cannot be null");

        CountMinSketch sketch = segment.termFrequencySketch();
        if (sketch == null) {
            return 0;
        }

        byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
        return sketch.estimate(termBytes);
    }

    /**
     * Estimates the relative frequency of a term in a segment.
     *
     * <p>Returns the frequency as a fraction of total terms in the segment.
     * Returns 0.0 if the segment has no sketch attached.</p>
     *
     * @param segment The segment to query
     * @param term The term to estimate relative frequency for
     * @return Estimated relative frequency (0.0 to 1.0)
     */
    public double estimateRelativeFrequency(Segment segment, String term) {
        Objects.requireNonNull(segment, "Segment cannot be null");
        Objects.requireNonNull(term, "Term cannot be null");

        CountMinSketch sketch = segment.termFrequencySketch();
        if (sketch == null) {
            return 0.0;
        }

        byte[] termBytes = term.getBytes(StandardCharsets.UTF_8);
        return sketch.estimateFrequency(termBytes);
    }
}
