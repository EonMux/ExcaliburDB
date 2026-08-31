/**
 * Security policy for the ExcaliburDB HTTP API.
 *
 * <p>This package holds every decision about who may call the API, how often, and
 * which ingest targets the server will touch on a caller's behalf:</p>
 * <ul>
 *   <li>{@link excalibur.api.security.ApiSecurityConfig} - the immutable policy,
 *       resolved once at startup from the properties file and the environment</li>
 *   <li>{@link excalibur.api.security.RequestGuard} - the single decorator every
 *       endpoint is registered through</li>
 *   <li>{@link excalibur.api.security.ClientRateLimiter} - bounded per-client token
 *       buckets with separate read and mutate budgets</li>
 *   <li>{@link excalibur.api.security.IngestTargetValidator} - allow-list checks for
 *       filesystem and URL ingest targets</li>
 * </ul>
 *
 * @since 9.0
 */
package excalibur.api.security;
