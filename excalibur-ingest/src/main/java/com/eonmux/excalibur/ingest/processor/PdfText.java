package com.eonmux.excalibur.ingest.processor;

import com.eonmux.excalibur.ingest.source.FetchedDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Reads the text layer of a PDF, in process, with nothing installed.
 *
 * <h2>What it reads</h2>
 * <p>The text a PDF already carries: what was typeset into it, or what an earlier OCR pass hid
 * behind a scan. Pages are read in the order their content streams draw them, which for typeset
 * documents - two-column papers included - is the order they are read in; ligatures are split
 * into their letters, so {@code ﬁnd} is indexed as {@code find}. Pages are separated by a blank
 * line, so the last sentence of one page is not fused to the first of the next.</p>
 *
 * <h2>What it cannot read</h2>
 * <p>A scanned page is a picture of text, and has no text layer to read. A document with none
 * at all is refused, naming the setting that reads it - {@code excalibur.ingest.pdf-reader=indra},
 * which OCRs - rather than indexed as an empty document that no query can ever find.</p>
 *
 * <p>Control characters, which a font with no character mapping leaves behind, are dropped. The
 * rest is then judged by the rules every text document is judged by ({@link TextContent#isText}),
 * so a document whose fonts map to nothing but replacement characters is refused as unreadable
 * rather than indexed as noise.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; each call parses its own document.</p>
 */
final class PdfText implements PdfReader {

    /** The one instance: the reader holds no state. */
    static final PdfText INSTANCE = new PdfText();

    /** What the refusal of a PDF with no text says to do about it. */
    static final String NO_TEXT_LAYER = "a PDF with no text layer, such as a scan; set "
        + "excalibur.ingest.pdf-reader=indra to read it with OCR";

    private PdfText() {
    }

    /**
     * The text-layer reader's identity: none, since its text depends on the bytes alone.
     *
     * @return The empty string
     */
    @Override
    public String identity() {
        return "";
    }

    @Override
    public DocumentDecoder.Decoded read(FetchedDocument document) throws InterruptedException {
        return read(Objects.requireNonNull(document, "document must not be null").contentView());
    }

    /**
     * Reads a PDF's text layer.
     *
     * <p>Interruptible at each page, so a stop reaches a run reading a two-thousand-page book
     * without waiting for the book to finish.</p>
     *
     * @param content The PDF, read from its position to its limit; not modified
     * @return Its text, or the reason it has none that can be indexed
     * @throws InterruptedException if the thread is interrupted while reading
     * @throws NullPointerException if content is null
     */
    static DocumentDecoder.Decoded read(ByteBuffer content) throws InterruptedException {
        Objects.requireNonNull(content, "content must not be null");
        try (PDDocument pdf = Loader.loadPDF(new RandomAccessReadBuffer(content.slice()))) {
            String text = withoutControlCharacters(textOf(pdf));
            if (text.isBlank()) {
                return DocumentDecoder.Decoded.refused(NO_TEXT_LAYER);
            }
            if (!TextContent.isText(text)) {
                return DocumentDecoder.Decoded.refused("a PDF whose text layer does not map to "
                    + "readable characters; set excalibur.ingest.pdf-reader=indra to read it "
                    + "with OCR");
            }
            return DocumentDecoder.Decoded.text(text);
        } catch (InterruptedIOException e) {
            // Loading can be cut short the same way; it is a stop, not a damaged file.
            throw new InterruptedException(e.getMessage());
        } catch (InvalidPasswordException e) {
            return DocumentDecoder.Decoded.refused("an encrypted PDF that cannot be opened "
                + "without a password");
        } catch (IOException e) {
            return DocumentDecoder.Decoded.refused("not a readable PDF: " + e.getMessage());
        }
    }

    /** Every page's text in one pass, noticing a stop as each page begins. */
    private static String textOf(PDDocument pdf) throws IOException, InterruptedException {
        PDFTextStripper stripper = new InterruptibleStripper();
        stripper.setLineSeparator("\n");
        stripper.setPageEnd("\n\n");
        try {
            return stripper.getText(pdf).strip();
        } catch (InterruptedIOException e) {
            throw new InterruptedException(e.getMessage());
        }
    }

    /**
     * A text stripper that gives up at the next page once its thread is interrupted.
     *
     * <p>PDFBox runs the whole document in one call and checks nothing between pages, so this is
     * where a stop can be seen; the exception unwinds through PDFBox as the I/O failure it is
     * declared to be and is turned back into an interruption above.</p>
     */
    private static final class InterruptibleStripper extends PDFTextStripper {

        @Override
        protected void startPage(PDPage page) throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Stopped reading a PDF at page "
                    + getCurrentPageNo());
            }
            super.startPage(page);
        }
    }

    /**
     * Drops the control characters a PDF's text can carry, keeping tabs and line breaks.
     *
     * <p>A glyph whose font maps it to no character comes out as one, most often U+0000, which
     * would otherwise have the whole document judged binary over a single unmapped symbol.</p>
     *
     * @param text Extracted text
     * @return The same text without C0 controls other than tab, line feed and carriage return
     */
    static String withoutControlCharacters(String text) {
        StringBuilder kept = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean control = c < 0x20 && c != '\t' && c != '\n' && c != '\r';
            if (control && kept == null) {
                kept = new StringBuilder(text.length()).append(text, 0, i);
            } else if (!control && kept != null) {
                kept.append(c);
            }
        }
        return kept == null ? text : kept.toString();
    }
}
