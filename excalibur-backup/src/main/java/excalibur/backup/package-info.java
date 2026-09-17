/**
 * Verified snapshot, verify and restore for an ExcaliburDB data directory.
 *
 * <p>Copying a running node's directory is not a backup: the segments are memory-mapped
 * and written continuously, nothing orders one file against another, and the result is a
 * torn image that will be discovered to be useless at the worst possible moment. What is
 * here instead is an image taken from a stopped node, described by a manifest that names
 * every file with its length and its checksums, checkable without being restored, and
 * restored by staging beside the target and renaming on.</p>
 */
package excalibur.backup;
