package com.eonmux.excalibur.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That {@link ConfigKeys} knows every key this module actually reads.
 *
 * <h2>Why this is not covered by the constants</h2>
 * <p>The API module builds its inventory reflectively from {@code KEY_*} constants, so a key
 * cannot be added there without being recognised. This module has no such constants: each
 * section names its keys as string literals at the parse site, and {@link ConfigKeys#KNOWN} is a
 * hand-written list beside them. Two lists, and only one of them is the one the node obeys.
 *
 * <p>Drift in the direction this catches is the damaging one. A key parsed here and missing from
 * the inventory is honoured by the node and reported at startup as being ignored, so an operator
 * who believes the warning deletes a line that was working. The opposite direction - an inventory
 * entry nothing reads - is already caught by the API module's test against the README table and
 * the shipped template, both of which would have to document a setting that does nothing.
 *
 * <p>Source text rather than reflection, because a string literal at a call site leaves nothing
 * behind to reflect over.
 */
@DisplayName("Configuration key parse sites")
class ConfigKeyParseSiteTest {

    /**
     * A property key as it appears at a parse site.
     *
     * <p>A string literal, so prose in a comment or a javadoc is not mistaken for a key. Two
     * segments at least, and never ending at a dot: {@code "excalibur.properties"} is the
     * configuration file's name and {@code "excalibur.graph."} is a prefix an error message is
     * built from, and neither is a setting anything reads.</p>
     */
    private static final Pattern LITERAL =
        Pattern.compile("\"(excalibur\\.[a-z0-9\\-]+(?:\\.[a-z0-9\\-]+)+)\"");

    /** The inventory itself, whose literals are the list rather than a use of it. */
    private static final String INVENTORY = "ConfigKeys.java";

    @Test
    @DisplayName("every key named at a parse site is one the inventory recognises")
    void everyParsedKeyIsKnown() throws IOException {
        List<String> parsed = parsedKeys();
        assertThat(parsed)
            .as("no key literals were found, so this is testing nothing")
            .isNotEmpty();

        List<String> unrecognised = new ArrayList<>();
        for (String key : new TreeSet<>(parsed)) {
            if (!ConfigKeys.isKnown(key)) {
                unrecognised.add(key);
            }
        }

        assertThat(unrecognised)
            .as("a key this module parses but the inventory does not know is honoured by the "
                + "node and reported at startup as ignored, and an operator who acts on that "
                + "warning deletes a setting that was in force")
            .isEmpty();
    }

    @Test
    @DisplayName("every key named at a parse site sits under a prefix this module claims")
    void everyParsedKeyIsUnderAnOwnedPrefix() throws IOException {
        List<String> unowned = new ArrayList<>();
        for (String key : new TreeSet<>(parsedKeys())) {
            if (!ConfigKeys.isOwned(key)) {
                unowned.add(key);
            }
        }

        assertThat(unowned)
            .as("a prefix this module reads but does not claim is a prefix where a typo is "
                + "accepted in silence, which is what the startup warning exists to prevent")
            .isEmpty();
    }

    /**
     * Every {@code "excalibur.*"} string literal in this module's main sources.
     *
     * <p>The inventory's own file is left out: its literals are the list being checked rather
     * than a use of it, and including them would let the list vouch for itself.</p>
     *
     * @return The keys, with repeats
     * @throws IOException if the sources cannot be read
     */
    private static List<String> parsedKeys() throws IOException {
        List<String> keys = new ArrayList<>();
        Path sources = moduleSources();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (!file.getFileName().toString().endsWith(".java")
                        || INVENTORY.equals(file.getFileName().toString())) {
                    continue;
                }
                Matcher matcher = LITERAL.matcher(
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                while (matcher.find()) {
                    keys.add(matcher.group(1));
                }
            }
        }
        return keys;
    }

    /**
     * This module's main sources, found by walking up from wherever the test is run.
     *
     * @return The source root
     */
    private static Path moduleSources() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("excalibur-config/src/main/java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new AssertionError(
            "excalibur-config sources not found above " + Path.of("").toAbsolutePath());
    }
}
