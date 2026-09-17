package excalibur.ingest.source;

import io.excalibur.tenancy.NamespaceId;

import java.util.Map;
import java.util.Objects;

/**
 * What a source was registered as, in a form that outlives the process that accepted it.
 *
 * <p>A {@link Source} is a live crawler - a resolved root, an HTTP client, a selector thread -
 * and none of that can be written down. What can is the request that produced it: a type, an id,
 * the namespace it belongs to, and the configuration the caller supplied. A node that keeps these
 * can rebuild every source it was asked to crawl after a restart. One that does not stops
 * re-crawling the moment it is bounced, and stops silently: the corpus is still there and still
 * queryable, so nothing looks wrong until someone notices that a directory changed weeks ago and
 * the index never followed.</p>
 *
 * <p>The configuration is held as the caller gave it, not as it resolved. A root path is
 * canonicalised and matched against an allow-list when the source is built, and that allow-list
 * is free to have narrowed since; re-checking the original request on the way back in is the
 * point, and recording the already-approved result would defeat it.</p>
 *
 * <h2>Why the namespace is here and not alongside</h2>
 * <p>Every fact extracted from a document this source publishes is staged into its namespace's
 * graph, and a namespace is not something the crawl can be asked for later: the caller who named
 * it is long gone by the time a re-crawl runs. Held only in the process that accepted the
 * registration, the binding would survive exactly until the next restart, after which the same
 * source would start staging into {@link NamespaceId#SHARED} - one tenant's documents landing in
 * an index every other tenant reads. So it is written down with everything else a registration is
 * rebuilt from.</p>
 *
 * @param sourceType Which crawler to build, {@code filesystem} or {@code http}
 * @param sourceId   The id the source is addressed by, in the registry and in the re-crawl schedule
 * @param config     Type-specific configuration, exactly as the registration request supplied it
 * @param namespace  The namespace this source's documents belong to; {@link NamespaceId#SHARED}
 *                   for a registration that named none
 */
public record SourceDescriptor(String sourceType, String sourceId, Map<String, String> config,
                               NamespaceId namespace) {

    /**
     * Validates the descriptor and takes an immutable copy of the configuration.
     *
     * @throws NullPointerException     if any argument, key or value is null
     * @throws IllegalArgumentException if the type or id is blank
     */
    public SourceDescriptor {
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(namespace, "namespace must not be null");
        if (sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType must not be blank");
        }
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        config = Map.copyOf(config);
    }

    /**
     * Describes a registration that named no namespace.
     *
     * <p>The shared namespace is what a node that has never heard of multi-tenancy uses, and it
     * is what an absent {@code X-Excalibur-Namespace} header means - so a caller that has no
     * namespace to name does not have to pass one to say so.</p>
     *
     * @param sourceType Which crawler to build
     * @param sourceId   The id the source is addressed by
     * @param config     Type-specific configuration, exactly as the request supplied it
     */
    public SourceDescriptor(String sourceType, String sourceId, Map<String, String> config) {
        this(sourceType, sourceId, config, NamespaceId.SHARED);
    }

    /**
     * Returns a configuration value.
     *
     * @param key The configuration key
     * @return The value, or null if the registration did not supply one
     */
    public String get(String key) {
        return config.get(key);
    }
}
