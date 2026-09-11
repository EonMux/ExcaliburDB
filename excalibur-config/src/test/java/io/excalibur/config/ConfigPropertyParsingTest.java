package io.excalibur.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for how Config resolves raw property values.
 *
 * <p>Tests verify that an absent or blank setting falls back to its default, that an
 * unparseable setting is reported rather than silently replaced by that default, and
 * that environment overrides are applied on every construction path.</p>
 */
@DisplayName("Config property resolution")
class ConfigPropertyParsingTest {

    @Nested
    @DisplayName("Numeric values")
    class NumericValues {

        @Test
        @DisplayName("rejects an unparseable int and names the key")
        void rejectsUnparseableInt() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.query.top-k-default", "one-hundred");

            assertThatThrownBy(() -> new Config(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excalibur.query.top-k-default");
        }

        @Test
        @DisplayName("rejects an unparseable long and names the key")
        void rejectsUnparseableLong() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.visibility.epoch-tick-ms", "100ms");

            assertThatThrownBy(() -> new Config(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excalibur.visibility.epoch-tick-ms");
        }

        @Test
        @DisplayName("rejects a value carrying a trailing inline comment")
        void rejectsInlineComment() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.health.readiness-threshold", "0.8 # 80% coverage");

            assertThatThrownBy(() -> new Config(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excalibur.health.readiness-threshold");
        }

        @Test
        @DisplayName("rejects NaN")
        void rejectsNaN() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.llm.relevancy-threshold", "NaN");

            assertThatThrownBy(() -> new Config(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excalibur.llm.relevancy-threshold");
        }

        @Test
        @DisplayName("rejects Infinity")
        void rejectsInfinity() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.query.threshold", "Infinity");

            assertThatThrownBy(() -> new Config(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excalibur.query.threshold");
        }

        @Test
        @DisplayName("falls back to the default for a blank value")
        void blankValueFallsBackToDefault() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.query.top-k-default", "");
            props.put("excalibur.visibility.epoch-tick-ms", "   ");

            Config config = new Config(props);

            assertThat(config.query().getDefaultTopK()).isEqualTo(Config.Query.DEFAULT_TOP_K);
            assertThat(config.visibility().getEpochTickMs())
                .isEqualTo(Config.Visibility.DEFAULT_EPOCH_TICK_MS);
        }
    }

    @Nested
    @DisplayName("Boolean values")
    class BooleanValues {

        @Test
        @DisplayName("recognises the true spellings")
        void recognisesTrueSpellings() {
            for (String spelling : new String[] {"true", "TRUE", "1", "yes", "on"}) {
                Map<String, String> props = new HashMap<>();
                props.put("excalibur.ingest.document-atomicity", spelling);

                assertThat(new Config(props).ingest().isDocumentAtomicityEnabled())
                    .as("spelling: %s", spelling)
                    .isTrue();
            }
        }

        @Test
        @DisplayName("recognises the false spellings")
        void recognisesFalseSpellings() {
            for (String spelling : new String[] {"false", "FALSE", "0", "no", "off"}) {
                Map<String, String> props = new HashMap<>();
                props.put("excalibur.ingest.document-atomicity", spelling);

                assertThat(new Config(props).ingest().isDocumentAtomicityEnabled())
                    .as("spelling: %s", spelling)
                    .isFalse();
            }
        }

        @Test
        @DisplayName("keeps a true default when the value is blank")
        void blankBooleanKeepsDefault() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.ingest.document-atomicity", "");
            props.put("excalibur.query.include-partial-segments", "  ");

            Config config = new Config(props);

            assertThat(config.ingest().isDocumentAtomicityEnabled()).isTrue();
            assertThat(config.query().isIncludePartialSegments()).isTrue();
        }

        @Test
        @DisplayName("fuses BM25 with vector search unless an operator turns it off")
        void hybridRetrievalDefaultsOn() {
            assertThat(new Config(new HashMap<>()).query().isHybridRetrieval())
                .as("a node holding both signals should use both")
                .isTrue();

            Map<String, String> props = new HashMap<>();
            props.put("excalibur.query.hybrid-retrieval", "false");

            assertThat(new Config(props).query().isHybridRetrieval())
                .as("the escape hatch back to vector-only ranking")
                .isFalse();
        }

        @Test
        @DisplayName("rejects an unrecognised spelling instead of inverting the default")
        void rejectsUnrecognisedBoolean() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.query.include-partial-segments", "enabled");

            assertThatThrownBy(() -> new Config(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excalibur.query.include-partial-segments");
        }
    }

    @Nested
    @DisplayName("Environment overrides")
    class EnvironmentOverrides {

        @Test
        @DisplayName("are applied when the properties map is empty")
        void appliedWithEmptyProperties() {
            Map<String, String> env = new HashMap<>();
            env.put("EXCALIBUR_LLM_API_KEY", "secret-key");
            env.put("EXCALIBUR_QUERY_VECTOR_DIMENSIONS", "768");

            Config config = new Config("excalibur.properties", env, new HashMap<>());

            assertThat(config.llm().getApiKey()).isEqualTo("secret-key");
            assertThat(config.query().getVectorDimensions()).isEqualTo(768);
        }

        @Test
        @DisplayName("take precedence over the properties map")
        void takePrecedenceOverProperties() {
            Map<String, String> props = new HashMap<>();
            props.put("excalibur.llm.base-url", "https://api.openai.com/v1");

            Map<String, String> env = new HashMap<>();
            env.put("EXCALIBUR_LLM_BASE_URL", "http://ollama:11434/v1");

            Config config = new Config("excalibur.properties", env, props);

            assertThat(config.llm().getBaseUrl()).isEqualTo("http://ollama:11434/v1");
        }

        @Test
        @DisplayName("ignore variables without the EXCALIBUR_ prefix")
        void ignoreUnprefixedVariables() {
            Map<String, String> env = new HashMap<>();
            env.put("PATH", "/usr/bin");
            env.put("HOME", "/root");

            Config config = new Config("excalibur.properties", env, new HashMap<>());

            assertThat(config.llm().getBaseUrl()).isEqualTo(Config.Llm.DEFAULT_BASE_URL);
        }

        @Test
        @DisplayName("do not mutate the caller's properties map")
        void doNotMutateCallerProperties() {
            Map<String, String> props = new HashMap<>();
            Map<String, String> env = new HashMap<>();
            env.put("EXCALIBUR_LLM_API_KEY", "secret-key");

            new Config("excalibur.properties", env, props);

            assertThat(props).isEmpty();
        }
    }
}
