package com.eonmux.excalibur.ingest.processor;

import org.apache.lucene.analysis.charfilter.HTMLStripCharFilter;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Objects;

/**
 * Reduces an HTML or XHTML document to the text a reader of it sees.
 *
 * <h2>Why this exists</h2>
 * <p>Indexed as it was served, a web page is mostly not its content: tag names, attributes, class
 * lists, inline scripts and stylesheets outnumber its words on most real sites. Every one of them
 * is chunked, term-indexed and paid for at the embedding endpoint, a search for {@code div} or
 * {@code function} matches every page, and the vector computed for a chunk describes its
 * boilerplate rather than what it says. A crawl and a directory walk meet the same pages, so this
 * is applied where decoded text is produced for every source rather than inside either one.</p>
 *
 * <h2>What is kept</h2>
 * <p>The markup is parsed by Lucene's {@link HTMLStripCharFilter}, which is written against the
 * HTML specification rather than a pattern: it removes tags and comments, drops the contents of
 * {@code script} and {@code style} elements whole, and decodes named and numeric character
 * references, so {@code Caf&eacute;} is indexed as the word it is. Block-level elements and
 * {@code br} become line breaks, so the last word of one list item is not fused to the first
 * word of the next; inline elements vanish without a trace, because {@code Ana<b>lyt</b>ical}
 * is one word on the page.</p>
 *
 * <p>The indentation and blank lines left behind by a page's own layout are then collapsed: a
 * run of whitespace holding two or more line breaks becomes one paragraph break, a run holding
 * one becomes a line break. They carry nothing a reader sees, and left in they are what every
 * chunk returned as a search result would open with.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; every method is a pure function of its arguments.</p>
 */
public final class HtmlText {

    /** The media type of an HTML document. */
    private static final String TEXT_HTML = "text/html";

    /** The media type of an XHTML document, which is HTML's markup in XML's syntax. */
    private static final String APPLICATION_XHTML = "application/xhtml+xml";

    private HtmlText() {
        throw new AssertionError("Utility class");
    }

    /**
     * Whether a content type names a document this extracts text from.
     *
     * <p>Compared on the media type alone: an HTTP {@code Content-Type} carries parameters
     * ({@code text/html; charset=utf-8}) and may be in any case.</p>
     *
     * @param contentType The document's content type, or null when it has none
     * @return Whether it is HTML or XHTML
     */
    public static boolean isMarkup(String contentType) {
        if (contentType == null) {
            return false;
        }
        int semicolon = contentType.indexOf(';');
        String mediaType = (semicolon < 0 ? contentType : contentType.substring(0, semicolon))
            .trim().toLowerCase(Locale.ROOT);
        return mediaType.equals(TEXT_HTML) || mediaType.equals(APPLICATION_XHTML);
    }

    /**
     * Extracts the text of a decoded HTML document.
     *
     * @param html The document's characters
     * @return Its text, with block boundaries as line breaks; empty when it shows none
     * @throws NullPointerException if html is null
     */
    public static String extract(String html) {
        Objects.requireNonNull(html, "html must not be null");
        StringBuilder text = new StringBuilder(html.length());
        char[] buffer = new char[8192];
        try (Reader stripped = new HTMLStripCharFilter(new StringReader(html))) {
            int read;
            while ((read = stripped.read(buffer)) != -1) {
                text.append(buffer, 0, read);
            }
        } catch (IOException e) {
            // A StringReader performs no I/O, so the filter has nothing to fail on.
            throw new UncheckedIOException("Reading markup from memory failed", e);
        }
        return collapseLayout(text);
    }

    /**
     * Collapses the whitespace markup leaves between blocks, and trims the ends.
     *
     * <p>A run of whitespace within a line is kept as it is, so the alignment of preformatted
     * text survives; only the runs that span lines are rewritten.</p>
     *
     * @param text The stripped text
     * @return The same text with each line-spanning run of whitespace reduced
     */
    private static String collapseLayout(CharSequence text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                out.append(c);
                i++;
                continue;
            }
            int start = i;
            int lineBreaks = 0;
            while (i < length && Character.isWhitespace(text.charAt(i))) {
                if (text.charAt(i) == '\n') {
                    lineBreaks++;
                }
                i++;
            }
            if (out.isEmpty() || i == length) {
                continue;
            }
            if (lineBreaks >= 2) {
                out.append("\n\n");
            } else if (lineBreaks == 1) {
                out.append('\n');
            } else {
                out.append(text, start, i);
            }
        }
        return out.toString();
    }
}
