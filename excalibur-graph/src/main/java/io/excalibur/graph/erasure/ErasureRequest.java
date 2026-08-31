package io.excalibur.graph.erasure;

import java.time.Duration;
import java.time.Instant;

/**
 * A request to remove data, and when it was made.
 *
 * <p>The timestamp is the point. An erasure obligation has a deadline that does not care how much
 * data is involved: one edge erased under a legal request is as urgent as a million, and a policy
 * that only watches the tombstone ratio would leave that single edge on disk indefinitely because
 * it never moves the ratio. Recording when the request arrived is what lets compaction be driven by
 * the obligation rather than by the volume.
 *
 * @param id          identifies the request, for auditing what was erased and when
 * @param scope       what to erase
 * @param requestedAt when the request arrived
 */
public record ErasureRequest(long id, ErasureScope scope, Instant requestedAt) {

    public ErasureRequest {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (requestedAt == null) {
            throw new IllegalArgumentException("requestedAt must not be null");
        }
    }

    /** How long this request has been outstanding. */
    public Duration ageAt(Instant now) {
        return Duration.between(requestedAt, now);
    }
}
