package io.excalibur.core.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * The stored form of an ingested chunk.
 *
 * <h2>Layout</h2>
 * <pre>
 * [dimension : int32 little-endian, 0 when the chunk carries no embedding]
 * [embedding : dimension * float32 little-endian, absent when dimension is 0]
 * [text      : the remaining bytes, UTF-8, possibly empty]
 * </pre>
 *
 * <h2>Chunks with no embedding</h2>
 * <p>A dimension of 0 is a chunk that was indexed lexically and never embedded. It is a normal
 * value, not a damaged one: retrieval that goes through Lucene and the graph does not need a
 * vector, and requiring one made a corpus-wide embedding pass the price of admission for any
 * indexing at all. Readers get null from {@link #embeddingOf(byte[])} for such a chunk, which is
 * the same answer they already had to handle for a value whose vector they cannot use, while
 * {@link #textBytesOf(byte[])} returns the text as usual.</p>
 *
 * <h2>Why one class owns it</h2>
 * <p>This layout is written to disk by ingestion and by the reindexer, and read back by the tail
 * scanner, the HNSW integration worker and relevancy scoring. It was previously spelled out
 * independently at each of those points, which is a persisted format with several definitions
 * and no test tying them together: a change to one writer would have been discovered as
 * mis-decoded vectors, not as a compile error. The copies had already drifted - some validated
 * the dimension header before trusting it and some did not, so the same corrupt value was
 * rejected by one reader and turned into a wild allocation by another.
 *
 * <h2>Reading defensively</h2>
 * <p>Every decoder here treats the value as untrusted. The dimension is a 32-bit field read from
 * storage, so it can be negative, absurd, or inconsistent with the length that follows it; each
 * of those is a value this codec declines to decode rather than an arithmetic overflow computing
 * an offset. A value that cannot be decoded yields null, because the callers are query paths for
 * which one unreadable entry is a result to skip and not a request to fail.
 */
public final class ChunkPayloadCodec {

    /** Bytes in the leading dimension field. */
    public static final int HEADER_BYTES = Integer.BYTES;

    /**
     * Largest dimension this codec will believe from a value header.
     *
     * <p>Well above any embedding model in use and far below the point where
     * {@code HEADER_BYTES + dimension * Float.BYTES} could overflow, so the length arithmetic
     * below cannot wrap however corrupt the stored header is.
     */
    public static final int MAX_DIMENSION = 65_536;

    private ChunkPayloadCodec() {
        throw new AssertionError("Utility class");
    }

    /**
     * Encodes an embedding and its chunk text.
     *
     * @param text      The chunk text
     * @param embedding The embedding vector
     * @return The stored value
     * @throws NullPointerException     if either argument is null
     * @throws IllegalArgumentException if the embedding is empty or longer than
     *                                  {@link #MAX_DIMENSION}
     */
    public static byte[] encode(String text, float[] embedding) {
        Objects.requireNonNull(text, "text must not be null");
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        return encode(embedding, textBytes);
    }

    /**
     * Encodes an embedding with no text.
     *
     * @param embedding The embedding vector
     * @return The stored value
     * @throws NullPointerException     if the embedding is null
     * @throws IllegalArgumentException if the embedding is empty or longer than
     *                                  {@link #MAX_DIMENSION}
     */
    public static byte[] encode(float[] embedding) {
        return encode(embedding, new byte[0]);
    }

    /**
     * Encodes chunk text with no embedding.
     *
     * @param text The chunk text
     * @return The stored value, declaring dimension 0
     * @throws NullPointerException if the text is null
     */
    public static byte[] encodeTextOnly(String text) {
        Objects.requireNonNull(text, "text must not be null");
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(HEADER_BYTES + textBytes.length)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0)
            .put(textBytes)
            .array();
    }

    private static byte[] encode(float[] embedding, byte[] textBytes) {
        Objects.requireNonNull(embedding, "embedding must not be null");
        if (embedding.length == 0) {
            throw new IllegalArgumentException("embedding must not be empty");
        }
        if (embedding.length > MAX_DIMENSION) {
            throw new IllegalArgumentException(
                "embedding dimension " + embedding.length + " exceeds " + MAX_DIMENSION);
        }

        ByteBuffer buffer = ByteBuffer
            .allocate(HEADER_BYTES + embedding.length * Float.BYTES + textBytes.length)
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(embedding.length);
        for (float component : embedding) {
            buffer.putFloat(component);
        }
        buffer.put(textBytes);
        return buffer.array();
    }

    /**
     * Returns the dimension the value declares, or -1 when the value does not carry a usable one.
     *
     * <p>Usable means: the header is present, the dimension is not negative and is within
     * {@link #MAX_DIMENSION}, and the value is long enough to hold that many floats. Zero is
     * usable and means the chunk carries text but no embedding. A caller that gets -1 has a
     * value this codec did not write, or one that has been truncated.
     *
     * @param value The stored value (may be null)
     * @return The dimension, 0 when the chunk has no embedding, or -1 when unreadable
     */
    public static int dimensionOf(byte[] value) {
        if (value == null || value.length < HEADER_BYTES) {
            return -1;
        }
        int dimension = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (dimension < 0 || dimension > MAX_DIMENSION) {
            return -1;
        }
        if (value.length < HEADER_BYTES + dimension * Float.BYTES) {
            return -1;
        }
        return dimension;
    }

    /**
     * Decodes the embedding.
     *
     * @param value The stored value (may be null)
     * @return The embedding, or null when the value carries none
     */
    public static float[] embeddingOf(byte[] value) {
        int dimension = dimensionOf(value);
        if (dimension <= 0) {
            // 0 is a chunk indexed without a vector; -1 is a value that cannot be read. Both
            // mean the caller has no embedding to use here.
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        buffer.getInt();
        float[] embedding = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            embedding[i] = buffer.getFloat();
        }
        return embedding;
    }

    /**
     * Decodes the embedding, requiring it to have the dimension the caller expects.
     *
     * <p>A value whose header says something else is not decoded. A vector of the wrong width
     * cannot be compared against the query, and silently returning it would produce a distance
     * computed over unrelated components.
     *
     * @param value             The stored value (may be null)
     * @param expectedDimension The dimension the caller can use
     * @return The embedding, or null when absent or of another dimension
     */
    public static float[] embeddingOf(byte[] value, int expectedDimension) {
        if (dimensionOf(value) != expectedDimension) {
            return null;
        }
        return embeddingOf(value);
    }

    /**
     * Returns the chunk text bytes stored after the embedding.
     *
     * @param value The stored value (may be null)
     * @return The UTF-8 bytes, or null when the value carries no readable text
     */
    public static byte[] textBytesOf(byte[] value) {
        int dimension = dimensionOf(value);
        if (dimension < 0) {
            return null;
        }
        int textStart = HEADER_BYTES + dimension * Float.BYTES;
        if (textStart >= value.length) {
            // A vector-only entry. There is no text, which is different from empty text only
            // in that no caller has anything to score.
            return null;
        }
        return Arrays.copyOfRange(value, textStart, value.length);
    }

    /**
     * Returns the chunk text stored after the embedding.
     *
     * @param value The stored value (may be null)
     * @return The text, or null when the value carries none
     */
    public static String textOf(byte[] value) {
        byte[] bytes = textBytesOf(value);
        return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
    }

    /**
     * Returns at most {@code maxBytes} of UTF-8 text, cut on a character boundary.
     *
     * <p>Cutting mid-character decodes to a replacement character, so the cut is walked back off
     * any continuation bytes.
     *
     * @param text     The UTF-8 bytes (may be null)
     * @param maxBytes The budget, which must be positive
     * @return The text, truncated if it exceeded the budget
     * @throws IllegalArgumentException if maxBytes is not positive
     */
    public static byte[] truncateUtf8(byte[] text, int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        if (text == null || text.length <= maxBytes) {
            return text;
        }
        int end = maxBytes;
        while (end > 0 && (text[end] & 0xC0) == 0x80) {
            end--;
        }
        return Arrays.copyOf(text, end);
    }
}
