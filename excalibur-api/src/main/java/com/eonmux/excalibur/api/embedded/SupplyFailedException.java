package com.eonmux.excalibur.api.embedded;

/**
 * A {@link ExcaliburEmbedded#supply} call whose ingestion run failed outright.
 *
 * <p>Distinct from a run that finished with some documents failed, which
 * {@link SupplyOutcome#documentsFailed()} counts: here the run itself could not complete - storage,
 * the lexical index or the embedding endpoint failed underneath it - so the outcome of the batch
 * is not known and supplying it again is the remedy. The engine's failure is kept as the cause.</p>
 *
 * <p>A type of the facade's own, so it can be caught by name with only {@code excalibur-api} on
 * the compile classpath.</p>
 */
public final class SupplyFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message What went wrong
     * @param cause   The engine's failure
     */
    SupplyFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
