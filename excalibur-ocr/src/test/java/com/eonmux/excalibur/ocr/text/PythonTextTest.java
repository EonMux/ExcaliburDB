package com.eonmux.excalibur.ocr.text;

import com.eonmux.excalibur.ocr.layout.FixtureJson;
import com.eonmux.excalibur.ocr.testing.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Python's string predicates are answered as Python 3.14 answers them")
class PythonTextTest {

    private static final Map<String, IntPredicate> PREDICATES = Map.of(
        "space", PythonText::isSpace, "alpha", PythonText::isAlpha,
        "lower", PythonText::isLower, "upper", PythonText::isUpper,
        "digit", PythonText::isDigit, "decimal", PythonText::isDecimal,
        "wide", PythonText::isWide);

    @Test
    @DisplayName("code points Java reads differently, Unicode 16 additions among them, read as Python's")
    void predicates() {
        Map<?, ?> doc = (Map<?, ?>) FixtureJson.parse(Fixtures.text("layout/synthetic.json"));
        List<?> samples = (List<?>) doc.get("unicode");
        for (Object o : samples) {
            Map<?, ?> s = (Map<?, ?>) o;
            int cp = ((Number) s.get("cp")).intValue();
            PREDICATES.forEach((name, p) -> assertThat(p.test(cp))
                .as("%s(U+%04X)", name, cp).isEqualTo(s.get(name)));
        }
    }

    @Test
    @DisplayName("strip removes Python's whitespace, no-break spaces and separators included")
    void strip() {
        assertThat(PythonText.strip(" \u001c a b \t　")).isEqualTo("a b");
        assertThat(PythonText.strip("​x​")).as("a zero-width space is not whitespace")
            .isEqualTo("​x​");
        assertThat(PythonText.strip(" \n ")).isEmpty();
        assertThat(PythonText.strip("😀")).isEqualTo("😀");
    }

    @Test
    @DisplayName("strings are measured and tested by code point")
    void strings() {
        assertThat(PythonText.length("a😀b")).isEqualTo(3);
        assertThat(PythonText.isDigit("12²")).isTrue();
        assertThat(PythonText.isDigit("𝟎")).as("MATHEMATICAL BOLD DIGIT ZERO").isTrue();
        assertThat(PythonText.isDigit("1a")).isFalse();
        assertThat(PythonText.isDigit("")).isFalse();
        assertThatThrownBy(() -> PythonText.strip(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PythonText.length(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> PythonText.isDigit((String) null))
            .isInstanceOf(NullPointerException.class);
    }
}
