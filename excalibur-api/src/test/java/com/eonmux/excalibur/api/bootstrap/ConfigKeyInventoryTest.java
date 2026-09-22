package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.security.TlsConfig;
import com.eonmux.excalibur.api.server.ListenPort;
import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.ConfigKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the three things that have to agree about a configuration key: the constant that declares
 * it, the inventory that judges it, and the template an operator copies.
 *
 * <p>Startup warns that a key it does not recognise is being ignored. That warning is only worth
 * printing if it is true in both directions. A key the node honours but no inventory knows is
 * reported as ignored, and an operator who acts on the warning deletes a line that was working -
 * which is how the crawl allow-lists came to be reported as dead settings. A key in the shipped
 * template that no inventory knows is the same failure reaching every deployment at once, because
 * the documented first run is to copy that template.</p>
 *
 * <p>Reflective and file-driven rather than a written-out list, because a written-out list is
 * exactly the copy that drifts.</p>
 *
 * @author ExcaliburDB
 */
@DisplayName("Configuration key inventory")
class ConfigKeyInventoryTest {

    /** Every class in this module that declares a property key constant. */
    private static final Class<?>[] KEY_DECLARING =
        {ApiSecurityConfig.class, TlsConfig.class, ListenPort.class, CoreComponents.class};

    /** Whether any inventory in the process claims to recognise this key. */
    private static boolean recognised(String key) {
        return ApiConfigKeys.isKnown(key) || ConfigKeys.isKnown(key);
    }

    /** Whether some module has claimed the prefix, and so will judge a typo under it. */
    private static boolean judged(String key) {
        return ConfigKeys.isOwned(key) || ApiConfigKeys.owns(key);
    }

    @Nested
    @DisplayName("against the constants that declare the keys")
    class AgainstTheCode {

        @Test
        @DisplayName("every key this module reads is recognised by an inventory")
        void everyDeclaredKeyIsRecognised() {
            List<String> unrecognised = new ArrayList<>();
            for (String key : declaredKeys()) {
                if (!recognised(key)) {
                    unrecognised.add(key);
                }
            }

            assertThat(unrecognised)
                .as("a key the node honours but no inventory knows is reported at startup as "
                    + "being ignored, and an operator who acts on that deletes a line that works")
                .isEmpty();
        }

        @Test
        @DisplayName("every key this module reads sits under a prefix some module judges")
        void everyDeclaredKeyIsUnderAJudgedPrefix() {
            List<String> unjudged = new ArrayList<>();
            for (String key : declaredKeys()) {
                if (!judged(key)) {
                    unjudged.add(key);
                }
            }

            assertThat(unjudged)
                .as("a prefix nobody claims is a prefix where a typo is silently ignored, which "
                    + "is the failure the startup warning exists to prevent")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("against the template an operator copies")
    class AgainstTheTemplate {

        @Test
        @DisplayName("every key in excalibur.properties.example is recognised")
        void theTemplateNamesNoUnknownKey() throws IOException {
            List<String> unrecognised = new ArrayList<>();
            for (String key : templateKeys()) {
                if (!recognised(key)) {
                    unrecognised.add(key);
                }
            }

            assertThat(unrecognised)
                .as("the documented first run copies this file, so a key here that no inventory "
                    + "knows warns every deployment about a setting that is in force")
                .isEmpty();
        }

        @Test
        @DisplayName("every key an inventory knows is documented in the template")
        void theTemplateDocumentsEveryKnownKey() throws IOException {
            var documented = new TreeSet<>(templateKeys());
            List<String> undocumented = new ArrayList<>();
            for (String key : new TreeSet<>(ConfigKeys.known())) {
                if (!documented.contains(key)) {
                    undocumented.add(key);
                }
            }
            for (String key : new TreeSet<>(ApiConfigKeys.known())) {
                if (!documented.contains(key)) {
                    undocumented.add(key);
                }
            }

            assertThat(undocumented)
                .as("a setting that exists and is not in the annotated template is a setting an "
                    + "operator can only find by reading the source")
                .isEmpty();
        }
    }

    /** Every {@code public static final String KEY_*} this module declares. */
    private static List<String> declaredKeys() {
        List<String> keys = new ArrayList<>();
        for (Class<?> type : KEY_DECLARING) {
            for (Field field : type.getDeclaredFields()) {
                if (isPropertyKey(field)) {
                    keys.add(readKey(field));
                }
            }
        }
        assertThat(keys).as("the reflection found no keys at all, so it is testing nothing")
            .isNotEmpty();
        return keys;
    }

    private static boolean isPropertyKey(Field field) {
        int modifiers = field.getModifiers();
        return Modifier.isPublic(modifiers)
            && Modifier.isStatic(modifiers)
            && Modifier.isFinal(modifiers)
            && field.getType() == String.class
            && field.getName().startsWith("KEY_");
    }

    private static String readKey(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError("could not read " + field.getName(), e);
        }
    }

