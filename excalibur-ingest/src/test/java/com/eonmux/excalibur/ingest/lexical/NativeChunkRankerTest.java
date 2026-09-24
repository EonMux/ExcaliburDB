package com.eonmux.excalibur.ingest.lexical;

import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.retrieval.expand.SearchFormulation;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionPlanner;
import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.Ranker;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the expanded query actually asks the native store for.
 *
 * <p>The ranker is where formulations stop being a list and become one weighted term set, so it
 * is where the promise that a guess about vocabulary cannot outrank the words the user typed is
 * either kept or lost. The corpus here is built so nothing but the weights can decide the
 * ranking: both documents are the same length and each distinguishing term occurs in exactly one
 * of them, which makes their BM25 contributions identical before any weight is applied.</p>
 */
@DisplayName("Native chunk ranker")
class NativeChunkRankerTest {

    private static CompactionPlanner standard() {
        return CompactionPlanner.standard(0.2, Duration.ofHours(1), 16);
    }

    /** An expander whose generated formulations all agree on one term. */
    private static QueryExpander agreeingOn(String shared, double weight, String... rest) {
        return QueryExpander.infallible(text -> {
            List<SearchFormulation> out = new java.util.ArrayList<>();
            out.add(SearchFormulation.original(text));
            for (String tail : rest) {
                out.add(new SearchFormulation(shared + " " + tail, weight));
            }
            return out;
        });
    }

    private static NativeChunkIndex twoComparableChunks(Path dir) throws Exception {
        AtomicLong epoch = new AtomicLong(1);
        NativeChunkIndex index = NativeChunkIndex.open(dir, epoch::get, 4, standard());
        index.add("kb:alpha.txt:chunk:0", "alpha filler one two three four");
        index.add("kb:bravo.txt:chunk:0", "bravo filler one two three four");
        index.refresh();
        epoch.incrementAndGet();
        return index;
    }

    @Test
    @DisplayName("expansions that agree on a term cannot outrank the user's own word")
    void agreementCannotOutrankTheUser(@TempDir Path dir) throws Exception {
        // Two surviving formulations sharing one token sum to 1.8 against the user's 1.0, so
        // the document holding only the guessed term beats the one holding the typed term.
        try (NativeChunkIndex index = twoComparableChunks(dir)) {
            Ranker ranker = index.ranker(agreeingOn("bravo", 0.9, "charlie", "delta"));

            RankedList out = ranker.rank(RetrievalQuery.ofText("alpha"), 5);

            assertThat(out.documents()).isNotEmpty();
            assertThat(index.chunkIdOf(out.documents().get(0).documentId()))
                .as("the word the user typed must lead a guess two expansions agreed on")
                .isEqualTo("kb:alpha.txt:chunk:0");
        }
    }

    @Test
    @DisplayName("agreement still counts for more than a single suggestion")
    void agreementStillCountsForMore(@TempDir Path dir) throws Exception {
        // Bounding the sum must not flatten it: a term two formulations named still has to
        // beat one only the same-weight single formulation named.
        AtomicLong epoch = new AtomicLong(1);
        try (NativeChunkIndex index = NativeChunkIndex.open(dir, epoch::get, 4, standard())) {
            index.add("kb:agreed.txt:chunk:0", "bravo filler one two three four");
            index.add("kb:alone.txt:chunk:0", "echo filler one two three four");
            index.refresh();
            epoch.incrementAndGet();

            QueryExpander expander = QueryExpander.infallible(text -> List.of(
                SearchFormulation.original(text),
                new SearchFormulation("bravo charlie", 0.9),
                new SearchFormulation("bravo delta", 0.9),
                new SearchFormulation("echo foxtrot", 0.9)));

            RankedList out = index.ranker(expander).rank(RetrievalQuery.ofText("alpha"), 5);

            assertThat(index.chunkIdOf(out.documents().get(0).documentId()))
                .as("two formulations agreeing is stronger evidence than one suggesting")
                .isEqualTo("kb:agreed.txt:chunk:0");
        }
    }
}
