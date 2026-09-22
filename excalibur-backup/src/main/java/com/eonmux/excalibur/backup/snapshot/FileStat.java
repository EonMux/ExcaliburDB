package com.eonmux.excalibur.backup.snapshot;

/**
 * A file's length and last-modified time, as one observation.
 *
 * @param size             Length in bytes
 * @param lastModifiedNanos Last-modified time, in nanoseconds since the epoch
 * @author ExcaliburDB
 */
public record FileStat(long size, long lastModifiedNanos) {
}
