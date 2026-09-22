package com.eonmux.excalibur.backup.snapshot;

import com.eonmux.excalibur.backup.manifest.EpochSource;

import java.util.Objects;

/**
 * The epoch a data directory is coherent at, and how that was established.
 *
 * @param epoch  The highest epoch stamped on anything in the directory
 * @param source Which files that was read from
 * @param detail A sentence an operator can act on
 * @author ExcaliburDB
 */
public record EpochStamp(long epoch, EpochSource source, String detail) {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException     if source or detail is null
     * @throws IllegalArgumentException if the epoch is negative
     */
    public EpochStamp {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(detail, "detail");
        if (epoch < 0) {
            throw new IllegalArgumentException("An epoch cannot be " + epoch);
        }
    }
}
