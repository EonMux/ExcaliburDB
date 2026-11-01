package com.eonmux.excalibur.ocr;

/** A conversion ran past its {@link Deadline} and was given up. */
public final class ConversionTimeoutException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * @param message What ran out of time
     */
    public ConversionTimeoutException(String message) {
        super(message);
    }
}
