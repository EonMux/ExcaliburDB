/**
 * Taking a consistent, self-describing image of a node's data directory.
 *
 * <p>Holds the three decisions a snapshot is: which files belong in it, what epoch it is
 * coherent at, and how to tell whether anything moved while it was being taken.</p>
 */
package excalibur.backup.snapshot;
