package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.bootstrap.NamespaceRefusedException;
import com.eonmux.excalibur.tenancy.NamespaceId;

import java.io.IOException;

/**
 * Makes sure a namespace a request named exists before the request is accepted into it.
 *
 * <p>For the routes that accept work into a namespace without borrowing it there and then: a
 * source registration stages its documents later, from the crawl, and a build runs behind its
 * {@code 202}. Refusing the namespace only when the work reaches it would accept a request whose
 * documents are then dropped, or a build that fails where nobody is looking, so these routes ask
 * first. On a node serving graphs this is
 * {@link com.eonmux.excalibur.api.bootstrap.GraphTenants#admit(NamespaceId)}.</p>
 */
@FunctionalInterface
public interface NamespaceAdmission {

    /** Admits every namespace, for a node that holds no graphs of its own to bound. */
    NamespaceAdmission ANY = namespace -> { };

    /**
     * Admits a namespace, creating it if the node creates namespaces on demand.
     *
     * @param namespace The namespace the request named
     * @throws NamespaceRefusedException if it does not exist and may not be created
     * @throws IOException               if it could not be created
     */
    void admit(NamespaceId namespace) throws IOException;
}
