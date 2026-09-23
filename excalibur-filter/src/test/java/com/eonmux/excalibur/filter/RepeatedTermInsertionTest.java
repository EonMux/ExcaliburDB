package com.eonmux.excalibur.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A term that occurs many times inside one text.
 *
 * <p>Any real text repeats its terms. A Cuckoo filter is a multiset - each insertion takes a
 * slot - so recording an occurrence rather than a term costs capacity in proportion to term
 * frequency, and an item's fingerprint only ever lives in its two candidate buckets, so the same
 * term stops being insertable altogether once those buckets are full.
 * {@link CuckooFilter#insertIfAbsent(byte[])} is what a caller recording terms uses for that
 * reason, and one delete then removes the term.
 */
@DisplayName("Repeated terms")
class RepeatedTermInsertionTest {

    private static final byte[] TERM = "invoice".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("a term repeated far beyond a bucket's capacity keeps inserting")
    void repeatedInsertionNeverFails() {
        CuckooFilter filter = CuckooFilter.create(1_000);

        // Two buckets of four slots each is the entire space one item can occupy, so an
        // occurrence-per-slot scheme runs out long before this.
        for (int occurrence = 0; occurrence < 500; occurrence++) {
            assertThat(filter.insertIfAbsent(TERM))
                .as("occurrence %s of the same term", occurrence)
                .isTrue();
        }

        assertThat(filter.contains(TERM)).isTrue();
    }

    @Test
    @DisplayName("a repeated term costs one entry, and one delete removes it")
    void plainFilterInsertIfAbsentIsIdempotent() {
        CuckooFilter filter = CuckooFilter.create(10_000);

        for (int occurrence = 0; occurrence < 200; occurrence++) {
            assertThat(filter.insertIfAbsent(TERM))
                .as("occurrence %s", occurrence)
                .isTrue();
        }

        assertThat(filter.count()).isEqualTo(1);
        assertThat(filter.contains(TERM)).isTrue();
        assertThat(filter.delete(TERM)).isTrue();
        assertThat(filter.contains(TERM)).isFalse();
    }
}
