package io.excalibur.core.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TermExtractor}.
 *
 * @author ExcaliburDB
 * @since 8.0
 */
class TermExtractorTest {

    private final TermExtractor extractor = new TermExtractor();

    @Test
    @DisplayName("extract returns empty list for null input")
    void testExtractNull() {
        List<String> terms = extractor.extract(null);
        assertNotNull(terms);
        assertTrue(terms.isEmpty());
    }

    @Test
    @DisplayName("extract returns empty list for empty string")
    void testExtractEmpty() {
        List<String> terms = extractor.extract("");
        assertNotNull(terms);
        assertTrue(terms.isEmpty());
    }

    @Test
    @DisplayName("extract returns empty list for whitespace only")
    void testExtractWhitespaceOnly() {
        List<String> terms = extractor.extract("   \t\n  ");
        assertNotNull(terms);
        assertTrue(terms.isEmpty());
    }

    @Test
    @DisplayName("extract returns lowercase terms")
    void testExtractLowercase() {
        List<String> terms = extractor.extract("Hello WORLD");
        assertEquals(2, terms.size());
        assertTrue(terms.contains("hello"));
        assertTrue(terms.contains("world"));
    }

    @Test
    @DisplayName("extract filters short terms")
    void testExtractFiltersShortTerms() {
        // "I" and "a" are too short (default min length is 2)
        // "go" is 2 chars and not a stop word, "am" would be filtered as stop word
        List<String> terms = extractor.extract("I go a test");
        assertTrue(terms.contains("go"));
        assertTrue(terms.contains("test"));
        assertFalse(terms.contains("i"));
        assertFalse(terms.contains("a"));
    }

    @Test
    @DisplayName("extract filters stop words")
    void testExtractFiltersStopWords() {
        List<String> terms = extractor.extract("the quick brown fox");
        assertTrue(terms.contains("quick"));
        assertTrue(terms.contains("brown"));
        assertTrue(terms.contains("fox"));
        assertFalse(terms.contains("the"));
    }

    @Test
    @DisplayName("extract strips punctuation from boundaries")
    void testExtractStripsPunctuation() {
        // Note: "how", "are", "you", "now" are stop words and will be filtered
        // Using "code" and "data" (not stop words) to test punctuation stripping
        List<String> terms = extractor.extract("Hello, world! Code works well?");
        assertTrue(terms.contains("hello"));
        assertTrue(terms.contains("world"));
        assertTrue(terms.contains("code"));
        assertTrue(terms.contains("works"));
        assertTrue(terms.contains("well"));
    }

    @Test
    @DisplayName("extract handles multiple spaces")
    void testExtractMultipleSpaces() {
        List<String> terms = extractor.extract("word1   word2    word3");
        assertTrue(terms.contains("word1"));
        assertTrue(terms.contains("word2"));
        assertTrue(terms.contains("word3"));
    }

    @Test
    @DisplayName("extract handles newlines and tabs")
    void testExtractNewlinesAndTabs() {
        List<String> terms = extractor.extract("word1\nword2\tword3");
        assertTrue(terms.contains("word1"));
        assertTrue(terms.contains("word2"));
        assertTrue(terms.contains("word3"));
    }

    @Test
    @DisplayName("extract filters terms with only symbols")
    void testExtractFiltersSymbolsOnly() {
        List<String> terms = extractor.extract("word1 --- word2 $$$ word3");
        assertTrue(terms.contains("word1"));
        assertTrue(terms.contains("word2"));
        assertTrue(terms.contains("word3"));
        // The symbol-only parts should be filtered
        assertFalse(terms.contains("---"));
        assertFalse(terms.contains("$$$"));
    }

    @Test
    @DisplayName("extractUnique returns unique terms")
    void testExtractUnique() {
        Set<String> terms = extractor.extractUnique("word word word");
        assertEquals(1, terms.size());
        assertTrue(terms.contains("word"));
    }

    @Test
    @DisplayName("extractUnique handles duplicates correctly")
    void testExtractUniqueWithDuplicates() {
        Set<String> terms = extractor.extractUnique("the quick the brown the fox");
        // "the" is a stop word and should be filtered
        assertFalse(terms.contains("the"));
        assertTrue(terms.contains("quick"));
        assertTrue(terms.contains("brown"));
        assertTrue(terms.contains("fox"));
    }

