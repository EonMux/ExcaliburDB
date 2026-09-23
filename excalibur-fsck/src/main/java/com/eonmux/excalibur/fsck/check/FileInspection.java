package com.eonmux.excalibur.fsck.check;

import java.util.List;
import java.util.Objects;

/**
 * What reading one file established: its kind, the verdict on it, and what it says about
 * other files.
 *
 * <p>Two formats are statements about the rest of the directory as well as files in their
 * own right. The catalog is the only record of which segments a store believes it owns, and
 * the routing directory the only record of which segment each hash slot belongs to. The
 * directory survey checks every other file against those two statements, so reading either
 * returns what it holds beside the verdict on it; for every other kind both are empty.</p>
 *
 * @param kind           What the file was classified as
 * @param check          The verdict on the file itself
 * @param catalogRecords Every row read, damaged ones included, when the file is the catalog;
 *                       empty otherwise
 * @param routingSlots   Canonical segment index per hash slot, when the file is the routing
 *                       directory and could be read; empty otherwise
 */
public record FileInspection(FileKind kind, FileCheck check, List<CatalogRecord> catalogRecords,
                             int[] routingSlots) {

    /**
     * Canonical constructor, defensively copying both collections.
     *
     * @throws NullPointerException if any component is null
     */
    public FileInspection {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(check, "check");
        catalogRecords = List.copyOf(Objects.requireNonNull(catalogRecords, "catalogRecords"));
        routingSlots = Objects.requireNonNull(routingSlots, "routingSlots").clone();
    }

    /**
     * Records the verdict on a file that says nothing about any other.
     *
     * @param kind  What the file was classified as
     * @param check The verdict on it
     * @return The inspection, with no rows and no slots
     */
    static FileInspection of(FileKind kind, FileCheck check) {
        return new FileInspection(kind, check, List.of(), new int[0]);
    }

    /**
     * Returns the routing slot table.
     *
     * @return A copy of the canonical segment index per slot
     */
    @Override
    public int[] routingSlots() {
        return routingSlots.clone();
    }
}
