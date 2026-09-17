/**
 * The query result cache, and the corpus fingerprint that decides when it may answer.
 *
 * <p>Everything here exists to answer one question: is a result computed a moment ago still
 * the result this query would produce now? {@link excalibur.query.cache.CorpusVersion} is the
 * evidence, {@link excalibur.query.cache.QueryCacheKey} is the question the result answered,
 * and {@link excalibur.query.cache.QueryResultCache} refuses to serve unless both match.</p>
 */
package excalibur.query.cache;
