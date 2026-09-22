package com.eonmux.excalibur.fsck.check;

import java.util.List;
import java.util.Objects;

/**
 * What reading {@code catalog.dat} found: a verdict on the file and the rows it holds.
 *
 * <p>The rows come back alongside the verdict because the catalog is the only statement of
 * which segment files a store believes it owns, and that statement is what the cross-file
 * checks are made against.</p>
 *
 * @param check   The verdict on the file itself
 * @param records Every row read, damaged ones included, in file order
 */
public record CatalogInspection(FileCheck check, List<CatalogRecord> records) {

    /**
     * Canonical constructor, defensively copying the row list.
     *
     * @throws NullPointerException if either component is null
     */
    public CatalogInspection {
        Objects.requireNonNull(check, "check");
        records = List.copyOf(Objects.requireNonNull(records, "records"));
    }
}
