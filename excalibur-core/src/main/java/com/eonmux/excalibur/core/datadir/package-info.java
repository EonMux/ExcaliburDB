/**
 * Who may use a data directory, and the entries in it that say so.
 *
 * <p>Here, in core, because every process that opens a data directory depends on core and on
 * little else in common: the node, {@code excalibur-fsck} and {@code excalibur-backup}. One lock
 * that all three take is the exclusion; three implementations would be three chances for two of
 * them to disagree about which file is locked.</p>
 */
package com.eonmux.excalibur.core.datadir;
