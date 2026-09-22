package com.eonmux.excalibur.retrieval.lexical;

import java.nio.file.Path;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LexicalIndexLeaseTest {

    @TempDir
    Path root;

    private static TermQuery containing(String word) {
        return new TermQuery(new Term(LexicalIndex.FIELD_TEXT, word));
    }

    @Test
    @DisplayName("a search holding a lease keeps reading after a refresh replaces its reader")
    void aRefreshDoesNotCloseALeasedReader() throws Exception {
        try (LexicalIndex index = LexicalIndex.open(root, 16.0)) {
            index.put(1, "alpha beta");
            index.refresh();

            try (LexicalIndex.Lease lease = index.acquire()) {
                index.put(2, "alpha gamma");
                index.refresh();

                IndexSearcher searcher = lease.searcher();
                assertThat(searcher.count(containing("alpha")))
                    .as("the leased reader still answers, as of its own version")
                    .isEqualTo(1);
            }
            try (LexicalIndex.Lease lease = index.acquire()) {
                assertThat(lease.searcher().count(containing("alpha"))).isEqualTo(2);
            }
        }
    }

    @Test
    @DisplayName("a replaced reader closes once its last lease is released")
    void aReplacedReaderClosesWithItsLastLease() throws Exception {
        try (LexicalIndex index = LexicalIndex.open(root, 16.0)) {
            index.put(1, "alpha");
            index.refresh();
            LexicalIndex.Lease lease = index.acquire();
            index.put(2, "beta");
            index.refresh();

            assertThat(lease.searcher().getIndexReader().getRefCount()).isEqualTo(1);
            lease.close();
            lease.close();

            assertThat(lease.searcher().getIndexReader().getRefCount()).isZero();
        }
    }

    @Test
    @DisplayName("releasing a lease twice gives up one reference, not the index's own")
    void aDoubleReleaseLeavesTheServedReaderOpen() throws Exception {
        try (LexicalIndex index = LexicalIndex.open(root, 16.0)) {
            index.put(1, "alpha");
            index.refresh();

            LexicalIndex.Lease lease = index.acquire();
            lease.close();
            lease.close();

            assertThat(index.textOf(1)).isEqualTo("alpha");
        }
    }

    @Test
    @DisplayName("a closed index has nothing to lease")
    void nothingToLeaseOnceClosed() throws Exception {
        LexicalIndex index = LexicalIndex.open(root, 16.0);
        index.close();

        assertThat(index.tryAcquire()).isEmpty();
        assertThatThrownBy(index::acquire).isInstanceOf(IllegalStateException.class);
        assertThat(index.readerVersion()).isEmpty();
    }

    @Test
    @DisplayName("the served version is readable while a lease holds the reader it replaced")
    void versionIsReadAcrossARefresh() throws Exception {
        try (LexicalIndex index = LexicalIndex.open(root, 16.0)) {
            index.put(1, "alpha");
            index.refresh();
            long first = index.readerVersion().orElseThrow();

            index.put(2, "beta");
            index.refresh();

            assertThat(index.readerVersion().orElseThrow()).isGreaterThan(first);
        }
    }
}
