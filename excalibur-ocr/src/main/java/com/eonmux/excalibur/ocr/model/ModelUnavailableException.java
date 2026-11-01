package com.eonmux.excalibur.ocr.model;

import java.io.IOException;

/**
 * The models a conversion needs are not in the model directory and could not be put there: the
 * download is turned off, failed, or delivered other bytes than the pinned ones.
 */
public final class ModelUnavailableException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message What is missing and what would provide it
     */
    public ModelUnavailableException(String message) {
        super(message);
    }

    /**
     * @param message What is missing and what would provide it
     * @param cause   The failure behind it
     */
    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
