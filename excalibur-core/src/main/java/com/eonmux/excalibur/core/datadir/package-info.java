/**
 * Who may use a data directory, the entries in it that say so, and which of its entries are
 * data at all.
 *
 * <p>Here, in core, because every process that opens a data directory depends on core and on
 * little else in common: the node, {@code excalibur-fsck} and {@code excalibur-backup}. One lock
 * that all three take is the exclusion; three implementations would be three chances for two of
 * them to disagree about which file is locked. The same holds for what a data directory
 * contains: the node, the checker, the snapshot and the restore list it through
 * {@link com.eonmux.excalibur.core.datadir.DataDirectoryContents}, so that none of them copies,
 * moves or reports an entry another passes over.</p>
 */
package com.eonmux.excalibur.core.datadir;
