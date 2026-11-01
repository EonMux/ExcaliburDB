package com.eonmux.excalibur.ocr.json;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

@DisplayName("JSON text is read as Python's json.loads reads it")
class JsonTest {

    @Test
    @DisplayName("objects in document order, a repeated key keeping its last value")
    void objects() {
        Map<String, Object> m = Json.parseObject(
            " {\"b\": 1, \"a\": [true, false, null], \"b\": {\"c\": \"d\"}, \"e\": {}} ");

        assertThat(m.keySet()).containsExactly("b", "a", "e");
        assertThat(m).contains(entry("b", Map.of("c", "d")), entry("e", Map.of()));
        assertThat(m.get("a")).isEqualTo(Arrays.asList(true, false, null));
        assertThatThrownBy(() -> m.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("integers as longs, everything else numeric as doubles")
    void numbers() {
        assertThat(Json.parse("[0, -12, 3.5, -1e3, 2E-2, 99999999999999999999]"))
            .isEqualTo(Arrays.asList(0L, -12L, 3.5, -1000.0, 0.02, 1e20));
        assertThat(Json.parse("[]")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("every escape, an escaped surrogate pair reading as one character")
    void strings() {
        assertThat(Json.parse("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00e9\\ud83d\\ude00x\""))
            .isEqualTo("\"\\/\b\f\n\r\té\uD83D\uDE00x");
        assertThat(Json.parse("\"Ш \u05d0\"")).isEqualTo("Ш \u05d0");
    }

    @Test
    @DisplayName("refusing anything outside the grammar, with its position")
    void refusals() {
        for (String bad : List.of("", "[1,]", "{\"a\" 1}", "{a: 1}", "'x'", "NaN", "01", "1.",
            "1e", "-", "\"\\x\"", "\"\\u12\"", "\"\\u12zz\"", "\"open", "\"a\u0001\"", "tru",
            "[1] 2", "{\"a\": 1", "[1 2]", "\"\\")) {
            assertThatThrownBy(() -> Json.parse(bad))
                .as(bad)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid JSON at offset");
        }
        assertThatThrownBy(() -> Json.parseObject("[1]"))
            .hasMessageContaining("expected a JSON object, got array");
    }

    @Test
    @DisplayName("names the type of every value it reads")
    void describe() {
        assertThat(Json.describe(null)).isEqualTo("null");
        assertThat(Json.describe(Map.of())).isEqualTo("object");
        assertThat(Json.describe(List.of())).isEqualTo("array");
        assertThat(Json.describe("s")).isEqualTo("string");
        assertThat(Json.describe(1L)).isEqualTo("number");
        assertThat(Json.describe(true)).isEqualTo("boolean");
        assertThat(Json.describe(new Object())).isEqualTo("Object");
    }
}
