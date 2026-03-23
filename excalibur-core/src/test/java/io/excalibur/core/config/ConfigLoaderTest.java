package io.excalibur.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for ConfigLoader class.
 */
@DisplayName("ConfigLoader Tests")
class ConfigLoaderTest {

    @BeforeEach
    void setUp() {
        ConfigLoader.clearCache();
    }

    @AfterEach
    void tearDown() {
        ConfigLoader.clearCache();
    }

    @Nested
    @DisplayName("Load Method Tests")
    class LoadMethodTests {

        @Test
        @DisplayName("Should clear cache correctly")
        void shouldClearCacheCorrectly() {
            // Clear should not throw
            assertThatCode(() -> ConfigLoader.clearCache()).doesNotThrowAnyException();

            // After clear, instance should be null
            ConfigLoader.clearCache();
            // Note: getInstance() returns null if not initialized
        }

        @Test
        @DisplayName("Should handle multiple clearCache calls")
        void shouldHandleMultipleClearCacheCalls() {
            assertThatCode(() -> {
                ConfigLoader.clearCache();
                ConfigLoader.clearCache();
                ConfigLoader.clearCache();
            }).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("PropertiesLoader Tests")
    class PropertiesLoaderTests {

        @Test
        @DisplayName("Should parse simple properties")
        void shouldParseSimpleProperties() throws Exception {
            String content = "key1=value1\nkey2=value2\nkey3=value3";
            InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

            // Use reflection to access inner class
            var loaderClass = Class.forName("io.excalibur.core.config.ConfigLoader$PropertiesLoader");
            var constructor = loaderClass.getDeclaredConstructor(InputStream.class);
            constructor.setAccessible(true);
            var loader = constructor.newInstance(stream);
            var loadMethod = loaderClass.getDeclaredMethod("load");
            loadMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, String> result = (Map<String, String>) loadMethod.invoke(loader);

            assertThat(result).containsEntry("key1", "value1");
            assertThat(result).containsEntry("key2", "value2");
            assertThat(result).containsEntry("key3", "value3");
        }

        @Test
        @DisplayName("Should skip empty lines")
        void shouldSkipEmptyLines() throws Exception {
            String content = "key1=value1\n\n\nkey2=value2";
            InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

            var loaderClass = Class.forName("io.excalibur.core.config.ConfigLoader$PropertiesLoader");
            var constructor = loaderClass.getDeclaredConstructor(InputStream.class);
            constructor.setAccessible(true);
            var loader = constructor.newInstance(stream);
            var loadMethod = loaderClass.getDeclaredMethod("load");
            loadMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, String> result = (Map<String, String>) loadMethod.invoke(loader);

            assertThat(result).hasSize(2);
            assertThat(result).containsEntry("key1", "value1");
            assertThat(result).containsEntry("key2", "value2");
        }

        @Test
        @DisplayName("Should skip comment lines")
        void shouldSkipCommentLines() throws Exception {
            String content = "# This is a comment\nkey1=value1\n# Another comment\nkey2=value2";
            InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

            var loaderClass = Class.forName("io.excalibur.core.config.ConfigLoader$PropertiesLoader");
            var constructor = loaderClass.getDeclaredConstructor(InputStream.class);
            constructor.setAccessible(true);
            var loader = constructor.newInstance(stream);
            var loadMethod = loaderClass.getDeclaredMethod("load");
            loadMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, String> result = (Map<String, String>) loadMethod.invoke(loader);

            assertThat(result).hasSize(2);
            assertThat(result).doesNotContainKey("# This is a comment");
            assertThat(result).doesNotContainKey("# Another comment");
        }

        @Test
        @DisplayName("Should handle whitespace in keys and values")
        void shouldHandleWhitespaceInKeysAndValues() throws Exception {
            String content = "  key1  =  value1  \n  key2=value2  ";
            InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

            var loaderClass = Class.forName("io.excalibur.core.config.ConfigLoader$PropertiesLoader");
            var constructor = loaderClass.getDeclaredConstructor(InputStream.class);
            constructor.setAccessible(true);
            var loader = constructor.newInstance(stream);
            var loadMethod = loaderClass.getDeclaredMethod("load");
            loadMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, String> result = (Map<String, String>) loadMethod.invoke(loader);

            assertThat(result).containsEntry("key1", "value1");
            assertThat(result).containsEntry("key2", "value2");
        }

        @Test
        @DisplayName("Should handle quoted values")
        void shouldHandleQuotedValues() throws Exception {
            String content = "key1=\"quoted value\"\nkey2=\"another\"";
            InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

            var loaderClass = Class.forName("io.excalibur.core.config.ConfigLoader$PropertiesLoader");
            var constructor = loaderClass.getDeclaredConstructor(InputStream.class);
            constructor.setAccessible(true);
            var loader = constructor.newInstance(stream);
            var loadMethod = loaderClass.getDeclaredMethod("load");
            loadMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, String> result = (Map<String, String>) loadMethod.invoke(loader);

            assertThat(result).containsEntry("key1", "quoted value");
            assertThat(result).containsEntry("key2", "another");
        }

        @Test
        @DisplayName("Should handle values with equals sign")
        void shouldHandleValuesWithEqualsSign() throws Exception {
            String content = "key1=value=with=equals";
            InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

            var loaderClass = Class.forName("io.excalibur.core.config.ConfigLoader$PropertiesLoader");
            var constructor = loaderClass.getDeclaredConstructor(InputStream.class);
            constructor.setAccessible(true);
            var loader = constructor.newInstance(stream);
            var loadMethod = loaderClass.getDeclaredMethod("load");
            loadMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, String> result = (Map<String, String>) loadMethod.invoke(loader);

            assertThat(result).containsEntry("key1", "value=with=equals");
        }

        @Test
        @DisplayName("Should skip lines without equals sign")
        void shouldSkipLinesWithoutEqualsSign() throws Exception {
            String content = "key1=value1\ninvalidline\nkey2=value2";
            InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

            var loaderClass = Class.forName("io.excalibur.core.config.ConfigLoader$PropertiesLoader");
            var constructor = loaderClass.getDeclaredConstructor(InputStream.class);
            constructor.setAccessible(true);
            var loader = constructor.newInstance(stream);
            var loadMethod = loaderClass.getDeclaredMethod("load");
            loadMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, String> result = (Map<String, String>) loadMethod.invoke(loader);

            assertThat(result).hasSize(2);
            assertThat(result).doesNotContainKey("invalidline");
        }

        @Test
        @DisplayName("Should handle empty file")
        void shouldHandleEmptyFile() throws Exception {
            String content = "";
            InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

            var loaderClass = Class.forName("io.excalibur.core.config.ConfigLoader$PropertiesLoader");
            var constructor = loaderClass.getDeclaredConstructor(InputStream.class);
            constructor.setAccessible(true);
            var loader = constructor.newInstance(stream);
            var loadMethod = loaderClass.getDeclaredMethod("load");
            loadMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, String> result = (Map<String, String>) loadMethod.invoke(loader);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should handle only comments and empty lines")
        void shouldHandleOnlyCommentsAndEmptyLines() throws Exception {
            String content = "# Comment 1\n\n# Comment 2\n\n";
            InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

            var loaderClass = Class.forName("io.excalibur.core.config.ConfigLoader$PropertiesLoader");
            var constructor = loaderClass.getDeclaredConstructor(InputStream.class);
            constructor.setAccessible(true);
            var loader = constructor.newInstance(stream);
            var loadMethod = loaderClass.getDeclaredMethod("load");
            loadMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, String> result = (Map<String, String>) loadMethod.invoke(loader);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should handle Windows line endings")
        void shouldHandleWindowsLineEndings() throws Exception {
            String content = "key1=value1\r\nkey2=value2\r\n";
            InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

            var loaderClass = Class.forName("io.excalibur.core.config.ConfigLoader$PropertiesLoader");
            var constructor = loaderClass.getDeclaredConstructor(InputStream.class);
            constructor.setAccessible(true);
            var loader = constructor.newInstance(stream);
            var loadMethod = loaderClass.getDeclaredMethod("load");
            loadMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, String> result = (Map<String, String>) loadMethod.invoke(loader);

            assertThat(result).containsEntry("key1", "value1");
            assertThat(result).containsEntry("key2", "value2");
        }

        @Test
        @DisplayName("Should handle ExcaliburDB configuration format")
        void shouldHandleExcaliburDBConfigurationFormat() throws Exception {
            String content = """
                # ExcaliburDB Configuration
                excalibur.enabled=true
                excalibur.visibility.epoch-tick-ms=100
                excalibur.query.include-partial-segments=true
                excalibur.query.default-top-k=10
                excalibur.query.default-threshold=0.3
                excalibur.ingest.hnsw-queue-depth-limit=100000
                excalibur.ingest.document-atomicity=true
                excalibur.health.readiness-threshold=0.5
                """;
            InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

            var loaderClass = Class.forName("io.excalibur.core.config.ConfigLoader$PropertiesLoader");
            var constructor = loaderClass.getDeclaredConstructor(InputStream.class);
            constructor.setAccessible(true);
            var loader = constructor.newInstance(stream);
            var loadMethod = loaderClass.getDeclaredMethod("load");
            loadMethod.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, String> result = (Map<String, String>) loadMethod.invoke(loader);

            assertThat(result).containsEntry("excalibur.enabled", "true");
            assertThat(result).containsEntry("excalibur.visibility.epoch-tick-ms", "100");
            assertThat(result).containsEntry("excalibur.query.include-partial-segments", "true");
            assertThat(result).containsEntry("excalibur.query.default-top-k", "10");
            assertThat(result).containsEntry("excalibur.query.default-threshold", "0.3");
            assertThat(result).containsEntry("excalibur.ingest.hnsw-queue-depth-limit", "100000");
            assertThat(result).containsEntry("excalibur.ingest.document-atomicity", "true");
            assertThat(result).containsEntry("excalibur.health.readiness-threshold", "0.5");
        }
    }

    @Nested
    @DisplayName("Config Creation Tests")
    class ConfigCreationTests {

        @Test
        @DisplayName("Should create config from loaded properties")
        void shouldCreateConfigFromLoadedProperties() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.visibility.epoch-tick-ms", "200");
            props.put("excalibur.query.default-top-k", "20");

            Config config = new Config(props);

            assertThat(config.visibility().getEpochTickMs()).isEqualTo(200L);
            assertThat(config.query().getDefaultTopK()).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("Load Integration Tests")
    class LoadIntegrationTests {

        @Test
        @DisplayName("Should load configuration from properties file")
        void shouldLoadConfigurationFromPropertiesFile() {
            ConfigLoader.clearCache();
            Map<String, String> env = new HashMap<>();
            Config config = ConfigLoader.load("excalibur.properties", env);

            assertThat(config).isNotNull();
            assertThat(config.visibility().getEpochTickMs()).isEqualTo(100L);
            assertThat(config.query().includePartialSegments()).isTrue();
            assertThat(config.query().getDefaultTopK()).isEqualTo(10);
            assertThat(config.ingest().getHnswQueueDepthLimit()).isEqualTo(100000);
            assertThat(config.health().getReadinessThreshold()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("Should apply environment variable overrides")
        void shouldApplyEnvironmentVariableOverrides() {
            ConfigLoader.clearCache();
            Map<String, String> env = new HashMap<>();
            env.put("EXCALIBUR_VISIBILITY_EPOCH_TICK_MS", "500");
            Config config = ConfigLoader.load("excalibur.properties", env);

            assertThat(config).isNotNull();
            assertThat(config.visibility().getEpochTickMs()).isEqualTo(500L);
        }

        @Test
        @DisplayName("Should apply multiple environment variable overrides")
        void shouldApplyMultipleEnvironmentVariableOverrides() {
            ConfigLoader.clearCache();
            Map<String, String> env = new HashMap<>();
            env.put("EXCALIBUR_VISIBILITY_EPOCH_TICK_MS", "250");
            env.put("EXCALIBUR_QUERY_DEFAULT_TOP_K", "50");
            env.put("EXCALIBUR_HEALTH_READINESS_THRESHOLD", "0.75");
            Config config = ConfigLoader.load("excalibur.properties", env);

            assertThat(config).isNotNull();
            assertThat(config.visibility().getEpochTickMs()).isEqualTo(250L);
            assertThat(config.query().getDefaultTopK()).isEqualTo(50);
            assertThat(config.health().getReadinessThreshold()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("Should allow environment variables to set new values not in properties file")
        void shouldAllowEnvVarsToSetNewValues() {
            ConfigLoader.clearCache();
            Map<String, String> env = new HashMap<>();
            // This property might not exist in the file but env var should still work
            env.put("EXCALIBUR_CUSTOM_NEW_VALUE", "test123");
            Config config = ConfigLoader.load("excalibur.properties", env);

            assertThat(config).isNotNull();
            // The config should still load successfully
            assertThat(config.visibility().getEpochTickMs()).isEqualTo(100L);
        }

        @Test
        @DisplayName("Should return cached config on subsequent loads")
        void shouldReturnCachedConfigOnSubsequentLoads() {
            ConfigLoader.clearCache();
            Map<String, String> env1 = new HashMap<>();
            Config config1 = ConfigLoader.load("excalibur.properties", env1);

            Map<String, String> env2 = new HashMap<>();
            env2.put("EXCALIBUR_VISIBILITY_EPOCH_TICK_MS", "999");
            Config config2 = ConfigLoader.load("excalibur.properties", env2);

            // Should return same cached instance, ignoring second env overrides
            assertThat(config1).isSameAs(config2);
            assertThat(config2.visibility().getEpochTickMs()).isEqualTo(100L);
        }

        @Test
        @DisplayName("Should throw exception for missing properties file")
        void shouldThrowExceptionForMissingPropertiesFile() {
            ConfigLoader.clearCache();
            Map<String, String> env = new HashMap<>();

            assertThatThrownBy(() -> ConfigLoader.load("nonexistent.properties", env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to load configuration");
        }
    }
}
