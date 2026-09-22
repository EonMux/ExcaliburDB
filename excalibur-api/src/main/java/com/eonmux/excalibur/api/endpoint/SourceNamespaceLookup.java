package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.tenancy.NamespaceId;

import java.util.Optional;

/**
 * Which graph namespace the documents written under a source id belong to.
 *
 * <p>Two routes write documents under a source id and each leaves a different trace of it. A
 * crawled source is registered, and its registration carries the namespace the request named.
 * Supplied documents leave no registration at all - only their id in {@link InlineSourceIds} -
 * and since {@code POST /v1/documents} refuses the namespace header, what they contribute to the
 * graph belongs to the shared namespace. Both the graph sink that stages facts and the erasure
 * that withdraws them ask this one question, so the two cannot disagree about where a
 * document's facts went.</p>
 *
 * <p>Empty means this node cannot say: nothing registered or supplied under the id in this
 * process. It is never read as the shared namespace, because a guess that lands on the index
 * every tenant reads is the one mistake a tenancy boundary exists to rule out.</p>
 */
public final class SourceNamespaceLookup {

    private SourceNamespaceLookup() {
    }

    /**
     * Resolves a source id to its namespace.
     *
     * @param registry The registered crawl sources, or null on a node that keeps none
     * @param supplied The ids documents have been supplied under, or null on a node that takes
     *                 no supplied documents
     * @param sourceId The source id
     * @return Its namespace, or empty if this node holds nothing under that id
     */
    public static Optional<NamespaceId> namespaceOf(SourceRegistry registry,
                                                    InlineSourceIds supplied, String sourceId) {
        if (sourceId == null) {
            return Optional.empty();
        }
        Optional<NamespaceId> registered =
            registry == null ? Optional.empty() : registry.namespaceOf(sourceId);
        if (registered.isPresent()) {
            return registered;
        }
        return supplied != null && supplied.isSupplied(sourceId)
            ? Optional.of(NamespaceId.SHARED)
            : Optional.empty();
    }
}
