package com.eonmux.excalibur.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ConfigLoader.
 *
 * <p>Tests verify configuration loading, file parsing, and environment variable overrides.</p>
 */
@DisplayName("ConfigLoader")
class ConfigLoaderTest {

    @TempDir
    Path tempDir;

    private Path configFile;

    @BeforeEach
    void setUp() throws IOException {
        configFile = tempDir.resolve("test-config.properties");
    }

    @AfterEach
    void tearDown() {
        // Clean up
    }

    @Nested
    @DisplayName("load()")
    class Load {

        @Test
        @DisplayName("rejects null config path")
        void rejectsNullConfigPath() {
            assertThatThrownBy(() -> ConfigLoader.load(null, Collections.emptyMap()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Config path cannot be null or empty");
        }

        @Test
        @DisplayName("rejects empty config path")
        void rejectsEmptyConfigPath() {
            assertThatThrownBy(() -> ConfigLoader.load("", Collections.emptyMap()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Config path cannot be null or empty");
        }

        @Test
        @DisplayName("throws IOException for non-existent file")
        void throwsIOExceptionForNonExistentFile() {
            assertThatThrownBy(() -> ConfigLoader.load("non-existent.properties", Collections.emptyMap()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Configuration file not found");
        }

        @Test
        @DisplayName("reports an absent file as NoSuchFileException, so a caller can tell it from an unreadable one")
        void reportsAbsenceDistinctly() {
            assertThatThrownBy(() -> ConfigLoader.load(
                tempDir.resolve("absent.properties").toString(), Collections.emptyMap()))
                .isInstanceOf(java.nio.file.NoSuchFileException.class);
        }

        @Test
        @DisplayName("does not read a file of that name from the classpath instead")
        void neverFallsBackToTheClasspath() {
            // classpath-only.properties is a test resource and is not on the filesystem. A loader
            // that looked it up on the classpath would start a node on a file the operator never
            // wrote whenever the one they named was missing.
            assertThatThrownBy(() -> ConfigLoader.load("classpath-only.properties",
                Collections.emptyMap()))
                .isInstanceOf(java.nio.file.NoSuchFileException.class);
        }

        @Test
        @DisplayName("a file that is present but unreadable is not reported as absent")
        void unreadableIsNotAbsent() throws IOException {
            // A directory stands in for an unreadable file: it exists, and opening it to read fails.
            Path directory = Files.createDirectory(tempDir.resolve("a-directory.properties"));

            assertThatThrownBy(() -> ConfigLoader.load(directory.toString(), Collections.emptyMap()))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(java.nio.file.NoSuchFileException.class);
        }

        @Test
        @DisplayName("loads config from filesystem")
        void loadsConfigFromFilesystem() throws IOException {
            String content = """
                excalibur.visibility.epoch-tick-ms=200
                excalibur.llm.base-url=https://api.test.com/v1
                """;
            Files.writeString(configFile, content);

            Config config = ConfigLoader.load(configFile.toString(), Collections.emptyMap());

            assertThat(config).isNotNull();
            assertThat(config.visibility().getEpochTickMs()).isEqualTo(200);
            assertThat(config.llm().getBaseUrl()).isEqualTo("https://api.test.com/v1");
        }

        @Test
        @DisplayName("loads config with null env overrides")
        void loadsConfigWithNullEnvOverrides() throws IOException {
            String content = "excalibur.visibility.epoch-tick-ms=150\n";
            Files.writeString(configFile, content);

            Config config = ConfigLoader.load(configFile.toString(), null);

            assertThat(config).isNotNull();
            assertThat(config.visibility().getEpochTickMs()).isEqualTo(150);
        }

        @Test
        @DisplayName("throws IllegalStateException for invalid property format")
        void throwsForInvalidPropertyFormat() throws IOException {
            String content = "invalid-line-without-equals\n";
            Files.writeString(configFile, content);

            assertThatThrownBy(() -> ConfigLoader.load(configFile.toString(), Collections.emptyMap()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid property format");
        }
    }

    @Nested
    @DisplayName("File parsing")
    class FileParsing {

        @Test
        @DisplayName("skips empty lines")
        void skipsEmptyLines() throws IOException {
            String content = """

                excalibur.visibility.epoch-tick-ms=300


                excalibur.llm.base-url=https://api.test.com/v1
                """;
            Files.writeString(configFile, content);

            Config config = ConfigLoader.load(configFile.toString(), Collections.emptyMap());

            assertThat(config.visibility().getEpochTickMs()).isEqualTo(300);
            assertThat(config.llm().getBaseUrl()).isEqualTo("https://api.test.com/v1");
        }

        @Test
        @DisplayName("skips comment lines with #")
        void skipsCommentLinesWithHash() throws IOException {
            String content = """
                # This is a comment
                excalibur.visibility.epoch-tick-ms=400
                # Another comment
                """;
            Files.writeString(configFile, content);

            Config config = ConfigLoader.load(configFile.toString(), Collections.emptyMap());

            assertThat(config.visibility().getEpochTickMs()).isEqualTo(400);
        }

        @Test
        @DisplayName("skips comment lines with ;")
        void skipsCommentLinesWithSemicolon() throws IOException {
            String content = """
                ; This is a comment
                excalibur.visibility.epoch-tick-ms=500
                ; Another comment
                """;
            Files.writeString(configFile, content);

            Config config = ConfigLoader.load(configFile.toString(), Collections.emptyMap());

            assertThat(config.visibility().getEpochTickMs()).isEqualTo(500);
        }

        @Test
        @DisplayName("removes surrounding double quotes from values")
        void removesDoubleQuotes() throws IOException {
            String content = "excalibur.llm.base-url=\"https://api.test.com/v1\"\n";
            Files.writeString(configFile, content);

            Config config = ConfigLoader.load(configFile.toString(), Collections.emptyMap());

            assertThat(config.llm().getBaseUrl()).isEqualTo("https://api.test.com/v1");
        }

        @Test
        @DisplayName("removes surrounding single quotes from values")
        void removesSingleQuotes() throws IOException {
            String content = "excalibur.llm.base-url='https://api.test.com/v1'\n";
            Files.writeString(configFile, content);

            Config config = ConfigLoader.load(configFile.toString(), Collections.emptyMap());

            assertThat(config.llm().getBaseUrl()).isEqualTo("https://api.test.com/v1");
        }

        @Test
        @DisplayName("handles whitespace around key and value")
        void handlesWhitespace() throws IOException {
            String content = "  excalibur.visibility.epoch-tick-ms  =  600  \n";
            Files.writeString(configFile, content);

            Config config = ConfigLoader.load(configFile.toString(), Collections.emptyMap());

            assertThat(config.visibility().getEpochTickMs()).isEqualTo(600);
        }
    }

    @Nested
    @DisplayName("Environment variable overrides")
    class EnvOverrides {

        @Test
        @DisplayName("applies environment variable override")
        void appliesEnvOverride() throws IOException {
            String content = "excalibur.visibility.epoch-tick-ms=100\n";
            Files.writeString(configFile, content);

            Map<String, String> envOverrides = new HashMap<>();
            envOverrides.put("EXCALIBUR_VISIBILITY_EPOCH_TICK_MS", "700");

            Config config = ConfigLoader.load(configFile.toString(), envOverrides);

            assertThat(config.visibility().getEpochTickMs()).isEqualTo(700);
        }

        @Test
        @DisplayName("transforms EXCALIBUR_VISIBILITY_EPOCH_TICK_MS to excalibur.visibility.epoch-tick-ms")
        void transformsEnvKeyCorrectly() throws IOException {
            String content = "excalibur.visibility.epoch-tick-ms=100\n";
            Files.writeString(configFile, content);

            Map<String, String> envOverrides = new HashMap<>();
            envOverrides.put("EXCALIBUR_VISIBILITY_EPOCH_TICK_MS", "800");

            Config config = ConfigLoader.load(configFile.toString(), envOverrides);

            assertThat(config.visibility().getEpochTickMs()).isEqualTo(800);
        }

        @Test
        @DisplayName("transforms EXCALIBUR_LLM_BASE_URL to excalibur.llm.base-url")
        void transformsLlmBaseUrlKey() throws IOException {
            String content = "excalibur.llm.base-url=https://default.com/v1\n";
            Files.writeString(configFile, content);

            Map<String, String> envOverrides = new HashMap<>();
            envOverrides.put("EXCALIBUR_LLM_BASE_URL", "https://override.com/v1");

            Config config = ConfigLoader.load(configFile.toString(), envOverrides);

            assertThat(config.llm().getBaseUrl()).isEqualTo("https://override.com/v1");
        }

        @Test
        @DisplayName("adds new keys not in properties file")
        void addsNewKeysNotInProperties() throws IOException {
            String content = "excalibur.visibility.epoch-tick-ms=100\n";
            Files.writeString(configFile, content);

            Map<String, String> envOverrides = new HashMap<>();
            envOverrides.put("EXCALIBUR_LLM_API_KEY", "secret-key");

            Config config = ConfigLoader.load(configFile.toString(), envOverrides);

            assertThat(config.llm().getApiKey()).isEqualTo("secret-key");
        }

        @Test
        @DisplayName("reaches a key whose name is more than one section deep")
        void reachesADeeperSection() throws IOException {
            // The documented rule is that any setting can be supplied from the environment.
            // Placing a dot only at the first underscore spelled this one
            // excalibur.query.cache-enabled, which nothing reads, so the two cache settings
            // were the only pair in the inventory the environment could not reach.
            Files.writeString(configFile, "excalibur.query.cache.max-entries=64\n");

            Map<String, String> envOverrides = new HashMap<>();
            envOverrides.put("EXCALIBUR_QUERY_CACHE_ENABLED", "true");
            envOverrides.put("EXCALIBUR_QUERY_CACHE_MAX_ENTRIES", "512");

            Config config = ConfigLoader.load(configFile.toString(), envOverrides);

            assertThat(config.query().isCacheEnabled()).isTrue();
            assertThat(config.query().getCacheMaxEntries()).isEqualTo(512);
        }

        @Test
        @DisplayName("a name the inventory does not know keeps the shallow spelling")
        void anUnknownNameKeepsTheShallowSpelling() {
            // So that the unrecognised-key warning names what the operator actually typed,
            // rather than one of the spellings that were tried on the way past it.
            assertThat(com.eonmux.excalibur.config.EnvOverrides.toPropertyKey("EXCALIBUR_LLM_NO_SUCH_KEY"))
                .isEqualTo("excalibur.llm.no-such-key");
        }

        @Test
        @DisplayName("handles key without underscore after prefix")
        void handlesKeyWithoutUnderscoreAfterPrefix() throws IOException {
            String content = "excalibur.llm.timeout-ms=60000\n";
            Files.writeString(configFile, content);

            Map<String, String> envOverrides = new HashMap<>();
            envOverrides.put("EXCALIBUR_TIMEOUT_MS", "30000");

            Config config = ConfigLoader.load(configFile.toString(), envOverrides);

            // The transformed key is "excalibur.timeout-ms" which doesn't match any config property
            // This tests the fallback behavior
            assertThat(config).isNotNull();
        }
    }

    @Nested
    @DisplayName("Config immutability")
    class ConfigImmutability {

        @Test
        @DisplayName("returns immutable env overrides map")
        void returnsImmutableEnvOverrides() throws IOException {
            String content = "excalibur.visibility.epoch-tick-ms=100\n";
            Files.writeString(configFile, content);

            Map<String, String> envOverrides = new HashMap<>();
            envOverrides.put("EXCALIBUR_LLM_API_KEY", "test-key");

            Config config = ConfigLoader.load(configFile.toString(), envOverrides);

            // Modifying the original map should not affect the config
            envOverrides.put("EXCALIBUR_LLM_API_KEY", "modified-key");

            // The config should have the original value
            assertThat(config.llm().getApiKey()).isEqualTo("test-key");
        }
    }
}
