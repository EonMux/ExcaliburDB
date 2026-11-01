package com.eonmux.excalibur.ocr.runtime;

/**
 * A model failed to run: it rejected its inputs, or ONNX Runtime failed while running it.
 *
 * <p>Unchecked, because it is a failure of one page's reading rather than a condition callers
 * plan for; a document reader turns it into that page's error.</p>
 */
public final class ModelRunException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message What failed
     */
    public ModelRunException(String message) {
        super(message);
    }

    /**
     * @param message What failed
     * @param cause   ONNX Runtime's failure
     */
    public ModelRunException(String message, Throwable cause) {
        super(message, cause);
    }
}
