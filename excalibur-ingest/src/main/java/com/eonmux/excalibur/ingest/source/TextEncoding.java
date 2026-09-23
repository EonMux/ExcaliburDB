package com.eonmux.excalibur.ingest.source;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Settles which encoding a document's bytes are in, and turns them into text.
 *
 * <h2>Why every source asks the same question here</h2>
 * <p>Nothing downstream of a source can recover the encoding once the bytes have been separated
 * from it, and each source learns it from a different place - an HTTP {@code Content-Type}, a
 * charset an operator set on a directory, a byte-order mark at the front of the content. The
 * mark is the one witness every source shares, so it is read in exactly one place: a crawl of a
 * web server and a walk of a directory holding the same UTF-16 file must decode it the same
 * way, and two copies of the detection are two chances for them not to.</p>
 *
 * <h2>Why a byte-order mark outranks a declared charset</h2>
 * <p>A declaration is a claim about a whole source or a whole server, made once by someone who
 * did not look at this file: an operator sets {@code windows-1252} on a directory of legacy
 * exports, and one file in it was saved by an editor that writes UTF-16. A byte-order mark is a
 * claim about these bytes, written by the program that encoded them, and it is not something
 * text in another encoding produces by accident - {@code FF FE} is {@code ÿþ} in Latin-1, which
 * no real document opens with. When the two disagree the mark is right and the declaration is
 * stale, and decoding by the declaration turns every character of that file into mojibake while
 * reporting it as indexed. This is the same order the WHATWG encoding standard sets for the web,
 * where a mark overrides the transport's label for the same reason.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; every method is a pure function of its arguments.</p>
 */
public final class TextEncoding {

    /** What a byte-order mark decodes to; it marks the encoding and is not part of the text. */
    private static final char BYTE_ORDER_MARK = '\uFEFF';

    /** UTF-32 has no constant in {@link StandardCharsets}, and every JDK ships it. */
    private static final Charset UTF_32BE = Charset.forName("UTF-32BE");
    private static final Charset UTF_32LE = Charset.forName("UTF-32LE");

    private TextEncoding() {
        throw new AssertionError("Utility class");
    }

    /**
     * The charset a byte-order mark at the start of the content declares.
     *
     * <p>The four-byte UTF-32 marks are tested before the two-byte UTF-16 ones they begin with.
     * {@code FF FE 00 00} is UTF-32LE: read as UTF-16LE it would be a mark followed by U+0000,
     * which no text contains.</p>
     *
     * @param content The document's bytes
     * @return The charset the mark names, or null when the content carries none
     * @throws NullPointerException if content is null
     */
    public static Charset ofByteOrderMark(byte[] content) {
        Objects.requireNonNull(content, "content must not be null");
        if (startsWith(content, 0x00, 0x00, 0xFE, 0xFF)) {
            return UTF_32BE;
        }
        if (startsWith(content, 0xFF, 0xFE, 0x00, 0x00)) {
            return UTF_32LE;
        }
        if (startsWith(content, 0xEF, 0xBB, 0xBF)) {
            return StandardCharsets.UTF_8;
        }
        if (startsWith(content, 0xFE, 0xFF)) {
            return StandardCharsets.UTF_16BE;
        }
        if (startsWith(content, 0xFF, 0xFE)) {
            return StandardCharsets.UTF_16LE;
        }
        return null;
    }

    /**
     * The encoding to decode a document in: its byte-order mark, else what its source declared,
     * else UTF-8.
     *
     * <p>See the class documentation for why the mark wins. UTF-8 is the last resort because it
     * is what a source that declares nothing almost always holds, and because it fails visibly -
     * replacement characters the text check counts - rather than decoding garbage cleanly the way
     * a single-byte guess would.</p>
     *
     * @param content  The document's bytes
     * @param declared What the source declared, or null when it declared nothing
     * @return The charset, never null
     * @throws NullPointerException if content is null
     */
    public static Charset resolve(byte[] content, Charset declared) {
        Charset marked = ofByteOrderMark(content);
        if (marked != null) {
            return marked;
        }
        return declared != null ? declared : StandardCharsets.UTF_8;
    }

    /**
     * Decodes content, dropping a byte-order mark it opens with.
     *
     * <p>The mark says how the bytes are encoded and is not a character of the document. Kept,
     * it is glued to the first word - U+FEFF followed by {@code Introduction} is a term nobody
     * will ever search for, and the document can no longer be found by its own first word. Dropped after
     * decoding rather than by skipping bytes, so it goes whichever encoding put it there,
     * including a declared {@code UTF-8} file that also carries one.</p>
     *
     * @param content The document's bytes
     * @param charset The encoding they are in
     * @return The text, without a leading byte-order mark
     * @throws NullPointerException if content or charset is null
     */
    public static String decode(byte[] content, Charset charset) {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(charset, "charset must not be null");
        String text = new String(content, charset);
        return !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;
    }

    private static boolean startsWith(byte[] content, int... prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((content[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
