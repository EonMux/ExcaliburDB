/**
 * The audit trail of mutating and administrative requests.
 *
 * <p>{@link com.eonmux.excalibur.api.audit.AuditLog} writes one structured event per audited
 * request to the {@code excalibur.audit} logger: the action, its outcome, the client, the
 * request id and the identifiers it was applied to - never the content an erasure removes.</p>
 */
package com.eonmux.excalibur.api.audit;
