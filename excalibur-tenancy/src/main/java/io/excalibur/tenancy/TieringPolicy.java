package io.excalibur.tenancy;

import java.util.Set;
import java.util.TreeSet;

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
        return declared(Set.of(), Set.of());
    }

    /**
     * Pins one named set of namespaces and marks another as the first to go.
     *
     * <p>The shared namespace is hot whether or not it is named, for the reason
     * {@link #sharedIsHot()} gives. Everything unnamed is warm, so passing two empty sets is the
     * default policy.
     *
     * @param hot  Namespaces never evicted, alongside the shared one
     * @param cold Namespaces evicted before any warm one, whatever their recency
     * @return The policy
     * @throws NullPointerException     if either set is null
     * @throws IllegalArgumentException if a namespace appears in both
     */
    static TieringPolicy declared(Set<NamespaceId> hot, Set<NamespaceId> cold) {
        Set<NamespaceId> pinned = Set.copyOf(hot);
        Set<NamespaceId> first = Set.copyOf(cold);
        // Resolving this by precedence would make the effective tier depend on which set the
        // implementation happens to consult first, which is not something an operator can read
        // off their own configuration.
        Set<NamespaceId> both = new TreeSet<>(pinned);
        both.retainAll(first);
        if (!both.isEmpty()) {
            throw new IllegalArgumentException(
                "a namespace cannot be both hot and cold: " + both);
        }
        return id -> {
            if (id.isShared() || pinned.contains(id)) {
                return NamespaceTier.HOT;
            }
            return first.contains(id) ? NamespaceTier.COLD : NamespaceTier.WARM;
        };
    }

    /** Everything warm, including the shared namespace. */
    static TieringPolicy uniform() {
        return id -> NamespaceTier.WARM;
    }
}
