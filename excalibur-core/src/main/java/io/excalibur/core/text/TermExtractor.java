package io.excalibur.core.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Simple term extractor for text tokenization without NLP dependencies.
 *
 * <h2>Purpose</h2>
 * <p>Extracts significant terms from text for use in:
 * <ul>
 *   <li>Hierarchical Cuckoo filter term insertion during ingestion</li>
 *   <li>Hierarchical Cuckoo filter term lookup during query</li>
 *   <li>Count-Min Sketch term frequency estimation</li>
 * </ul>
 *
 * <h2>Tokenization Strategy</h2>
 * <p>Uses whitespace-based tokenization with:
 * <ul>
 *   <li>Lowercase normalization for case-insensitive matching</li>
 *   <li>Minimum term length filter (default: 2 characters)</li>
 *   <li>Common stop word filtering (optional)</li>
 *   <li>Punctuation stripping from term boundaries</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. All methods are stateless.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Terms are always lowercase</li>
 *   <li>Terms are at least minTermLength characters</li>
 *   <li>Empty/null input produces empty output</li>
 *   <li>Terms contain only alphanumeric characters (punctuation stripped)</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 8.0
 */
public final class TermExtractor {

    /**
     * Default minimum term length.
     * Terms shorter than this are discarded.
     */
    public static final int DEFAULT_MIN_TERM_LENGTH = 2;