    @Nested
    @DisplayName("against the reference table in the README")
    class AgainstTheReadme {

        @Test
        @DisplayName("every key an inventory knows has a row in the configuration table")
        void theReadmeDocumentsEveryKnownKey() throws IOException {
            var documented = new TreeSet<>(readmeTableKeys());
            List<String> undocumented = new ArrayList<>();
            for (String key : new TreeSet<>(ConfigKeys.known())) {
                if (!documented.contains(key)) {
                    undocumented.add(key);
                }
            }
            for (String key : new TreeSet<>(ApiConfigKeys.known())) {
                if (!documented.contains(key)) {
                    undocumented.add(key);
                }
            }

            assertThat(undocumented)
                .as("the README's table is where a reader looks for a setting's default, and a "
                    + "key mentioned only in prose elsewhere is one they will conclude does not "
                    + "exist")
                .isEmpty();
        }

        @Test
        @DisplayName("every key the table lists is one some inventory knows")
        void theReadmeNamesNoUnknownKey() throws IOException {
            List<String> unrecognised = new ArrayList<>();
            for (String key : readmeTableKeys()) {
                if (!recognised(key)) {
                    unrecognised.add(key);
                }
            }

            assertThat(unrecognised)
                .as("a row for a setting nothing reads documents a knob that does nothing")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("against the defaults the node actually applies")
    class AgainstTheDefaults {

        /**
         * That a documented default is the node's default.
         *
         * <p>Checked by behaviour rather than by reading the constant beside the parse site,
         * because the constant and the parse site can already be read together and the thing
         * that drifts is the table. Setting a key to the value the table promises must leave a
         * node indistinguishable from one where the key was never set; where it does not, the
         * table is describing a node nobody is running - and it is the table an operator reads
         * before deciding a setting is not worth changing.</p>
         *
         * <p>{@link Config#equals} compares exactly the parsed sections and nothing else, so a
         * key that only reaches the effective properties cannot make this pass by accident.</p>
         */
        @Test
        @DisplayName("setting a key to its documented default changes nothing")
        void documentedDefaultsAreTheRealDefaults() throws IOException {
            Config bare = new Config(Map.of());
            List<String> wrong = new ArrayList<>();
            int checked = 0;
            for (Row row : readmeRows()) {
                if (!ConfigKeys.isOwned(row.key()) || !isScalar(row.documentedDefault())) {
                    continue;
                }
                checked++;
                Config explicit = new Config(Map.of(row.key(), row.documentedDefault()));
                if (!explicit.equals(bare)) {
                    wrong.add(row.key() + " documented as " + row.documentedDefault());
                }
            }

            assertThat(checked)
                .as("no row carried a default this could test, so it is testing nothing")
                .isGreaterThan(40);
            assertThat(wrong)
                .as("a default in the table that the node does not apply is worse than no "
                    + "default at all: it is read as a reason not to set the key")
                .isEmpty();
        }

        /**
         * Whether a documented default is a value rather than a description of one.
         *
         * <p>Several settings have no default to state - an empty list, an absent API key, a
         * path derived from the data directory - and the table says so in prose, emphasised so a
         * reader does not mistake it for a literal. There is no string to set those to that would
         * reproduce the absence, so they are skipped rather than guessed at. Prose is recognised
         * by its emphasis or its parentheses rather than by a list of phrases, which would be one
         * more copy to keep in step.</p>
         *
         * @param documented The default column
         * @return true when it can be set verbatim
         */
        private boolean isScalar(String documented) {
            if (documented.isBlank() || documented.indexOf(' ') >= 0) {
                return false;
            }
            char first = documented.charAt(0);
            return first != '_' && first != '*' && first != '(' && first != '-' && first != '—';
        }
    }

    /**
     * README sections whose {@code excalibur.}-prefixed table rows are not properties file keys.
     *
     * <p>"System properties" documents the JVM {@code -D} namespace, which is a different set of
     * names that no properties-file inventory knows or should. "Documentation" is the table of
     * where to read things, and one of its rows is the template's file name.</p>
     */
    private static final List<String> NOT_SETTINGS_TABLES =
        List.of("### System properties", "### Documentation");

    /**
     * The keys the README gives a table row to.
     *
     * <p>Rows, not prose: the same keys are named throughout the text, and counting those would
     * let every table go empty while this still passed. More than one table carries them - the
     * reference table, and the one in the section on the chunk lexical index - so this reads
     * them all and names the sections that are not settings tables instead.</p>
     *
     * @return Every key with a row
     * @throws IOException if the README cannot be read
     */
    private static List<String> readmeTableKeys() throws IOException {
        List<String> keys = new ArrayList<>();
        for (Row row : readmeRows()) {
            keys.add(row.key());
        }
        return keys;
    }

    /**
     * One settings row: the key it names and the default it promises.
     *
     * @param key           The setting
     * @param documentedDefault The second cell, backticks stripped, exactly as written
     */
    private record Row(String key, String documentedDefault) {
    }

    /**
     * Every settings row in the README, key and documented default together.
     *
     * @return The rows, in the order they appear
     * @throws IOException if the README cannot be read
     */
    private static List<Row> readmeRows() throws IOException {
        List<Row> rows = new ArrayList<>();
        boolean settingsMayFollow = true;
        for (String line : Files.readAllLines(repositoryFile("README.md"))) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#")) {
                settingsMayFollow = !NOT_SETTINGS_TABLES.contains(trimmed);
                continue;
            }
            if (!settingsMayFollow || !trimmed.startsWith("| `excalibur.")) {
                continue;
            }
            int close = trimmed.indexOf('`', 3);
            if (close > 0) {
                rows.add(new Row(trimmed.substring(3, close), secondCell(trimmed)));
            }
        }
        assertThat(rows).as("no rows were parsed out of the README, so this tests nothing")
            .isNotEmpty();
        return rows;
    }

