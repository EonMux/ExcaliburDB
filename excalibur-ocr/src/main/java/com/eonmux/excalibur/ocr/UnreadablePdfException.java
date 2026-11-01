// Ported from Indra OCR 1.1.2, pipeline.py's InputError (Apache License 2.0); see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr;

/**
 * A PDF that cannot be opened: damaged, not a PDF at all, or locked with a password. Its bytes
 * decide this, so reading them again ends the same way.
 */
public final class UnreadablePdfException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * @param message Why it cannot be opened
     * @param cause   The parser's failure
     */
    public UnreadablePdfException(String message, Throwable cause) {
        super(message, cause);
    }
}
