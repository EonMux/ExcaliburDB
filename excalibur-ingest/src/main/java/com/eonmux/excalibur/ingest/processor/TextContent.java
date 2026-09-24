package com.eonmux.excalibur.ingest.processor;

import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.TextEncoding;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * Decides whether what a source handed back is text, and decodes it when it is.
 *
 * <h2>Why this exists</h2>
 * <p>A crawl with no extension filter lists every file under its root, and a document's content
 * type is guessed from its extension, so neither says what a file actually holds: a {@code .txt}
 * can be a core dump and a {@code .dat} can be YAML. Decoded the way {@code new String(bytes,
 * charset)} decodes - leniently, substituting U+FFFD for anything it cannot read - a PNG becomes
 * kilobytes of replacement characters, and those are then chunked, term-indexed, embedded at
 * whatever an embedding call costs, and returned as a search result. The bytes are the only
 * witness to what a file is, so this asks them rather than its name.</p>
 *
 * <h2>What counts as text</h2>
 * <p>Two rules, applied to the decoded characters rather than to the raw bytes, so that a
 * document in an encoding the source declared is judged in that encoding:</p>
 * <ol>
 *   <li><b>No U+0000.</b> Decisive. No text format contains it and every binary container is
 *       full of it, which is the same test {@code git} uses to call a file binary. Applied after
 *       decoding rather than before, because UTF-16 text is full of zero <em>bytes</em> and holds
 *       no zero <em>characters</em>; a source that declares UTF-16 is taken at its word.</li>
 *   <li><b>Under a third replacement characters.</b> A guard for the binary formats that happen
 *       to hold no zero byte. Deliberately not a stricter bar: a UTF-8 crawl reading a
 *       windows-1252 document sees one U+FFFD per accented character, which is a few percent of
 *       a European-language text and nowhere near a third, and that document is still worth
 *       indexing imperfectly. Binary read as text runs well over half.</li>
 * </ol>
 *
 * <p>Both rules read the whole document rather than a prefix. Scanning it costs one pass over
 * text that is about to be chunked, embedded and written, and a prefix window would pass a text
 * header with a blob appended to it - which is exactly what most binary formats are.</p>
 *
 * <h2>Markup</h2>
 * <p>A document whose content type is HTML or XHTML is reduced to the text it shows once it has
 * passed both rules; see {@link HtmlText}. The rules are applied to the characters as decoded,
 * before the markup goes, because stripping first would remove the angle brackets a binary file
 * happens to contain and judge what little was left. Every other type is indexed as it reads,
 * angle brackets included: in XML, Markdown or source code they are content.</p>
 *
 * @author ExcaliburDB
 */
public final class TextContent {

    /** Decoded binary is full of these; decoded text has none. */
    private static final char NUL = '\u0000';

    /** Appended to a decoding whose markup is reduced to the text it shows. */
    private static final String MARKUP_DECODING = "+markup";

    /** What a lenient decoder substitutes for bytes it cannot read. */
    private static final char REPLACEMENT = '\uFFFD';

    /** Numerator of the share of replacement characters that stops a document being text. */
    private static final int MAX_REPLACEMENT_NUMERATOR = 1;

    /** Denominator of that share. One in three. */
    private static final int MAX_REPLACEMENT_DENOMINATOR = 3;

    private TextContent() {
    }

    /**
     * Decodes a document's bytes as text, or reports that they are not text.
     *
     * <p>A byte-order mark the content opens with is dropped rather than kept as the first
     * character; see {@link TextEncoding#decode}. The characters are returned as they read,
     * markup included; a document with a content type goes through
     * {@link #decode(byte[], Charset, String)}.</p>
     *
     * @param content The bytes the source handed back
     * @param charset The encoding the source declared them to be in
     * @return The decoded text, or {@code null} when {@code content} is not text in
     *         {@code charset}
     * @throws NullPointerException if content or charset is null
     */
    public static String decode(byte[] content, Charset charset) {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(charset, "charset must not be null");

        String text = TextEncoding.decode(content, charset);
        return isText(text) ? text : null;
    }

    /**
     * Decodes a document's bytes as text, reducing HTML and XHTML to the text they show.
     *
     * <p>This is how every source's documents become the text that is chunked: the encoding is
     * the one the source declared, the rules are the ones the class describes, and markup is
     * stripped for the content types {@link HtmlText#isMarkup} names.</p>
     *
     * @param content     The bytes the source handed back
     * @param charset     The encoding the source declared them to be in
     * @param contentType The content type the source reported, or null when it reported none
     * @return The document's text, or {@code null} when {@code content} is not text in
     *         {@code charset}
     * @throws NullPointerException if content or charset is null
     */
    public static String decode(byte[] content, Charset charset, String contentType) {
        String text = decode(content, charset);
        if (text == null || !HtmlText.isMarkup(contentType)) {
            return text;
        }
        return HtmlText.extract(text);
    }

    /**
     * Decodes a fetched document by the encoding and content type its source reported.
     *
     * @param document What the source handed back
     * @return The document's text, or {@code null} when it is not text
     * @throws NullPointerException if document is null
     */
    public static String decode(FetchedDocument document) {
        Objects.requireNonNull(document, "document must not be null");
        return decode(document.getContent(), document.getCharset(), document.getContentType());
    }

    /**
     * Names how a fetched document's bytes become its text, apart from the bytes themselves.
     *
     * <p>{@link #decode(FetchedDocument)} depends on exactly two things besides the content: the
     * encoding the bytes are read in, and whether they are markup to be reduced to the text it
     * shows. This names both - {@code windows-1252}, or {@code UTF-8+markup} - and nothing else,
     * so it moves when the same bytes would decode to different text and not otherwise. A source
     * registered again with another {@code charset}, or a server that starts labelling the same
     * page with another encoding or as HTML, moves it; a new crawl delay, a moved modification
     * time or a content type that is still not markup do not.</p>
     *
     * <p>Recorded beside each indexed document, so a re-crawl can tell a document whose bytes
     * are unchanged but whose text would not be from one it can leave alone.</p>
     *
     * @param document What the source handed back
     * @return The decoding, never null
     * @throws NullPointerException if document is null
     */
    public static String decodingOf(FetchedDocument document) {
        Objects.requireNonNull(document, "document must not be null");
        String charset = document.getCharset().name();
        return HtmlText.isMarkup(document.getContentType()) ? charset + MARKUP_DECODING : charset;
    }

    /**
     * Applies both rules to already-decoded characters.
     *
     * <p>Separate from {@link #decode} so that a caller holding text rather than bytes - a
     * document supplied in a request body, which arrived as a JSON string - is judged by the
     * same rules.</p>
     *
     * @param text The decoded characters
     * @return Whether they are text
     * @throws NullPointerException if text is null
     */
    public static boolean isText(String text) {
        Objects.requireNonNull(text, "text must not be null");

        // Empty is text: a file with nothing in it has nothing binary in it either, and the
        // chunker reports it as the empty document it is rather than as one that was refused.
        if (text.isEmpty()) {
            return true;
        }

        int replacements = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == NUL) {
                return false;
            }
            if (c == REPLACEMENT) {
                replacements++;
            }
        }
        return (long) replacements * MAX_REPLACEMENT_DENOMINATOR
            < (long) text.length() * MAX_REPLACEMENT_NUMERATOR;
    }
}