    @Test
    @DisplayName("extractTerms static method works correctly")
    void testExtractTermsStatic() {
        List<String> terms = TermExtractor.extractTerms("Hello World");
        assertEquals(2, terms.size());
        assertTrue(terms.contains("hello"));
        assertTrue(terms.contains("world"));
    }

    @Test
    @DisplayName("extractUniqueTerms static method works correctly")
    void testExtractUniqueTermsStatic() {
        Set<String> terms = TermExtractor.extractUniqueTerms("test test test");
        assertEquals(1, terms.size());
        assertTrue(terms.contains("test"));
    }

    @Test
    @DisplayName("Custom minTermLength is respected")
    void testCustomMinTermLength() {
        TermExtractor shortExtractor = new TermExtractor(1, false);
        List<String> terms = shortExtractor.extract("a b c d");
        assertTrue(terms.contains("a"));
        assertTrue(terms.contains("b"));
        assertTrue(terms.contains("c"));
        assertTrue(terms.contains("d"));
    }

    @Test
    @DisplayName("Stop word filtering can be disabled")
    void testStopWordFilteringDisabled() {
        TermExtractor noStopWords = new TermExtractor(2, false);
        List<String> terms = noStopWords.extract("the quick brown fox");
        assertTrue(terms.contains("the"));
        assertTrue(terms.contains("quick"));
        assertTrue(terms.contains("brown"));
        assertTrue(terms.contains("fox"));
    }

    @Test
    @DisplayName("Constructor rejects invalid minTermLength")
    void testConstructorRejectsInvalidMinTermLength() {
        assertThrows(IllegalArgumentException.class, () ->
            new TermExtractor(0, true));
        assertThrows(IllegalArgumentException.class, () ->
            new TermExtractor(-1, true));
    }

    @Test
    @DisplayName("Getters return correct values")
    void testGetters() {
        assertEquals(2, extractor.getMinTermLength());
        assertTrue(extractor.isFilterStopWords());

        TermExtractor custom = new TermExtractor(5, false);
        assertEquals(5, custom.getMinTermLength());
        assertFalse(custom.isFilterStopWords());
    }

    @Test
    @DisplayName("toString returns meaningful representation")
    void testToString() {
        String str = extractor.toString();
        assertTrue(str.contains("TermExtractor"));
        assertTrue(str.contains("minTermLength=2"));
        assertTrue(str.contains("filterStopWords=true"));
    }

    @Test
    @DisplayName("Common stop words are filtered")
    void testCommonStopWords() {
        String text = "a an the and or but is are was were be been being have has had " +
            "do does did will would could should may might must shall can to of in for on " +
            "with at by from as into through during before after above below between under";

        List<String> terms = extractor.extract(text);
        assertTrue(terms.isEmpty(), "All common stop words should be filtered");
    }

    @Test
    @DisplayName("Mixed content is handled correctly")
    void testMixedContent() {
        String text = "Machine learning is a subset of artificial intelligence.";
        List<String> terms = extractor.extract(text);

        assertTrue(terms.contains("machine"));
        assertTrue(terms.contains("learning"));
        assertTrue(terms.contains("subset"));
        assertTrue(terms.contains("artificial"));
        assertTrue(terms.contains("intelligence"));

        // Stop words should be filtered
        assertFalse(terms.contains("is"));
        assertFalse(terms.contains("a"));
        assertFalse(terms.contains("of"));
    }

    @Test
    @DisplayName("Numbers are preserved in terms")
    void testNumbersPreserved() {
        String text = "version2 beta3 release1";
        List<String> terms = extractor.extract(text);

        assertTrue(terms.contains("version2"));
        assertTrue(terms.contains("beta3"));
        assertTrue(terms.contains("release1"));
    }

    @Test
    @DisplayName("Hyphenated words are handled")
    void testHyphenatedWords() {
        // Hyphenated words are treated as single terms
        String text = "state-of-the-art technology";
        List<String> terms = extractor.extract(text);

        // The hyphens should be stripped, leaving individual words
        // (depending on implementation)
        assertFalse(terms.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPPERCASE", "MixedCase", "camelCase"})
    @DisplayName("All case variations are converted to lowercase")
    void testCaseConversion(String input) {
        List<String> terms = extractor.extract(input);
        assertEquals(1, terms.size());
        assertEquals(input.toLowerCase(), terms.get(0));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("extractTerms static handles null and empty")
    void testStaticExtractTermsNullAndEmpty(String input) {
        List<String> terms = TermExtractor.extractTerms(input);
        assertNotNull(terms);
        assertTrue(terms.isEmpty());
    }
}
