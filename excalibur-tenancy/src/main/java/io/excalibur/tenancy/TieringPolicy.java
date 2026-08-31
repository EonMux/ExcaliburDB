package io.excalibur.tenancy;

import java.util.Set;

/**
 * Assigns each namespace a tier, deciding what gets evicted first.
 *
 * <p>A seam rather than a field on the namespace, because the right tier is a property of how a
 * deployment is used rather than of the namespace itself, and it changes — a tenant that goes idle
 * should cool without anything being rewritten.
 */
@FunctionalInterface
public interface TieringPolicy {

    /** The tier {@code id} currently belongs to. */
    NamespaceTier tierOf(NamespaceId id);

    /**
     * Everything warm except the shared namespace, which is pinned.
     *
     * <p>The right default: the shared index is read by nearly every query, so evicting it is
     * almost always a mistake, and nothing else has a reason to be treated specially until it is
     * measured.
     */
    static TieringPolicy sharedIsHot() {
        return id -> id.isShared() ? NamespaceTier.HOT : NamespaceTier.WARM;
    }

    /** Pins a named set of namespaces alongside the shared one. */
    static TieringPolicy pinning(Set<NamespaceId> pinned) {
        Set<NamespaceId> copy = Set.copyOf(pinned);
        return id -> id.isShared() || copy.contains(id) ? NamespaceTier.HOT : NamespaceTier.WARM;
    }

    /** Everything warm, including the shared namespace. */
    static TieringPolicy uniform() {
        return id -> NamespaceTier.WARM;
    }
}
