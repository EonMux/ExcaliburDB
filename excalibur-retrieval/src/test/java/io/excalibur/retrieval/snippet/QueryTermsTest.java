package io.excalibur.retrieval.snippet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The words a query is explained and highlighted against.
 */
@DisplayName("QueryTerms")
class QueryTermsTest {

    @Test
    @DisplayName("terms are the distinct words of the query, lower-cased, in the order typed")
    void distinctLowerCasedInOrder() {
        assertThat(QueryTerms.of("OOM killer OOM SIGKILL", QueryTerms.MAX_TERMS))
            .containsExactly("oom", "killer", "sigkill");
    }

    @Test
    @DisplayName("punctuation and identifiers split the way a reader would expect")
    void punctuationSplitsTerms() {
        assertThat(QueryTerms.of("error-code ENOMEM (errno 12)", QueryTerms.MAX_TERMS))
            .containsExactly("error", "code", "enomem", "errno", "12");
    }

    /**
     * A highlight that disagrees with the passage beside it is worse than no highlight, so a
     * term matches a whole word of the text and never a part of one.
     */
    @Test
    @DisplayName("a term matches a whole word and not a fragment of one")
    void wholeWordsOnly() {
        List<TermOccurrence> found = QueryTerms.occurrences("there is room in the room",
            List.of("oom"), 32);

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("every occurrence is reported, in the order it appears")
    void everyOccurrenceInOrder() {
        List<TermOccurrence> found = QueryTerms.occurrences("oom and OOM again",
            List.of("oom"), 32);

        assertThat(found).hasSize(2);
        assertThat(found.get(0).start()).isZero();
        assertThat(found.get(1).start()).isEqualTo(8);
    }

    @Test
    @DisplayName("the scan stops at the bound rather than reporting an unbounded list")
    void theScanIsBounded() {
        assertThat(QueryTerms.occurrences("oom ".repeat(100), List.of("oom"), 5)).hasSize(5);
    }

    /**
     * Every term costs a pass over every returned passage, and how many there are is set by
     * how long a query the caller types. Refused rather than trimmed: a query explained
     * against half of its own words reports terms as absent from a passage that contains them.
     */
    @Test
    @DisplayName("a query with more terms than can be explained is refused, not trimmed")
    void tooManyTermsIsRefused() {
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < QueryTerms.MAX_TERMS + 1; i++) {
            query.append("term").append(i).append(' ');
        }

        assertThatThrownBy(() -> QueryTerms.of(query.toString(), QueryTerms.MAX_TERMS))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("distinct terms");
    }
}
