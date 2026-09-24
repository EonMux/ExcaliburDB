package com.eonmux.excalibur.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Holds USAGE.md's embedded example to the code {@link EmbeddedUsageExampleTest} compiles and runs.
 *
 * <p>USAGE.md says the snippet is {@code ingestAndQuery} and {@code reopenAndErase}, and that
 * claim is what an embedder relies on when copying it: that it compiles, and that it does what the
 * test proves. Nothing held the two together, so an edit to either one left the other describing
 * code that no longer existed. This compares them token for token. Layout is ignored because the
 * documentation is indented one level less than the methods.</p>
 */
@DisplayName("Embedded usage example matches USAGE.md")
class EmbeddedUsageDocumentationTest {

    /** The heading the example sits under; the first Java block after it is the example. */
    private static final String HEADING = "### Ingesting and querying in-process";

    private static final String METHOD = "static List<SearchHit> ingestAndQuery(";
    private static final String REOPEN = "static SearchAnswer reopenAndErase(";

    private static final Pattern IMPORT = Pattern.compile("(?m)^import ([\\w.]+);");

    @Test
    @DisplayName("the documented method is the one the test runs")
    void methodMatches() throws IOException {
        String documented = documentedExample();
        String compiled = exampleSource();

        assertThat(normalise(member(documented, METHOD)))
            .isEqualTo(normalise(member(compiled, METHOD)));
    }

    @Test
    @DisplayName("the documented reopening is the one the test runs")
    void reopenMatches() throws IOException {
        assertThat(normalise(member(documentedExample(), REOPEN)))
            .isEqualTo(normalise(member(exampleSource(), REOPEN)));
    }

    @Test
    @DisplayName("the documented imports are exactly the ones the example uses")
    void importsMatch() throws IOException {
        String compiled = exampleSource();
        String example = member(compiled, METHOD) + member(compiled, REOPEN);
        Set<String> used = new LinkedHashSet<>();
        for (String imported : imports(compiled)) {
            String simpleName = imported.substring(imported.lastIndexOf('.') + 1);
            if (Pattern.compile("\\b" + simpleName + "\\b").matcher(example).find()) {
                used.add(imported);
            }
        }

        assertThat(imports(documentedExample()))
            .as("an import the example needs and the snippet lacks will not compile once copied, "
                + "and one it does not need is noise an embedder cannot tell from a requirement")
            .containsExactlyInAnyOrderElementsOf(used);
    }

    /** The extraction itself, which the comparisons above are only as good as. */
    @Nested
    @DisplayName("extracting a member")
    class Extraction {

        @Test
        @DisplayName("a changed token is a difference, and a changed layout is not")
        void comparesTokens() {
            String indented = "class A {\n    static int f(int x) {\n        return x + 1;\n    }\n}";
            String flush = "static int f(int x) {\n    return x + 1;\n}";
            String changed = "static int f(int x) {\n    return x + 2;\n}";

            String signature = "static int f(";
            assertThat(normalise(member(flush, signature)))
                .isEqualTo(normalise(member(indented, signature)));
            assertThat(normalise(member(changed, signature)))
                .isNotEqualTo(normalise(member(indented, signature)));
        }

        @Test
        @DisplayName("braces in strings, characters and comments do not end the member")
        void skipsBracesThatAreNotCode() {
            String source = "static void f() {\n"
                + "    String s = \"}\\\"}\"; // }\n"
                + "    char c = '}'; /* } */\n"
                + "    g();\n"
                + "}\n"
                + "static void after() {}";

            assertThat(member(source, "static void f(")).endsWith("g();\n}")
                .doesNotContain("after");
        }

        @Test
        @DisplayName("a member that is not there is reported, not compared as empty")
        void reportsAMissingMember() {
            assertThatThrownBy(() -> member("class A {}", METHOD))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ingestAndQuery");
        }
    }

    /**
     * Reads the first Java block after {@link #HEADING} in USAGE.md.
     *
     * @return The block's contents, without its fences
     * @throws IOException if USAGE.md cannot be read
     */
    private static String documentedExample() throws IOException {
        String usage = Files.readString(pathProperty("excalibur.usage"));
        int heading = usage.indexOf(HEADING);
        assertThat(heading).as("USAGE.md has no '%s' section", HEADING).isNotNegative();
        int open = usage.indexOf("```java\n", heading);
        assertThat(open).as("no Java block under '%s'", HEADING).isNotNegative();
        int start = open + "```java\n".length();
        int close = usage.indexOf("\n```", start);
        assertThat(close).as("the Java block under '%s' is not closed", HEADING).isNotNegative();
        return usage.substring(start, close + 1);
    }

    private static String exampleSource() throws IOException {
        return Files.readString(pathProperty("excalibur.embeddedExampleSource"));
    }

    private static Path pathProperty(String name) {
        String value = System.getProperty(name);
        assertThat(value)
            .as("system property %s, set by the embeddedExampleTest task", name)
            .isNotBlank();
        return Path.of(value);
    }

    private static Set<String> imports(String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = IMPORT.matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * Collapses every run of whitespace, so only the tokens and their order are compared.
     *
     * @param code Java source
     * @return The same source on one line
     */
    static String normalise(String code) {
        return code.strip().replaceAll("\\s+", " ");
    }

    /**
     * Cuts one member out of Java source, from its signature to its closing brace.
     *
     * <p>Braces inside string and character literals and inside comments are skipped, since the
     * example's comments and strings are free to contain them.</p>
     *
     * @param source    The source to search
     * @param signature The text the member's declaration starts with
     * @return The member, signature through closing brace
     * @throws AssertionError if the member is absent or never closes
     */
    static String member(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            throw new AssertionError("no member starting '" + signature + "'");
        }
        int depth = 0;
        int i = source.indexOf('{', start);
        while (i >= 0 && i < source.length()) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = pastLiteral(source, i, c);
                continue;
            }
            if (source.startsWith("//", i)) {
                i = source.indexOf('\n', i);
                continue;
            }
            if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? -1 : end + 2;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i + 1);
            }
            i++;
        }
        throw new AssertionError("member starting '" + signature + "' is never closed");
    }

    private static int pastLiteral(String source, int open, char quote) {
        int i = open + 1;
        while (i < source.length() && source.charAt(i) != quote) {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        return i + 1;
    }
}
