package io.excalibur.retrieval.snippet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Choosing the window of a passage worth showing, and locating the terms inside it.
 */
@DisplayName("SnippetSelector")
class SnippetSelectorTest {

    private static final String LEAD = "Introduction. ".repeat(24);

    private static final String MATCH = "The kernel OOM killer sent SIGKILL to the process. ";

    private static final String PASSAGE = LEAD + MATCH + "Afterwards. ".repeat(24);

    private static Snippet window(String text, String query, int chars) {
        List<String> terms = QueryTerms.of(query, QueryTerms.MAX_TERMS);
        return SnippetSelector.around(text,
            QueryTerms.occurrences(text, terms, SnippetSelector.MAX_MATCHES_PER_PASSAGE), chars);
    }

    @Test
    @DisplayName("the window holds the match rather than the opening of the passage")
    void theWindowHoldsTheMatch() {
        Snippet snippet = window(PASSAGE, "OOM killer", 120);

        assertThat(snippet.text()).contains("OOM killer");
        assertThat(snippet.offset()).isGreaterThan(0);
        assertThat(snippet.truncated()).isTrue();
        assertThat(snippet.text().length()).isLessThanOrEqualTo(120);
    }

    /**
     * The window is a verbatim substring, so its offset still points at the same character of
     * the passage. A caller handed a decorated quotation cannot locate it again.
     */
    @Test
    @DisplayName("the window is a verbatim substring at the offset it reports")
    void theWindowIsVerbatimAtItsOffset() {
        Snippet snippet = window(PASSAGE, "SIGKILL", 100);

        assertThat(PASSAGE.substring(snippet.offset(),
            snippet.offset() + snippet.text().length())).isEqualTo(snippet.text());
    }

    @Test
    @DisplayName("the matches are located against the window, not against the passage")
    void matchesAreRebasedToTheWindow() {
        Snippet snippet = window(PASSAGE, "SIGKILL", 100);

        assertThat(snippet.matches()).isNotEmpty();
        for (TermOccurrence match : snippet.matches()) {
            assertThat(snippet.text().substring(match.start(), match.end()).toLowerCase())
                .isEqualTo(match.term());
        }
    }

    /**
     * A passage saying three of the query's terms once each is better evidence than one saying
     * a single term repeatedly, and the window that gets shown should be the first.
     */
    @Test
    @DisplayName("the window covering the most distinct terms wins over the densest repetition")
    void distinctTermsBeatRepetition() {
        String text = "alpha alpha alpha alpha alpha alpha alpha alpha. "
            + "Filler that separates the two regions of this passage from each other entirely. "
            + "alpha beta gamma together.";

        Snippet snippet = window(text, "alpha beta gamma", 40);

        assertThat(snippet.text()).contains("alpha beta gamma");
    }

    @Test
    @DisplayName("a passage that fits is returned whole and says it was not clipped")
    void aShortPassageIsWhole() {
        Snippet snippet = window("the OOM killer ran", "OOM", 120);

        assertThat(snippet.text()).isEqualTo("the OOM killer ran");
        assertThat(snippet.offset()).isZero();
        assertThat(snippet.truncated()).isFalse();
    }

    @Test
    @DisplayName("a passage matching nothing falls back to its opening window")
    void noMatchOpensAtTheStart() {
        Snippet snippet = window(PASSAGE, "zookeeper", 60);

        assertThat(snippet.offset()).isZero();
        assertThat(snippet.matches()).isEmpty();
        assertThat(snippet.truncated()).isTrue();
    }

    /**
     * An unpaired surrogate is not encodable and leaves the wire as a literal question mark,
     * so a window must never be cut between the halves of one character.
     */
    @Test
    @DisplayName("a window never splits a surrogate pair")
    void neverSplitsASurrogatePair() {
        String text = "ab😀cd";

        Snippet snippet = SnippetSelector.fromStart(text, 3);

        assertThat(snippet.text()).isEqualTo("ab");
        assertThat(snippet.truncated()).isTrue();
    }

    @Test
    @DisplayName("a window of nothing is refused")
    void refusesAnEmptyWindow() {
        assertThatThrownBy(() -> SnippetSelector.fromStart("anything", 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("windowChars");
    }
}
