package com.eonmux.excalibur.core.text;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * The sources a search may answer from.
 *
 * <p>A value rather than a predicate, so it can be compared and hashed: a cached answer is keyed on
 * it, and two requests naming the same sources in a different order ask the same question. What
 * turns it into a decision about one chunk is {@link ChunkSources}, which the node supplies - the
 * scope says which sources, the node says which source a chunk is from.</p>
 *
 * <p>{@link #ALL} restricts nothing and is what a search that names no source runs with.</p>
 *
 * <p>Immutable and safe for concurrent use.</p>
 */
public final class SourceScope {

    /**
     * Most sources one scope may name.
     *
     * <p>Every named source is a prefix each candidate chunk is compared against, so the list is
     * bounded as any per-candidate cost a caller chooses is. Not a bound on what a node holds:
     * a scope wider than this is better written as no scope.</p>
     */
    public static final int MAX_SOURCES = 1_000;

    /** Every source: the scope of a search that names none. */
    public static final SourceScope ALL = new SourceScope(Collections.emptySortedSet());

    /** The named sources, sorted so equal scopes render and hash alike; empty for {@link #ALL}. */
    private final Set<String> sourceIds;

    /** Each named id followed by the separator every chunk id of that source begins with. */
    private final String[] keyPrefixes;

    private SourceScope(Set<String> sourceIds) {
        this.sourceIds = sourceIds;
        // The key of a document with an empty id: its source's id and the separator, which is
        // exactly what every chunk id of that source begins with.
        this.keyPrefixes = sourceIds.stream()
            .map(id -> ChunkIdentifiers.documentKey(id, ""))
            .toArray(String[]::new);
    }

    /**
     * A scope over the named sources.
     *
     * @param sourceIds The sources to answer from; duplicates are collapsed
     * @return The scope
     * @throws NullPointerException     if sourceIds or any id in it is null
     * @throws IllegalArgumentException if sourceIds is empty or an id is blank - a scope over no
     *                                  source would answer nothing, which no caller means - or
     *                                  it names more than {@link #MAX_SOURCES}
     */
    public static SourceScope of(Collection<String> sourceIds) {
        Objects.requireNonNull(sourceIds, "sourceIds must not be null");
        if (sourceIds.isEmpty()) {
            throw new IllegalArgumentException("a source scope must name at least one source");
        }
        if (sourceIds.size() > MAX_SOURCES) {
            throw new IllegalArgumentException("a source scope may name at most " + MAX_SOURCES
                + " sources: " + sourceIds.size());
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String sourceId : sourceIds) {
            Objects.requireNonNull(sourceId, "a source id must not be null");
            if (sourceId.isBlank()) {
                throw new IllegalArgumentException("a source id must not be blank");
            }
            sorted.add(sourceId);
        }
        return new SourceScope(Collections.unmodifiableSortedSet(sorted));
    }

    /**
     * Whether this scope restricts nothing.
     *
     * @return true for {@link #ALL}
     */
    public boolean isUnrestricted() {
        return sourceIds.isEmpty();
    }

    /**
     * The sources this scope names, in order.
     *
     * @return The ids, empty for {@link #ALL}
     */
    public Set<String> sourceIds() {
        return sourceIds;
    }

    /**
     * Whether a chunk is from one of this scope's sources.
     *
     * <p>A chunk id that begins with none of the named ids followed by a colon cannot be from any
     * of them, and is refused without asking {@code sources}; that is every chunk of every other
     * source in the ordinary case, and it costs a string comparison per named source. One that
     * does is asked about, because the name alone does not settle it - see
     * {@link ChunkSources}.</p>
     *
     * @param chunkId The chunk's id, or null for a result that carries none
     * @param sources Names the source a chunk is from
     * @return true when the scope is {@link #ALL}, or the chunk is from a named source
     */
    public boolean admits(String chunkId, ChunkSources sources) {
        if (isUnrestricted()) {
            return true;
        }
        if (chunkId == null || !beginsWithNamedSource(chunkId)) {
            return false;
        }
        String owner = sources.sourceOf(chunkId);
        return owner != null && sourceIds.contains(owner);
    }

    /**
     * This scope as a filter on chunk ids.
     *
     * @param sources Names the source a chunk is from
     * @return The filter, or null for {@link #ALL}, which filters nothing - the convention the
     *         engines' own document filters follow
     * @throws NullPointerException if the scope restricts and sources is null
     */
    public Predicate<String> admitting(ChunkSources sources) {
        if (isUnrestricted()) {
            return null;
        }
        Objects.requireNonNull(sources, "a restricted scope needs to know each chunk's source");
        return chunkId -> admits(chunkId, sources);
    }

    private boolean beginsWithNamedSource(String chunkId) {
        for (String prefix : keyPrefixes) {
            if (chunkId.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
            || other instanceof SourceScope scope && sourceIds.equals(scope.sourceIds);
    }

    @Override
    public int hashCode() {
        return sourceIds.hashCode();
    }

    @Override
    public String toString() {
        return isUnrestricted() ? "SourceScope[all]" : "SourceScope" + sourceIds;
    }
}
