package com.eonmux.excalibur.core.memory;

/**
 * What one open resource costs the process in memory mappings and file descriptors.
 *
 * <p>Both are kernel-enforced limits — {@code vm.max_map_count} and {@code RLIMIT_NOFILE} — and
 * both fail badly when they are reached. An exhausted mapping count surfaces as an
 * {@code OutOfMemoryError} naming map failure while there is plenty of heap; an exhausted
 * descriptor table breaks whatever happens to open a file next, which is rarely the component that
 * used them up. Anything that holds tenants open therefore has to budget for these before it
 * allocates, and a budget can only be as honest as what each resource reports.
 *
 * <h2>Measured, not declared</h2>
 * <p>Implementations report what they hold <em>now</em> rather than a constant. Some footprints
 * genuinely are fixed — a column store with one mapping per column — but others move with the data:
 * a Lucene index maps its segment files, so merging changes the count without anything reopening.
 * A constant would be wrong for those in the direction that matters, since undercounting is what
 * lets the process walk into the limit the budget exists to keep it away from.
 *
 * <p>A caller that has to keep a running total must remember the figures it charged rather than
 * re-reading them at release time, because a resource whose cost moved between the two would
 * otherwise leave the total drifting away from reality in one direction or the other.
 *
 * @author ExcaliburDB
 */
public interface MappedFootprint {

    /**
     * How many memory mappings this resource currently holds.
     *
     * @return The count, never negative
     */
    long mappings();

    /**
     * How many file descriptors this resource currently holds open.
     *
     * <p>Often zero for a mapped resource: a mapping outlives the channel that created it, so a
     * store that maps its files at open and closes the channels holds none. Reporting zero is a
     * statement about the implementation, not an admission that it was not measured.
     *
     * @return The count, never negative
     */
    long descriptors();
}
