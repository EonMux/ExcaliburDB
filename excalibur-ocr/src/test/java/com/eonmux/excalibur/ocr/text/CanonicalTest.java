package com.eonmux.excalibur.ocr.text;

import com.eonmux.excalibur.ocr.json.Json;
import com.eonmux.excalibur.ocr.testing.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Label canonicalization")
class CanonicalTest {

    @Test
    @DisplayName("gives Python's canonical form for every sampled text")
    void matchesPython() {
        List<?> cases = (List<?>) Json.parse(Fixtures.text("text/canonical.json"));

        assertThat(cases).hasSizeGreaterThan(80);
        for (Object o : cases) {
            Map<?, ?> c = (Map<?, ?>) o;
            assertThat(Canonical.canonical((String) c.get("in"))).as("%s", c.get("in"))
                .isEqualTo(c.get("out"));
        }
    }

    @Test
    @DisplayName("normalizes, maps look-alikes, joins doubled quotes and shortens runs")
    void rules() {
        assertThat(Canonical.canonical("é")).isEqualTo("é");
        assertThat(Canonical.canonical("ț ●")).isEqualTo("ţ •");
        assertThat(Canonical.canonical("''a``'''")).isEqualTo("\"a\"\"'");
        assertThat(Canonical.canonical("a......b___c--d")).isEqualTo("a...b___c--d");
    }
}
