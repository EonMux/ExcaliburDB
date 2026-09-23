package com.eonmux.excalibur.retrieval.expand;

import com.eonmux.excalibur.filter.CuckooFilter;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Answers term membership from a Cuckoo filter of the corpus vocabulary.
 *
 * <h2>What it buys over asking Lucene</h2>
 * <p>Nothing, for a single index held open: Lucene's term dictionary answers the same question
 * exactly and is already in memory. The filter earns its place when the corpus is split across many
 * shards or namespaces, because it answers without opening any of them - one constant-size structure
 * instead of a dictionary seek per shard, and no reader, no file descriptor and no page cache per
 * index consulted.
 *
 * <h2>Direction of error</h2>
 * <p>A Cuckoo filter over-reports and never under-reports, which is the correct direction here. A
 * false positive costs one query clause that finds nothing. A false negative would discard a good
 * expansion for a word the corpus really does contain, and nothing downstream could tell.
 *
 * <p>Unlike a Bloom filter it supports deletion, so a corpus that has documents erased out of it
 * can have their vocabulary removed rather than accumulating terms it no longer holds.
 */
public final class CuckooVocabulary implements VocabularyGate {

    private final CuckooFilter filter;

    public CuckooVocabulary(CuckooFilter filter) {
        this.filter = Objects.requireNonNull(filter, "filter must not be null");
    }

    /** Records a term as present in the corpus. */
    public boolean add(String term) {
        return filter.insert(term.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean mayContain(String term) {
        return filter.contains(term.getBytes(StandardCharsets.UTF_8));
    }
}
