package excalibur.query.payload;

import io.excalibur.core.codec.ChunkPayloadCodec;

/**
 * The query side's view of a stored chunk value.
 *
 * <p>The layout itself belongs to {@link ChunkPayloadCodec}, which every writer and reader in
 * the process shares. What lives here is the one thing that is a query concern rather than a
 * format concern: how much of a chunk's text is allowed into a prompt.
 *
 * @author ExcaliburDB
 * @since 11.0
 */
public final class ChunkValueCodec {

    /**
     * Maximum number of text bytes any one result carries.
     *
     * <p>Relevancy scoring reads the text into a prompt, so an unbounded chunk body would be an
     * unbounded request; every producer of a payload truncates to this budget.</p>
     */
    public static final int MAX_TEXT_BYTES = 8 * 1024;

    private ChunkValueCodec() {
        throw new AssertionError("Utility class");
    }

    /**
     * Returns the chunk text stored after the embedding.
     *
     * <p>A value that does not carry the documented header is rejected rather than guessed at.
     * Handing the raw bytes of an unrecognised encoding to an LLM as "text" would score a
     * document against binary noise.</p>
     *
     * @param value The stored value bytes (may be null)
     * @return The UTF-8 text bytes, or null when the value carries no readable text
     */
    public static byte[] textOf(byte[] value) {
        return ChunkPayloadCodec.textBytesOf(value);
    }

    /**
     * Returns at most {@code maxBytes} of {@code text}, cut on a UTF-8 character boundary.
     *
     * @param text     The UTF-8 text bytes (may be null)
     * @param maxBytes The maximum number of bytes to keep (must be positive)
     * @return The text, truncated if it exceeded the budget
     */
    public static byte[] truncateUtf8(byte[] text, int maxBytes) {
        return ChunkPayloadCodec.truncateUtf8(text, maxBytes);
    }
}