    /**
     * The default column of a settings row.
     *
     * @param row The whole line
     * @return The cell with its backticks and padding removed, or empty when the row has none
     */
    private static String secondCell(String row) {
        String[] cells = row.split("\\|");
        // cells[0] is what precedes the leading pipe, cells[1] the key, cells[2] the default.
        return cells.length < 3 ? "" : cells[2].strip().replace("`", "").strip();
    }

    /**
     * The keys the shipped template sets, commented-out lines included.
     *
     * <p>A commented default counts: it is there to be uncommented, and a key that only appears
     * behind a {@code #} is documented exactly as much as one that does not.</p>
     */
    private static List<String> templateKeys() throws IOException {
        List<String> keys = new ArrayList<>();
        for (String line : Files.readAllLines(template())) {
            String trimmed = line.strip();
            while (trimmed.startsWith("#")) {
                trimmed = trimmed.substring(1).strip();
            }
            int equals = trimmed.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = trimmed.substring(0, equals).strip();
            if (key.startsWith("excalibur.") && !key.contains(" ")) {
                keys.add(key);
            }
        }
        assertThat(keys).as("no keys were parsed out of the template, so this tests nothing")
            .isNotEmpty();
        return keys;
    }

    /** The template, found by walking up from wherever the test happens to be run. */
    private static Path template() {
        return repositoryFile("excalibur.properties.example");
    }

    /**
     * A file at the repository root, found by walking up from wherever the test is run.
     *
     * @param name The file's name at the root
     * @return Its path
     */
    private static Path repositoryFile(String name) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new AssertionError(name + " not found above " + Path.of("").toAbsolutePath());
    }
}
