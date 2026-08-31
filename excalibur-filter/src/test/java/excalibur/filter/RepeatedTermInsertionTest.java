package excalibur.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A term that occurs many times inside one segment.
 *
 * <p>Ingestion extracts terms from chunk text, and any real text repeats its terms. A Cuckoo
 * filter is a multiset - each insertion takes a slot - so recording an occurrence rather than a
 * term costs capacity in proportion to term frequency, and an item's fingerprint only ever lives
 * in its two candidate buckets, so the same term stops being insertable altogether once those
 * buckets are full.
 *
 * <p>It also breaks removal, which the maintenance worker performs once per distinct term of a
 * retired segment: one delete cannot undo a number of insertions nobody counted.
 */
@DisplayName("Repeated terms")
class RepeatedTermInsertionTest {

    private static final byte[] TERM = "invoice".getBytes(StandardCharsets.UTF_8);
    private static final String SEGMENT = "7";

    private static HierarchicalCuckooFilter hierarchy() {
        HierarchicalCuckooFilter filter =
            new HierarchicalCuckooFilter(100_000L, 13, 10_000, 1_000);
        filter.registerSegment(SEGMENT, "cluster-0");
        return filter;
    }

    @Test
    @DisplayName("a term repeated far beyond a bucket's capacity keeps inserting")
    void repeatedInsertionNeverFails() {
        HierarchicalCuckooFilter filter = hierarchy();

        // Two buckets of a handful of slots each is the entire space one item can occupy, so
        // an occurrence-per-slot scheme runs out here. Ingest reports a chunk as failed when
        // every one of its terms fails, which fails the whole batch publication.
        for (int occurrence = 0; occurrence < 500; occurrence++) {
            assertThat(filter.insert(TERM, SEGMENT))
                .as("occurrence %s of the same term", occurrence)
                .isTrue();
        }

        assertThat(filter.mightContainInSegment(TERM, SEGMENT)).isTrue();
    }

    @Test
    @DisplayName("a repeated term costs one entry, not one per occurrence")
    void repeatedInsertionCostsOneEntry() {
        HierarchicalCuckooFilter filter = hierarchy();

        for (int occurrence = 0; occurrence < 50; occurrence++) {
            filter.insert(TERM, SEGMENT);
        }

        assertThat(filter.getTotalEntries())
            .as("the hierarchy holds the term once")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("one delete removes a repeated term, matching how cleanup removes it")
    void oneDeleteRemovesARepeatedTerm() {
        HierarchicalCuckooFilter filter = hierarchy();

        for (int occurrence = 0; occurrence < 50; occurrence++) {
            filter.insert(TERM, SEGMENT);
        }

        assertThat(filter.deleteFromHierarchy(TERM, SEGMENT)).isTrue();

        // The maintenance worker collects a retired segment's distinct terms and deletes each
        // once. Anything left behind keeps the segment a candidate for a term it no longer has
        // and keeps the shared levels claiming it for the whole corpus.
        assertThat(filter.mightContainInSegment(TERM, SEGMENT)).isFalse();
        assertThat(filter.mightContain(TERM)).isFalse();
    }

    @Test
    @DisplayName("a plain filter records a repeated item once")
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
