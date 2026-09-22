package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.api.endpoint.InMemorySourceRegistry;
import com.eonmux.excalibur.api.endpoint.SourceFactory;
import com.eonmux.excalibur.api.security.IngestTargetRejectedException;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceDescriptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Puts the sources a previous run was crawling back into the registry.
 *
 * <h2>Why it replays the request</h2>
 * <p>The catalog holds registration requests, not the crawlers they produced, so every record
 * goes back through the same allow-lists a fresh {@code POST /v1/ingest} would meet. An operator
 * who narrows {@code excalibur.ingest.allowed-roots} or {@code allowed-hosts} has narrowed it, and
 * a target that was permitted last week must not walk back in through a file the node wrote
 * itself.</p>
 *
 * <h2>What a refusal means</h2>
 * <p>A record that is refused or cannot be built is skipped and left on file. The allow-list may
 * be the thing that is wrong - a mistyped root, a config not yet deployed - and a node that
 * deleted the registration in response would leave the operator nothing to correct. Each one is
 * logged by id and reason, and {@code DELETE /v1/ingest} still cancels it.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless. Called once during startup, before the listener accepts requests.</p>
 */
public final class SourceRestoration {

    private static final Logger LOGGER = LoggerFactory.getLogger(SourceRestoration.class);

    private SourceRestoration() {
        throw new AssertionError("Utility class");
    }

    /**
     * Rebuilds and re-registers every source the catalog holds.
     *
     * <p>No crawl is started. Each restored source takes its place in the re-crawl schedule and is
     * next crawled one interval from now, which is what it was waiting for when the process
     * ended.</p>
     *
     * @param catalog  The recorded registrations
     * @param factory  Builds a crawler from a registration, re-checking its target
     * @param registry The registry to restore into
     * @return How many sources were restored
     * @throws NullPointerException if any argument is null
     */
    public static int restore(SourceCatalog catalog, SourceFactory factory,
                              InMemorySourceRegistry registry) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        Objects.requireNonNull(factory, "factory must not be null");
        Objects.requireNonNull(registry, "registry must not be null");

        int restored = 0;
        int refused = 0;
        for (SourceDescriptor descriptor : catalog.all()) {
            if (restoreOne(descriptor, factory, registry)) {
                restored++;
            } else {
                refused++;
            }
        }

        if (restored > 0 || refused > 0) {
            LOGGER.info("Restored {} registered source(s) from the catalog; {} could not be "
                + "restored", restored, refused);
        }
        return restored;
    }

    /**
     * Rebuilds one registration.
     *
     * @return true if the source is registered again
     */
    private static boolean restoreOne(SourceDescriptor descriptor, SourceFactory factory,
                                      InMemorySourceRegistry registry) {
        Source source;
        try {
            source = factory.create(descriptor);
        } catch (IngestTargetRejectedException e) {
            LOGGER.warn("Source {} is recorded but its target is no longer allowed ({}); it will "
                + "not be crawled. Allow the target again, or DELETE /v1/ingest to cancel it.",
                descriptor.sourceId(), e.getMessage());
            return false;
        } catch (RuntimeException e) {
            LOGGER.warn("Source {} of type {} is recorded but could not be rebuilt: {}",
                descriptor.sourceId(), descriptor.sourceType(), e.toString());
            return false;
        }

        try {
            // With the descriptor, not just the crawler: the namespace it records is what
            // decides which tenant's graph this source's documents are staged into, and a
            // restore that dropped it would move them into the shared namespace at the first
            // restart after they were registered.
            registry.restore(source, descriptor);
            return true;
        } catch (RuntimeException e) {
            closeQuietly(source, descriptor.sourceId());
            LOGGER.warn("Source {} could not be restored into the registry: {}",
                descriptor.sourceId(), e.toString());
            return false;
        }
    }

    /**
     * Closes a crawler that never reached the registry.
     *
     * <p>An HTTP source owns a client and a selector thread; dropping the reference without
     * closing leaks both for the life of the process.</p>
     */
    private static void closeQuietly(Source source, String sourceId) {
        try {
            source.close();
        } catch (Exception e) {
            LOGGER.debug("Error closing unrestored source {}: {}", sourceId, e.getMessage());
        }
    }
}
