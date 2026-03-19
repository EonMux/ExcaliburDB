package io.excalibur.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Config class.
 */
@DisplayName("Config Tests")
class ConfigTest {

    @Nested
    @DisplayName("ParseBoolean Tests")
    class ParseBooleanTests {

        @Test
        @DisplayName("Should parse true variations")
        void shouldParseTrueVariations() {
            assertThat(Config.parseBoolean("true", false)).isTrue();
            assertThat(Config.parseBoolean("TRUE", false)).isTrue();
            assertThat(Config.parseBoolean("1", false)).isTrue();
            assertThat(Config.parseBoolean("yes", false)).isTrue();
            assertThat(Config.parseBoolean("YES", false)).isTrue();
        }

        @Test
        @DisplayName("Should return default for null")
        void shouldReturnDefaultForNull() {
            assertThat(Config.parseBoolean(null, true)).isTrue();
            assertThat(Config.parseBoolean(null, false)).isFalse();
        }

        @Test
        @DisplayName("Should return false for other values")
        void shouldReturnFalseForOtherValues() {
            assertThat(Config.parseBoolean("false", true)).isFalse();
            assertThat(Config.parseBoolean("0", true)).isFalse();
            assertThat(Config.parseBoolean("no", true)).isFalse();
            assertThat(Config.parseBoolean("random", true)).isFalse();
        }
    }
}
