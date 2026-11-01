package com.eonmux.excalibur.ocr.text;

import com.eonmux.excalibur.ocr.json.Json;
import com.eonmux.excalibur.ocr.testing.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Right-to-left text")
class RtlTest {

    private static final List<?> CASES = (List<?>) Json.parse(Fixtures.text("text/rtl.json"));

    @Test
    @DisplayName("flips every sampled line exactly as Python does, in every mode")
    void flipMatchesPython() {
        expectEach("flip", Rtl::flip);
        expectEach("flip_plain", t -> Rtl.flip(t, false));
        expectEach("flip_rtl", t -> Rtl.flip(t, true, true));
        expectEach("flip_ltr", t -> Rtl.flip(t, true, false));
        expectEach("flip_ltr_plain", t -> Rtl.flip(t, false, false));
    }

    @Test
    @DisplayName("finds direction, clusters and normal forms as Python does, by code point")
    void propertiesMatchPython() {
        expectEach("has_rtl", Rtl::hasRtl);
        expectEach("rtl_base", Rtl::rtlBase);
        expectEach("clusters", Rtl::clusters);
        expectEach("normalize", Rtl::normalize);
        expectEach("is_rtl", t -> t.codePoints().mapToObj(Rtl::isRtl).toList());
    }

    @Test
    @DisplayName("is its own inverse on a line with mixed directions")
    void involution() {
        String logical = "שלום (abc 123) עולם";
        String visual = Rtl.flip(logical);

        assertThat(visual).isEqualTo("םלוע (abc 123) םולש");
        assertThat(Rtl.flip(visual)).isEqualTo(logical);
        assertThat(Rtl.flip("plain text")).isEqualTo("plain text");
    }

    @Test
    @DisplayName("keeps marks with their base and drops tatweel and orphan marks")
    void marks() {
        assertThat(Rtl.clusters("َبَ")).containsExactly("َ", "بَ");
        assertThat(Rtl.normalize("َمـرﺳ")).isEqualTo("مرس");
        assertThat(Rtl.flip("ab אְב")).isEqualTo("ab באְ");
    }

    private static void expectEach(String field, Function<String, Object> actual) {
        List<String> mismatches = new ArrayList<>();
        for (Object o : CASES) {
            Map<?, ?> c = (Map<?, ?>) o;
            String text = (String) c.get("text");
            Object got = actual.apply(text);
            if (!got.equals(c.get(field))) {
                mismatches.add(field + "(" + text + "): expected " + c.get(field) + ", got " + got);
            }
        }
        assertThat(CASES).hasSizeGreaterThan(400);
        assertThat(mismatches).isEmpty();
    }
}
