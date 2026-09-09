package io.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which of the states a query may read hold an incomplete answer.
 *
 * <p>A client that asks to be spared partially-indexed segments is asking to be spared partial
 * answers, not every segment that happens not to be {@link LifecycleState#ACTIVE}. Reading the
 * enum as if those were the same set drops whole segments out of a strict query, and a dropped
 * segment is not an error anywhere - it is simply absent from the results.</p>
 */
@DisplayName("Lifecycle state query visibility")
class LifecycleStateQueryVisibilityTest {

    /** The states a query may read at all. */
    private static final Set<LifecycleState> QUERYABLE = EnumSet.of(
        LifecycleState.INDEXING,
        LifecycleState.WARMING,
        LifecycleState.ACTIVE,
        LifecycleState.COMPACTING);

    @Nested
    @DisplayName("A partially-indexed segment")
    class PartiallyIndexed {

        @Test
        @DisplayName("is one still taking entries")
        void indexingIsPartial() {
            // INDEXING is the one state where the segment's own contents are still moving: the
            // graph, the cuckoo filter and the LSH index are all being populated as the query
            // reads them, so what it returns is a prefix of what the segment will hold.
            assertThat(LifecycleState.INDEXING.isPartiallyIndexed()).isTrue();
        }

        @Test
        @DisplayName("is not one whose entries are all in")
        void warmingIsNotPartial() {
            // WARMING is reached by completeIndexing: every entry is written and absorbed. What
            // the warming worker does from there is train a product quantizer and encode the
            // vectors with it, and no production query path reads a PQ code - the search reads
            // full vectors either way. A warming segment therefore answers a query with exactly
            // the structures an active one does.
            assertThat(LifecycleState.WARMING.isPartiallyIndexed()).isFalse();
        }

        @Test
        @DisplayName("is not one serving a frozen snapshot")
        void compactingIsNotPartial() {
            // Compaction reclaims tombstones behind a frozen snapshot. The snapshot is a whole
            // answer, not part of one.
            assertThat(LifecycleState.COMPACTING.isPartiallyIndexed()).isFalse();
        }

        @Test
        @DisplayName("is not one that is fully indexed")
        void activeIsNotPartial() {
            assertThat(LifecycleState.ACTIVE.isPartiallyIndexed()).isFalse();
        }
    }

    @Nested
    @DisplayName("The predicate stays inside the query path")
    class ScopedToQueryableStates {

        @Test
        @DisplayName("no state outside it claims to be partially indexed")
        void unqueryableStatesAreNotPartial() {
            // A state a query never reaches cannot be the reason a query dropped a segment, so
            // claiming partiality there would put a second, unreachable meaning on the flag.
            for (LifecycleState state : LifecycleState.values()) {
                if (QUERYABLE.contains(state)) {
                    continue;
                }
                assertThat(state.isPartiallyIndexed())
                    .as("%s is not queryable, so it cannot be a partial answer", state)
                    .isFalse();
            }
        }

        @Test
        @DisplayName("exactly one queryable state is partial")
        void onlyOneQueryableStateIsPartial() {
            assertThat(QUERYABLE.stream().filter(LifecycleState::isPartiallyIndexed).toList())
                .containsExactly(LifecycleState.INDEXING);
        }

        @Test
        @DisplayName("and every state it names is one a query can read")
        void everyPartialStateIsQueryable() {
            for (LifecycleState state : LifecycleState.values()) {
                if (!state.isPartiallyIndexed()) {
                    continue;
                }
                assertThat(state.isQueryable())
                    .as("%s is reported partial, so a query must be able to reach it", state)
                    .isTrue();
            }
        }
    }
}
