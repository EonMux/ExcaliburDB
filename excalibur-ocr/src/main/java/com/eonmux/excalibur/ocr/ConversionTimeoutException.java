package com.eonmux.excalibur.ocr;

/** A conversion ran past its {@link Deadline} and was given up. */
public final class ConversionTimeoutException extends Exception {

    private static final long serialVersionUID = 1L;

    private final boolean shared;

    /**
     * @param message What ran out of time
     */
    public ConversionTimeoutException(String message) {
        this(message, false);
    }

    /**
     * @param message What ran out of time
     * @param shared  Whether another conversion took turns at the page readers meanwhile
     */
    public ConversionTimeoutException(String message, boolean shared) {
        super(message);
        this.shared = shared;
    }

    /**
     * Whether another conversion took turns at the engine's page readers while this one ran. A
     * conversion that ran alone ran out of time on its own pages, and would again; one that
     * shared the readers may have been slowed by the other, and might not.
     *
     * @return Whether the conversion shared the page readers
     */
    public boolean shared() {
        return shared;
    }
}
