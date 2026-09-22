package com.eonmux.excalibur.backup.cli;

/**
 * The command line could not be understood.
 *
 * @author ExcaliburDB
 */
public final class UsageException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param detail What is wrong with the command line
     */
    public UsageException(String detail) {
        super(detail);
    }
}
