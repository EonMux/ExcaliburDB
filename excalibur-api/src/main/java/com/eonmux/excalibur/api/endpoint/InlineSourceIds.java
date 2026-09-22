package com.eonmux.excalibur.api.endpoint;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The source identifiers that documents have been supplied directly under.
 *
 * <p>A source id names one body of documents, and two routes can write under one: {@code POST
 * /v1/documents} supplies documents itself, and {@code POST /v1/ingest} registers a crawl that
 * decides for itself what documents the id holds. The second removes what the first wrote - a
 * crawl's first pass tombstones every document under its source that the crawl did not find -
 * so the two must not share an id. This is the half of that guard the source registry cannot
 * answer: a supplied document leaves no registered source behind to be found.</p>
 *
 * <h2>Capacity</h2>
 * <p>Bounded by the same limit as the crawl registry, and for the same reason: an id recorded
 * here is an id no crawl can be registered under, so an unbounded set lets one caller deny
 * every likely id to every other. A node refuses a new supplied source past the bound rather
 * than growing for the life of the process.</p>
 *
 * <h2>What this does not survive</h2>
 * <p>A restart. The ids live in this process only, so a node that has restarted no longer knows
 * which of its stored documents were supplied rather than crawled, and will accept a crawl
 * registered over them. Registering a crawl over an id that was ever used to supply documents
 * loses them, whether or not this node still remembers.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Recording and forgetting are serialized so that the bound is
 * exact: claiming optimistically and giving the claim back when it turns out to be one too many
 * lets a second caller read the transient claim as a recorded id and proceed without one.</p>
 *
 * @author ExcaliburDB
 */
public final class InlineSourceIds {

    private final Set<String> ids = ConcurrentHashMap.newKeySet();
    private final int maxSources;

    /**
     * Creates the set.
     *
     * @param maxSources Most ids this node will hold supplied documents under
     * @throws IllegalArgumentException if maxSources is not positive
     */
    public InlineSourceIds(int maxSources) {
        if (maxSources <= 0) {
            throw new IllegalArgumentException("maxSources must be positive: " + maxSources);
        }
        this.maxSources = maxSources;
    }

    /**
     * Records that documents are being supplied under an id.
     *
     * <p>Called before the documents are written rather than after, so that a crawl registration
     * arriving while the write is still running is refused too.</p>
     *
     * <p>An id already recorded is always accepted: a second batch under a source this node
     * already holds is not a new source, and refusing it at the bound would make a working
     * corpus stop taking updates.</p>
     *
     * @param sourceId The source the documents were supplied under
     * @return true when the id is recorded, false when the node already holds as many supplied
     *         sources as it is configured for
     * @throws NullPointerException if sourceId is null
     */
    public synchronized boolean remember(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        if (ids.contains(sourceId)) {
            return true;
        }
        if (ids.size() >= maxSources) {
            return false;
        }
        ids.add(sourceId);
        return true;
    }

    /**
     * Forgets an id, for a caller that has removed everything supplied under it.
     *
     * <p>Without this the refusal outlives what it protects: a source erased down to nothing
     * would still refuse a crawl registered under its id, on the grounds that it holds supplied
     * documents it no longer holds. The crawl half of the guard releases its claim when the
     * source is deregistered, and this is the same release for the supplied half.</p>
     *
     * @param sourceId The source to forget
     * @return true when the id was recorded
     * @throws NullPointerException if sourceId is null
     */
    public synchronized boolean forget(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        return ids.remove(sourceId);
    }

    /**
     * Reports whether documents have been supplied under an id.
     *
     * @param sourceId The source to check
     * @return true when this node has taken supplied documents under that id
     * @throws NullPointerException if sourceId is null
     */
    public boolean isSupplied(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        return ids.contains(sourceId);
    }

    @Override
    public String toString() {
        return "InlineSourceIds{size=" + ids.size() + ", max=" + maxSources + "}";
    }
}
