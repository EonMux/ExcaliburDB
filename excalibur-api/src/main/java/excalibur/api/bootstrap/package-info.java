/**
 * Bootstrap seams of the ExcaliburDB application.
 *
 * <p>The pieces of process startup and shutdown that carry their own correctness
 * requirements, factored out of the application entry point so they can be tested:</p>
 * <ul>
 *   <li>{@link excalibur.api.bootstrap.StorageChunkWriter} - hash-routed chunk writes
 *       and the routed tombstone adapter</li>
 *   <li>{@link excalibur.api.bootstrap.ReindexerFactory} - wiring for the document
 *       reindexer</li>
 *   <li>{@link excalibur.api.bootstrap.ShutdownSequence} - the ordered shutdown that
 *       keeps the shared arena open until every reader has drained</li>
 * </ul>
 *
 * @since 9.0
 */
package excalibur.api.bootstrap;
