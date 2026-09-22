package com.eonmux.excalibur.retrieval.expand;

import com.eonmux.excalibur.retrieval.lexical.LexicalIndex;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.util.Objects;

/**
 * Answers term membership from Lucene's own term dictionary.
 *
 * <p>The index already knows its vocabulary exactly, so this is the truthful implementation: no
 * false positives, nothing extra to build, nothing to keep in step with the corpus. Its cost is a
 * dictionary seek per segment of the index, which is cheap but not free, and it requires an open
 * reader - which is what makes the probabilistic alternative interesting once an index is split
 * across many shards that would otherwise all have to be opened.
 */
public final class LuceneVocabulary implements VocabularyGate {

    private final LexicalIndex index;

    public LuceneVocabulary(LexicalIndex index) {
        this.index = Objects.requireNonNull(index, "index must not be null");
    }

    @Override
    public boolean mayContain(String term) {
        java.util.Optional<LexicalIndex.Lease> leased = index.tryAcquire();
        if (leased.isEmpty()) {
            // Nothing searchable yet: admit everything rather than silently emptying queries.
            return true;
        }
        BytesRef needle = new BytesRef(term);
        try (LexicalIndex.Lease lease = leased.get()) {
            IndexSearcher searcher = lease.searcher();
            boolean sawVocabulary = false;
            for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
                Terms terms = leaf.reader().terms(LexicalIndex.FIELD_TEXT);
                if (terms == null) {
                    continue;
                }
                sawVocabulary = true;
                TermsEnum cursor = terms.iterator();
                if (cursor.seekExact(needle)) {
                    return true;
                }
            }
            // An index with no vocabulary cannot judge vocabulary. Answering "absent" here would
            // gate away every expansion on a node whose index is open but not yet populated -
            // which is every node during its first ingestion run.
            return !sawVocabulary;
        } catch (IOException e) {
            // A gate that cannot read must not be the reason a query returns nothing.
            return true;
        }
    }
}
