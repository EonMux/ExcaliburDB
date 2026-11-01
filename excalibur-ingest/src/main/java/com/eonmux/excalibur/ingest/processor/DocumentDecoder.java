package com.eonmux.excalibur.ingest.processor;

import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.PdfSignature;

import java.time.Duration;
import java.util.Objects;

/**
 * Turns what a source handed back into the text that is chunked, or says why there is none.
 *
 * <p>The one gate every fetched document passes through, on the first crawl, on every re-crawl
 * and on its way to the graph's extractor, so they cannot read the same bytes differently.</p>
 *
 * <h2>Two kinds of document</h2>
 * <ul>
 *   <li><b>A PDF</b>, recognised by its header and not by its name or declared type (see
 *       {@link PdfSignature}), is read by the node's {@link PdfReader}: by default its text
 *       layer, in process (see {@link PdfText}), which refuses a PDF that has none with the
 *       setting that would read it; or with OCR, which reads text layers and scanned pages
 *       alike.</li>
 *   <li><b>Everything else</b>, decoded in the encoding its source declared and judged by the
 *       rules in {@link TextContent}, markup reduced to the text it shows.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable and thread-safe; a reader that converts bounds its own concurrency.</p>
 */
public final class DocumentDecoder {

    /** The decoding a PDF read by the built-in text-layer reader is recorded under. */
    static final String BUILT_IN_PDF = "pdf";

    /** Prefixes a converting reader's identity in the decoding of a PDF it read. */
    private static final String CONVERTED_PDF = "pdf+";

    private static final DocumentDecoder BUILT_IN = new DocumentDecoder(PdfText.INSTANCE);

    private final PdfReader pdfReader;
    private final String pdfDecoding;

    private DocumentDecoder(PdfReader pdfReader) {
        this.pdfReader = pdfReader;
        String identity = pdfReader.identity();
        this.pdfDecoding = identity == null || identity.isEmpty() ? BUILT_IN_PDF
            : CONVERTED_PDF + identity;
    }

    /**
     * The decoder a node uses by default, reading PDFs' text layers in process.
     *
     * @return The decoder
     */
    public static DocumentDecoder builtIn() {
        return BUILT_IN;
    }

    /**
     * A decoder that reads every PDF with the given reader.
     *
     * @param pdfReader The reader
     * @return The decoder
     * @throws NullPointerException if pdfReader is null
     */
    public static DocumentDecoder reading(PdfReader pdfReader) {
        return new DocumentDecoder(Objects.requireNonNull(pdfReader,
            "pdfReader must not be null"));
    }

    /**
     * Decodes a fetched document.
     *
     * <p>Converting a PDF can take minutes, so this is interruptible: a stop delivered while it
     * runs ends it.</p>
     *
     * @param document What the source handed back
     * @return Its text, or the reason it has none that can be indexed
     * @throws InterruptedException   if the thread is interrupted while a PDF is read
     * @throws PdfConversionException if the configured reader fails on the document
     * @throws NullPointerException   if document is null
     */
    public Decoded decode(FetchedDocument document) throws InterruptedException {
        Objects.requireNonNull(document, "document must not be null");
        if (PdfSignature.isPdf(document.contentView())) {
            return pdfReader.read(document);
        }
        String text = TextContent.decode(document.getContent(), document.getCharset(),
            document.getContentType());
        return text != null ? Decoded.text(text)
            : Decoded.refused("its " + document.getContentLength() + " bytes are not text in "
                + document.getCharset());
    }

    /**
     * Names how a fetched document's bytes become its text, apart from the bytes themselves.
     *
     * <p>Recorded beside each indexed document, so a re-crawl can tell a document whose bytes are
     * unchanged but whose text would not be from one it can leave alone. For a text document
     * this is its encoding and whether it is markup (see {@link TextContent#decodingOf}). For a
     * PDF it is how PDFs are read: {@code pdf} for the built-in reader, or {@code pdf+} and the
     * reader's {@link PdfReader#identity identity} - so choosing another reader, or changing how
     * it is asked to convert, has every PDF read again on the next re-crawl.</p>
     *
     * @param document What the source handed back
     * @return The decoding, never null
     * @throws NullPointerException if document is null
     */
    public String decodingOf(FetchedDocument document) {
        Objects.requireNonNull(document, "document must not be null");
        if (PdfSignature.isPdf(document.contentView())) {
            return pdfDecoding();
        }
        return TextContent.decodingOf(document.getCharset(), document.getContentType());
    }

    /**
     * Whether a PDF recorded with a decoding would be read another way now.
     *
     * <p>Answerable without the document's bytes, since how a PDF is read depends on the node's
     * configuration alone; so a re-crawl can tell it must fetch a document that a conditional
     * request would report unchanged.</p>
     *
     * @param recorded The decoding a document was indexed with, or null when none was recorded
     * @return true when {@code recorded} names a way of reading PDFs other than this decoder's
     */
    public boolean readsOtherwise(String recorded) {
        if (recorded == null || !(recorded.equals(BUILT_IN_PDF)
                || recorded.startsWith(CONVERTED_PDF))) {
            return false;
        }
        return !recorded.equals(pdfDecoding());
    }

    /**
     * Releases what the PDF reader holds; see {@link PdfReader#release}.
     *
     * @param wait How long to wait for conversions still running
     * @return true when everything was released
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean release(Duration wait) throws InterruptedException {
        return pdfReader.release(wait);
    }

    /**
     * The decoding every PDF this decoder reads is recorded under.
     *
     * @return {@code pdf} for the text-layer reader, otherwise {@code pdf+} and the reader's
     *         identity
     */
    public String pdfDecoding() {
        return pdfDecoding;
    }

    /**
     * What decoding one document produced.
     *
     * @param text    The document's text, or null when it has none that can be indexed
     * @param refusal Why it has none, or null when it has text
     */
    public record Decoded(String text, String refusal) {

        /**
         * @throws IllegalArgumentException unless exactly one of text and refusal is given
         */
        public Decoded {
            if ((text == null) == (refusal == null)) {
                throw new IllegalArgumentException("exactly one of text and refusal is given");
            }
        }

        /**
         * A document that decoded to text, which may be empty.
         *
         * @param text The text
         * @return The outcome
         */
        public static Decoded text(String text) {
            return new Decoded(Objects.requireNonNull(text, "text must not be null"), null);
        }

        /**
         * A document with no text that can be indexed.
         *
         * @param reason Why, phrased to follow "Skipping document X: "
         * @return The outcome
         */
        public static Decoded refused(String reason) {
            return new Decoded(null, Objects.requireNonNull(reason, "reason must not be null"));
        }

        /**
         * Whether the document has text that can be chunked.
         *
         * @return true when it decoded to text
         */
        public boolean isText() {
            return text != null;
        }
    }
}
