package com.eonmux.excalibur.ocr;

import com.eonmux.excalibur.ocr.pipeline.PageText;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Reads one page of a PDF: Indra's {@code Converter.pdf_page}, behind the document-level work of
 * {@link IndraOcr}.
 *
 * <p>Implementations are thread-safe: pages of one document, and of several, are read on many
 * threads at once, each with its own {@link PDDocument}.</p>
 */
interface PageConverter extends AutoCloseable {

    /**
     * Reads one page.
     *
     * @param document The document, owned by the calling thread for the duration of the call
     * @param index    The page, from 0
     * @param deadline When the conversion must be done by, checked between the page's steps
     * @return The page's text
     * @throws InterruptedException       if the thread is interrupted
     * @throws ConversionTimeoutException if the deadline passes
     * @throws Exception                  for a failure of this page alone, which becomes its
     *                                    error
     */
    PageText read(PDDocument document, int index, Deadline deadline) throws Exception;

    /** Releases the models. */
    @Override
    void close();
}
