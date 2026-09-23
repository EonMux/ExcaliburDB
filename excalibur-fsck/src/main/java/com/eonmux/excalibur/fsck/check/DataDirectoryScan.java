package com.eonmux.excalibur.fsck.check;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything one pass over a data directory established.
 *
 * @param dataDirectory The directory that was surveyed
 * @param files         One verdict per file found, in path order
 * @param findings      What is wrong between files rather than inside one
 * @param catalogRows   The catalog's rows, empty when the catalog could not be read
 * @param routingSlots  Canonical segment index per hash slot, empty when unreadable
 */
public record DataDirectoryScan(
    Path dataDirectory,
    List<FileCheck> files,
    List<ConsistencyFinding> findings,
    List<CatalogRecord> catalogRows,
    int[] routingSlots) {

    /**
     * Canonical constructor, defensively copying every collection.
     *
     * @throws NullPointerException if any component is null
     */
    public DataDirectoryScan {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        catalogRows = List.copyOf(Objects.requireNonNull(catalogRows, "catalogRows"));
        routingSlots = Objects.requireNonNull(routingSlots, "routingSlots").clone();
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

    /**
     * Counts the verdicts by status.
     *
     * @return How many files reached each status, every status present as a key
     */
    public Map<CheckStatus, Long> statusCounts() {
        Map<CheckStatus, Long> counts = new java.util.EnumMap<>(CheckStatus.class);
        for (CheckStatus status : CheckStatus.values()) {
            counts.put(status, 0L);
        }
        for (FileCheck file : files) {
            counts.merge(file.status(), 1L, Long::sum);
        }
        return counts;
    }

    /**
     * Reports whether anything found is damage.
     *
     * <p>A corrupt file the node rebuilds by itself on open is reported and does not count;
     * see {@link FileCheck#isDamage()}.</p>
     *
     * @return true if any file is corrupt, unreadable or missing, or any finding is damage
     */
    public boolean hasDamage() {
        return files.stream().anyMatch(FileCheck::isDamage)
            || findings.stream().anyMatch(f -> f.severity() == FindingSeverity.DAMAGE);
    }

    /**
     * Reports whether anything found could not be verified one way or the other.
     *
     * <p>Includes a file whose status is {@link CheckStatus#INTACT} but part of whose
     * content carried nothing to be weighed against - a segment written before slot
     * checksums existed is the case that matters. Every checksum such a file carries was
     * found and matched, which is what makes the status true, and its slot table is still
     * unproven, which is what {@code --strict} exists to ask about.</p>
     *
     * @return true if any file is unsealed, legacy-unprotected, unchecked, or only
     *         partly proven
     */
    public boolean hasUnverified() {
        return files.stream().anyMatch(file ->
            file.status() == CheckStatus.UNSEALED
                || file.status() == CheckStatus.LEGACY_UNPROTECTED
                || file.status() == CheckStatus.UNCHECKED
                || file.unverifiedContent());
    }

    /**
     * Counts corrupt files the node rebuilds by itself on open, which are not damage.
     *
     * @return How many corrupt verdicts {@link FileCheck#isDamage()} exempts
     */
    public long rebuiltOnOpenCount() {
        return files.stream()
            .filter(file -> file.status() == CheckStatus.CORRUPT && !file.isDamage())
            .count();
    }

    /**
     * Counts files that were checked in full but proven only in part.
     *
     * @return How many verdicts carry unverified content
     */
    public long partlyVerifiedCount() {
        return files.stream().filter(FileCheck::unverifiedContent).count();
    }
}
