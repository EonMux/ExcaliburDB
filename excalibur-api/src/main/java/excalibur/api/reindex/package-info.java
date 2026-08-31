/**
 * Reindex coordination for the ExcaliburDB API.
 *
 * <p>Holds {@link excalibur.api.reindex.ReindexCoordinator}, the single
 * mutual-exclusion primitive shared by the {@code POST /v1/reindex} endpoint and the
 * scheduled reindex handler.</p>
 *
 * @since 9.0
 */
package excalibur.api.reindex;
