/**
 * The file primitives a snapshot is built out of: digesting, copying and publishing.
 *
 * <p>Each of the three exists because the obvious spelling of it is wrong for this engine's
 * files. A digest has to cover the holes in a sparsely allocated mapping, a copy has to
 * preserve them, and a publish has to order the bytes against the name that makes them
 * authoritative.</p>
 */
package excalibur.backup.io;
