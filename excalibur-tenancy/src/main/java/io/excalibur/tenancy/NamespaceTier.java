package io.excalibur.tenancy;

/**
 * How willing the registry is to close a namespace to make room.
 *
 * <p>Least-recent use alone is the wrong policy when namespaces are not interchangeable. The shared
 * knowledge base is read by nearly every query and costs seconds to reopen, so evicting it during a
 * quiet moment for one tenant's index is a bad trade however long it has been idle. A tenant that
 * queries once a week is the opposite. Recency cannot see that difference; a declared tier can.
 */
public enum NamespaceTier {

    /**
     * Never evicted.
     *
     * <p>For namespaces whose reopen cost is paid by everyone — the shared index above all. Pinning
     * spends a fixed share of the mapping budget permanently, so it is a decision to make
     * deliberately and for very few namespaces.
     */
    HOT,

    /** Evicted by least-recent use once the colder tier is exhausted. The default. */
    WARM,

    /**
     * Evicted first, whatever its recency.
     *
     * <p>For namespaces known to be cheap to reopen or unlikely to be needed again soon — one
     * finishing a bulk import, or one whose tenant has gone idle.
     */
    COLD
}
