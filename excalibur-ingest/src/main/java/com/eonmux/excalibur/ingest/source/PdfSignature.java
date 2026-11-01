package com.eonmux.excalibur.ingest.source;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Tells a PDF by its bytes rather than by its name or declared type.
 *
 * <p>A file's name and a server's {@code Content-Type} are both claims made by something that did
 * not look at the bytes, which is why the rest of ingestion judges text by what it decodes to.
 * A PDF says what it is in its first line: {@code %PDF-} and a version, which the specification
 * puts at the first byte. A document that opens that way is a PDF.</p>
 *
 * <p>Readers - Acrobat among them - also accept the header anywhere in the first kilobyte, because
 * files with a few bytes of junk ahead of it turn up in the wild. Text that merely mentions the
 * header can put it there too: a note, a source file or a page about PDFs. Read as a PDF it would
 * fail to parse and be lost, or, with a converter configured, be sent through OCR. So a header
 * past the first byte counts only with what a mention does not have: it begins a line, it carries
 * a version, and the document ends with a PDF's {@code %%EOF} trailer.</p>
 */
public final class PdfSignature {

    /** How far into a document the header may begin, and how near its end the trailer must. */
    public static final int SEARCH_WINDOW = 1024;

    private static final byte[] HEADER = {'%', 'P', 'D', 'F', '-'};

    private static final byte[] TRAILER = {'%', '%', 'E', 'O', 'F'};

    private PdfSignature() {
        throw new AssertionError("Static methods only");
    }

    /**
     * Whether a whole document is a PDF, read without moving the buffer's position.
     *
     * @param content The document, from its position to its limit
     * @return Whether it is a PDF
     * @throws NullPointerException if content is null
     */
    public static boolean isPdf(ByteBuffer content) {
        Objects.requireNonNull(content, "content must not be null");
        ByteBuffer document = content.slice();
        int length = document.remaining();
        ByteBuffer head = document.slice(0, Math.min(length, SEARCH_WINDOW));
        int tailLength = Math.min(length, SEARCH_WINDOW);
        ByteBuffer tail = document.slice(length - tailLength, tailLength);
        return isPdf(head, tail);
    }

    /**
     * Whether a document is a PDF, judged from its two ends.
     *
     * <p>For a document read in pieces, such as a file too large to read only to find out what it
     * is. Both may be shorter than {@link #SEARCH_WINDOW} when the document is, and for such a
     * document they overlap.</p>
     *
     * @param head The document's first bytes, up to {@link #SEARCH_WINDOW} of them
     * @param tail The document's last bytes, up to {@link #SEARCH_WINDOW} of them
     * @return Whether it is a PDF
     * @throws NullPointerException if either is null
     */
    public static boolean isPdf(ByteBuffer head, ByteBuffer tail) {
        Objects.requireNonNull(head, "head must not be null");
        Objects.requireNonNull(tail, "tail must not be null");
        ByteBuffer opening = head.slice(0, Math.min(head.remaining(), SEARCH_WINDOW));
        if (matchesAt(opening, 0, HEADER)) {
            return true;
        }
        return hasLaterHeader(opening) && contains(tail, TRAILER);
    }

    /** A header past the first byte, at the start of a line and followed by a version digit. */
    private static boolean hasLaterHeader(ByteBuffer opening) {
        int last = opening.limit() - HEADER.length - 1;
        for (int at = 1; at <= last; at++) {
            byte before = opening.get(at - 1);
            if ((before == '\n' || before == '\r') && matchesAt(opening, at, HEADER)
                    && Character.isDigit(opening.get(at + HEADER.length))) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(ByteBuffer buffer, byte[] needle) {
        ByteBuffer window = buffer.slice();
        for (int at = 0; at <= window.limit() - needle.length; at++) {
            if (matchesAt(window, at, needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAt(ByteBuffer buffer, int at, byte[] needle) {
        if (at + needle.length > buffer.limit()) {
            return false;
        }
        for (int i = 0; i < needle.length; i++) {
            if (buffer.get(at + i) != needle[i]) {
                return false;
            }
        }
        return true;
    }
}
