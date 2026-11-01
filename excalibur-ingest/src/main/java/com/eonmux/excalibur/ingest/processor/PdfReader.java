package com.eonmux.excalibur.ingest.processor;

import com.eonmux.excalibur.ingest.source.FetchedDocument;

import java.time.Duration;

/**
 * How a node turns a PDF into the text that is chunked.
 *
 * <p>Three ways, one chosen by {@code excalibur.ingest.pdf-reader}: the PDF's own text layer,
 * read in process ({@link PdfText}); Indra OCR, run in process ({@link IndraPdfReader}); or an
 * external program ({@link PdfConverter}).</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations are thread-safe: every source a node crawls reads through the same one.</p>
 */
public interface PdfReader {

    /**
     * Names what a PDF's text depends on besides its bytes.
     *
     * <p>Recorded with each document as part of its decoding (see
     * {@link DocumentDecoder#decodingOf}), so changing how PDFs are read has every PDF read again
     * at the next re-crawl, and leaving it alone reads none of them again.</p>
     *
     * @return The reader's identity: empty for the text-layer reader, otherwise one line naming
     *         the engine and every setting that changes what it writes
     */
    String identity();

    /**
     * Reads one PDF.
     *
     * @param document The PDF
     * @return Its text, or the reason it has none that can be indexed
     * @throws InterruptedException   if the thread is interrupted while the PDF is read
     * @throws PdfConversionException if the reader fails on the document in a way that a later
     *                                attempt might not
     */
    DocumentDecoder.Decoded read(FetchedDocument document) throws InterruptedException;

    /**
     * Releases what the reader holds, such as loaded models, once no conversion is using it.
     *
     * <p>Called once, when the node shuts down, after ingestion has been asked to stop. A reader
     * that holds nothing has nothing to release.</p>
     *
     * @param wait How long to wait for conversions still running to stop
     * @return true when everything was released; false when a conversion was still running at
     *         the end of the wait and what it uses was left in place
     * @throws InterruptedException if interrupted while waiting
     */
    default boolean release(Duration wait) throws InterruptedException {
        return true;
    }
}