    /**
     * Common English stop words to filter out.
     * These words are too common to be useful for filtering.
     */
    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "the", "and", "or", "but", "is", "are", "was", "were",
        "be", "been", "being", "have", "has", "had", "do", "does", "did",
        "will", "would", "could", "should", "may", "might", "must", "shall",
        "can", "to", "of", "in", "for", "on", "with", "at", "by", "from",
        "as", "into", "through", "during", "before", "after", "above",
        "below", "between", "under", "again", "further", "then", "once",
        "here", "there", "when", "where", "why", "how", "all", "each",
        "few", "more", "most", "other", "some", "such", "no", "nor",
        "not", "only", "own", "same", "so", "than", "too", "very",
        "s", "t", "just", "don", "now", "i", "me", "my", "myself",
        "we", "our", "ours", "ourselves", "you", "your", "yours",
        "yourself", "yourselves", "he", "him", "his", "himself", "she",
        "her", "hers", "herself", "it", "its", "itself", "they", "them",
        "their", "theirs", "themselves", "what", "which", "who", "whom",
        "this", "that", "these", "those", "am", "if", "because", "until",
        "while", "about", "against", "both", "any", "up", "down", "out",
        "off", "over", "also"
    );

    private final int minTermLength;
    private final boolean filterStopWords;

    /**
     * Creates a TermExtractor with default settings.
     */
    public TermExtractor() {
        this(DEFAULT_MIN_TERM_LENGTH, true);
    }

    /**
     * Creates a TermExtractor with custom settings.
     *
     * @param minTermLength Minimum term length (must be >= 1)
     * @param filterStopWords Whether to filter common stop words
     * @throws IllegalArgumentException if minTermLength < 1
     */
    public TermExtractor(int minTermLength, boolean filterStopWords) {
        if (minTermLength < 1) {
            throw new IllegalArgumentException("minTermLength must be >= 1: " + minTermLength);
        }
        this.minTermLength = minTermLength;
        this.filterStopWords = filterStopWords;
    }

    /**
     * Extracts terms from the given text.
     *
     * <p>The extraction process:
     * <ol>
     *   <li>Split text on whitespace</li>
     *   <li>Strip punctuation from term boundaries</li>
     *   <li>Convert to lowercase</li>
     *   <li>Filter by minimum length</li>
     *   <li>Optionally filter stop words</li>
     * </ol>
     *
     * @param text The text to extract terms from (may be null or empty)
     * @return List of extracted terms (never null, may be empty)
     */
    public List<String> extract(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> terms = new ArrayList<>();
        StringBuilder currentTerm = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (Character.isWhitespace(c)) {
                if (currentTerm.length() > 0) {
                    addTermIfValid(terms, currentTerm.toString());
                    currentTerm.setLength(0);
                }
            } else {
                currentTerm.append(c);
            }
        }

        // Add final term if any
        if (currentTerm.length() > 0) {
            addTermIfValid(terms, currentTerm.toString());
        }

        return terms;
    }

    /**
     * Extracts unique terms from the given text.
     *
     * <p>Same as {@link #extract(String)} but returns a set of unique terms.</p>
     *
     * @param text The text to extract terms from
     * @return Set of unique extracted terms (never null, may be empty)
     */
    public Set<String> extractUnique(String text) {
        List<String> terms = extract(text);
        return new HashSet<>(terms);
    }

    /**
     * Extracts terms and returns them as lowercase strings.
     *
     * <p>This is a convenience method that ensures consistent case handling.</p>
     *
     * @param text The text to extract terms from
     * @return List of lowercase terms
     */
    public List<String> extractLowercase(String text) {
        // extract() already converts to lowercase, so this is just an alias
        return extract(text);
    }

    /**
     * Processes a raw term and adds it to the list if valid.
     *
     * <p>Processing steps:
     * <ol>
     *   <li>Strip punctuation from boundaries</li>
     *   <li>Convert to lowercase</li>
     *   <li>Check minimum length</li>
     *   <li>Optionally check stop words</li>
     * </ol>
     *
     * @param terms The list to add the term to
     * @param rawTerm The raw term to process
     */
    private void addTermIfValid(List<String> terms, String rawTerm) {
        // Strip punctuation from boundaries
        String term = stripPunctuation(rawTerm);

        // Convert to lowercase with a fixed locale: the default locale would make
        // tokenization JVM-dependent (Turkish 'I' maps to a dotless i), silently
        // desynchronizing persisted term filters from the terms a query produces.
        term = term.toLowerCase(java.util.Locale.ROOT);

        // Check minimum length
        if (term.length() < minTermLength) {
            return;
        }

        // Check stop words
        if (filterStopWords && STOP_WORDS.contains(term)) {
            return;
        }

        // Check if term contains at least one letter or digit (not just symbols)
        if (!hasAlphanumeric(term)) {
            return;
        }

        terms.add(term);
    }

    /**
     * Strips punctuation from the beginning and end of a term.
     *
     * @param term The term to strip
     * @return Term with boundary punctuation removed
     */
    private String stripPunctuation(String term) {
        int start = 0;
        int end = term.length();

        // Strip from start
        while (start < end && !Character.isLetterOrDigit(term.charAt(start))) {
            start++;
        }

        // Strip from end
        while (end > start && !Character.isLetterOrDigit(term.charAt(end - 1))) {
            end--;
        }

        return term.substring(start, end);
    }

    /**
     * Checks if a term contains at least one alphanumeric character.
     *
     * @param term The term to check
     * @return true if term contains at least one letter or digit
     */
    private boolean hasAlphanumeric(String term) {
        for (int i = 0; i < term.length(); i++) {
            if (Character.isLetterOrDigit(term.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the minimum term length setting.
     *
     * @return Minimum term length
     */
    public int getMinTermLength() {
        return minTermLength;
    }

    /**
     * Returns whether stop word filtering is enabled.
     *
     * @return true if stop words are filtered
     */
    public boolean isFilterStopWords() {
        return filterStopWords;
    }

    /**
     * Static utility method to extract terms with default settings.
     *
     * <p>This is a convenience method for quick term extraction.</p>
     *
     * @param text The text to extract terms from
     * @return List of extracted terms
     */
    public static List<String> extractTerms(String text) {
        return new TermExtractor().extract(text);
    }

    /**
     * Static utility method to extract unique terms with default settings.
     *
     * @param text The text to extract terms from
     * @return Set of unique extracted terms
     */
    public static Set<String> extractUniqueTerms(String text) {
        return new TermExtractor().extractUnique(text);
    }

    @Override
    public String toString() {
        return String.format("TermExtractor[minTermLength=%d, filterStopWords=%s]",
            minTermLength, filterStopWords);
    }
}
