package excalibur.ingest.source;

import java.util.Map;
import java.util.Objects;

/**
 * What a source was registered as, in a form that outlives the process that accepted it.
 *
 * <p>A {@link Source} is a live crawler - a resolved root, an HTTP client, a selector thread -
 * and none of that can be written down. What can is the request that produced it: a type, an id,
 * and the configuration the caller supplied. A node that keeps these can rebuild every source it
 * was asked to crawl after a restart. One that does not stops re-crawling the moment it is
 * bounced, and stops silently: the corpus is still there and still queryable, so nothing looks
 * wrong until someone notices that a directory changed weeks ago and the index never followed.</p>
 *
 * <p>The configuration is held as the caller gave it, not as it resolved. A root path is
 * canonicalised and matched against an allow-list when the source is built, and that allow-list
 * is free to have narrowed since; re-checking the original request on the way back in is the
 * point, and recording the already-approved result would defeat it.</p>
 *
 * @param sourceType Which crawler to build, {@code filesystem} or {@code http}
 * @param sourceId   The id the source is addressed by, in the registry and in the re-crawl schedule
 * @param config     Type-specific configuration, exactly as the registration request supplied it
 */
public record SourceDescriptor(String sourceType, String sourceId, Map<String, String> config) {

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
        if (sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType must not be blank");
        }
        if (sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        config = Map.copyOf(config);
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
