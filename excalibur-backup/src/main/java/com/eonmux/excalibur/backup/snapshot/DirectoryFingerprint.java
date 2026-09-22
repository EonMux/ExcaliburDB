package com.eonmux.excalibur.backup.snapshot;

import java.util.Map;
import java.util.Objects;

/**
 * What a data directory looked like at one moment, cheaply.
 *
 * <p>Sizes and timestamps rather than contents, because the point is to compare two
 * observations taken either side of a copy that may itself have taken minutes, and a
 * comparison that costs another full read of the directory would double the window it is
 * trying to measure.</p>
 *
 * @param files Relative path to what was observed of it
 * @author ExcaliburDB
 */
public record DirectoryFingerprint(Map<String, FileStat> files) {

    /**
     * Canonical constructor, copying the map.
     *
     * @throws NullPointerException if files is null
     */
    public DirectoryFingerprint {
        files = Map.copyOf(Objects.requireNonNull(files, "files"));
    }
}
