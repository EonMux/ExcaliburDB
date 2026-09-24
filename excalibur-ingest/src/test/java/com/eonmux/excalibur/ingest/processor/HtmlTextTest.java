package com.eonmux.excalibur.ingest.processor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("HtmlText Tests")
class HtmlTextTest {

    @Nested
    @DisplayName("Which content types are markup")
    class Markup {

        @Test
        @DisplayName("HTML and XHTML are markup, whatever their case or parameters")
        void htmlIsMarkup() {
            assertThat(HtmlText.isMarkup("text/html")).isTrue();
            assertThat(HtmlText.isMarkup("Text/HTML; charset=windows-1252")).isTrue();
            assertThat(HtmlText.isMarkup("application/xhtml+xml")).isTrue();
            assertThat(HtmlText.isMarkup(" application/xhtml+xml ;q=1")).isTrue();
        }

        @Test
        @DisplayName("Other types, and no type, are not")
        void otherTypesAreNot() {
            assertThat(HtmlText.isMarkup("text/plain")).isFalse();
            assertThat(HtmlText.isMarkup("application/xml")).isFalse();
            assertThat(HtmlText.isMarkup("text/htmlx")).isFalse();
            assertThat(HtmlText.isMarkup("")).isFalse();
            assertThat(HtmlText.isMarkup(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("What is extracted")
    class Extraction {

        @Test
        @DisplayName("Tags are removed and the words they wrapped are kept")
        void tagsAreRemoved() {
            String text = HtmlText.extract(
                "<html><body><h1 class=\"t\">Title</h1><p>Ada <em>wrote</em> it.</p></body></html>");

            assertThat(text).isEqualTo("Title\n\nAda wrote it.");
        }

        @Test
        @DisplayName("Script and style contents are dropped, not indexed")
        void scriptAndStyleAreDropped() {
            String text = HtmlText.extract("<head><style>p { color: red }</style>"
                + "<script>var tracker = '<p>'; send(tracker);</script></head>"
                + "<body><p>Visible</p></body>");

            assertThat(text).isEqualTo("Visible");
        }

        @Test
        @DisplayName("Block-level boundaries keep adjacent words apart")
        void blocksDoNotFuseWords() {
            String text = HtmlText.extract(
                "<ul><li>alpha</li><li>beta</li></ul><table><tr><td>gamma</td><td>delta</td>"
                    + "</tr></table><div>epsilon</div>zeta<br>eta");

            assertThat(text.split("\\s+"))
                .containsExactly("alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta");
        }

        @Test
        @DisplayName("Inline elements inside a word do not split it")
        void inlineElementsDoNotSplitWords() {
            assertThat(HtmlText.extract("<p>Ana<b>lyt</b>ical</p>")).isEqualTo("Analytical");
        }

        @Test
        @DisplayName("Character references are decoded")
        void entitiesAreDecoded() {
            assertThat(HtmlText.extract("<p>Caf&eacute; &amp; Caf&#233; &lt;tag&gt;</p>"))
                .isEqualTo("Café & Café <tag>");
        }

        @Test
        @DisplayName("Comments are dropped")
        void commentsAreDropped() {
            assertThat(HtmlText.extract("<p>kept<!-- dropped --> too</p>")).isEqualTo("kept too");
        }

        @Test
        @DisplayName("Indentation and blank lines left by the markup are collapsed")
        void layoutWhitespaceIsCollapsed() {
            String text = HtmlText.extract(
                "<body>\n    <div>\n        <p>one</p>\n\n\n\n        <p>two</p>\n    </div>\n</body>\n");

            assertThat(text).isEqualTo("one\n\ntwo");
        }

        @Test
        @DisplayName("Markup with no text in it extracts to nothing")
        void markupOnlyIsEmpty() {
            assertThat(HtmlText.extract("<html><head><script>x()</script></head><body> </body></html>"))
                .isEmpty();
            assertThat(HtmlText.extract("")).isEmpty();
        }

        @Test
        @DisplayName("Text with no markup in it is left as it reads")
        void plainTextSurvives() {
            assertThat(HtmlText.extract("just words, 1 < 3 and 5 > 4"))
                .isEqualTo("just words, 1 < 3 and 5 > 4");
        }

        @Test
        @DisplayName("Null is refused")
        void nullIsRefused() {
            assertThatNullPointerException().isThrownBy(() -> HtmlText.extract(null));
        }
    }
}
