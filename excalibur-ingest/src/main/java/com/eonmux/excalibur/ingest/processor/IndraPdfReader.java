package com.eonmux.excalibur.ingest.processor;

import com.eonmux.excalibur.ingest.source.DocumentLimits;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ocr.ConversionTimeoutException;
import com.eonmux.excalibur.ocr.ConvertedDocument;
import com.eonmux.excalibur.ocr.Deadline;
import com.eonmux.excalibur.ocr.IndraOcr;
import com.eonmux.excalibur.ocr.UnreadablePdfException;
import com.eonmux.excalibur.ocr.model.ModelUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Reads PDFs with Indra OCR, in process: the text layer where it can be trusted, OCR elsewhere,
 * as markdown in reading order.
 *
 * <h2>Outcomes</h2>
 * <ul>
 *   <li><b>Text.</b> The document's markdown, without the control characters a broken font map
 *       leaves, judged as every document is ({@link TextContent#isText}). A page Indra could not
 *       read is logged and adds nothing, as on Indra's own command line; the rest of the document
 *       is indexed.</li>
 *   <li><b>Refused</b>, like the text-layer reader refuses it: a PDF that cannot be opened,
 *       damaged or locked with a password.</li>
 *   <li><b>Failed and remembered</b> (see {@link PdfConversions}): a conversion that ran past the
 *       timeout or wrote more text than a document may have.</li>
 *   <li><b>Failed and retried</b> by the next crawl: models that could not be downloaded or
 *       loaded, and a document every page of which failed, which says more about the engine
 *       than about the document.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe.</p>
 */
public final class IndraPdfReader implements PdfReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndraPdfReader.class);

    private final IndraOcr ocr;
    private final PdfConversions conversions;

    private IndraPdfReader(IndraOcr ocr, PdfConversions conversions) {
        this.ocr = ocr;
        this.conversions = conversions;
    }

    /**
     * A reader over an OCR engine.
     *
     * @param ocr         The engine, owned by the reader from now on and closed by
     *                    {@link #release}
     * @param timeout     The longest one document may take
     * @param parallelism How many documents may be converted at once
     * @return The reader
     * @throws IllegalArgumentException if a bound is not positive
     */
    public static IndraPdfReader of(IndraOcr ocr, Duration timeout, int parallelism) {
        Objects.requireNonNull(ocr, "ocr must not be null");
        return new IndraPdfReader(ocr, new PdfConversions(timeout, parallelism));
    }

    /**
     * Names the engine and every option that changes what it writes.
     *
     * @return The engine's identity
     */
    @Override
    public String identity() {
        return ocr.identity();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The models are fetched and loaded before the conversion's timeout starts, so a first
     * download, however slow, is not charged to the document that prompted it.</p>
     */
    @Override
    public DocumentDecoder.Decoded read(FetchedDocument document) throws InterruptedException {
        try {
            ocr.prepare();
        } catch (ModelUnavailableException e) {
            throw new PdfConversionException("Indra OCR could not read "
                + document.getDocumentId() + ": " + e.getMessage(), e);
        }
        return conversions.convert(document, this::convert);
    }

    private DocumentDecoder.Decoded convert(FetchedDocument document, long deadline)
            throws InterruptedException, PdfConversions.Unrepeatable {
        String documentId = document.getDocumentId();
        ConvertedDocument converted;
        try {
            converted = ocr.convert(document.contentView(), new Deadline(deadline));
        } catch (UnreadablePdfException e) {
            return DocumentDecoder.Decoded.refused("a PDF Indra OCR cannot open: "
                + e.getMessage());
        } catch (ConversionTimeoutException e) {
            String late = "Indra OCR did not finish " + documentId + " within "
                + conversions.timeout().toSeconds() + "s";
            if (e.shared()) {
                // Other conversions took turns at the page readers meanwhile, so the time was not
                // the document's alone: failed for now, and tried again by a later crawl.
                throw new PdfConversionException(late + " while other PDFs were converted "
                    + "alongside it; a later crawl tries it again");
            }
            throw new PdfConversions.Unrepeatable(late + "; raise "
                + "excalibur.ingest.pdf-converter-timeout-seconds if it is that large");
        } catch (ModelUnavailableException e) {
            throw new PdfConversionException("Indra OCR could not read " + documentId + ": "
                + e.getMessage(), e);
        }
        String text = PdfText.withoutControlCharacters(converted.markdown()).strip();
        if (!converted.failures().isEmpty()) {
            String pages = converted.failures().stream()
                .map(f -> f.page() + " (" + f.reason() + ")")
                .collect(Collectors.joining(", "));
            if (converted.failures().size() == converted.pages()) {
                throw new PdfConversionException("Indra OCR could not read any page of "
                    + documentId + ": " + pages);
            }
            LOGGER.warn("Indra OCR could not read {} of the {} pages of {}, indexed without "
                + "them: {}", converted.failures().size(), converted.pages(), documentId, pages);
        }
        if (utf8Length(text) > DocumentLimits.MAX_DOCUMENT_BYTES) {
            throw new PdfConversions.Unrepeatable("Indra OCR wrote more than "
                + DocumentLimits.MAX_DOCUMENT_BYTES + " bytes of text for " + documentId);
        }
        if (!text.isEmpty() && !TextContent.isText(text)) {
            return DocumentDecoder.Decoded.refused("Indra OCR read it as text that is not "
                + "readable characters");
        }
        return DocumentDecoder.Decoded.text(text);
    }

    /**
     * Stops the engine's conversions and releases its models once its page readers stop.
     *
     * @param wait How long to wait for pages being read
     * @return true when the models were released
     * @throws InterruptedException if interrupted while waiting
     */
    @Override
    public boolean release(Duration wait) throws InterruptedException {
        return ocr.close(wait);
    }

    /** The length of a string in UTF-8, without encoding it. */
    private static long utf8Length(String text) {
        long bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }
}
