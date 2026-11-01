// Ported from Indra OCR 1.1.2, cli.py (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr;

import java.util.List;
import java.util.Objects;

/**
 * A converted document: its markdown, and the pages that could not be read.
 *
 * @param markdown The pages' markdown, in page order, separated by a blank line, as Indra's
 *                 command line writes it
 * @param pages    The pages read, including those that failed
 * @param failures The pages that failed, which add nothing to the markdown
 */
public record ConvertedDocument(String markdown, int pages, List<PageFailure> failures) {

    public ConvertedDocument {
        Objects.requireNonNull(markdown, "markdown must not be null");
        failures = List.copyOf(failures);
    }

    /**
     * A page that failed, and why. One bad page does not end its document.
     *
     * @param page   The page number, from 1
     * @param reason The failure
     */
    public record PageFailure(int page, String reason) {
    }
}
