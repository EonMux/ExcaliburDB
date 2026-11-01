package com.eonmux.excalibur.ocr.text;

import com.eonmux.excalibur.ocr.json.Json;
import com.eonmux.excalibur.ocr.testing.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Unicode properties as Python's unicodedata gives them")
class UnicodeDataTest {

    @Test
    @DisplayName("agree with Python's for every character any recognizer can emit")
    void vocabularyCharactersMatchPython() {
        Map<String, Object> f = Json.parseObject(Fixtures.text("text/unicode_props.json"));
        List<String> mismatches = new ArrayList<>();
        int checked = 0;
        for (Object o : (List<?>) f.get("runs")) {
            List<?> run = (List<?>) o;
            int first = ((Long) run.get(0)).intValue();
            int last = ((Long) run.get(1)).intValue();
            for (int cp = first; cp <= last; cp++) {
                checked++;
                if (!UnicodeData.bidirectional(cp).equals(run.get(2))
                    || !UnicodeData.category(cp).equals(run.get(3))) {
                    mismatches.add(Integer.toHexString(cp) + ": Python " + run.get(2) + "/"
                        + run.get(3) + ", Java " + UnicodeData.bidirectional(cp) + "/"
                        + UnicodeData.category(cp));
                }
            }
        }

        assertThat(checked).isGreaterThan(10_000);
        assertThat(mismatches).isEmpty();
    }

    @Test
    @DisplayName("names every general category with Python's two letters")
    void categories() {
        Map<Integer, String> samples = Map.ofEntries(
            Map.entry((int) 'A', "Lu"), Map.entry((int) 'a', "Ll"), Map.entry(0x01C5, "Lt"),
            Map.entry(0x02B0, "Lm"), Map.entry(0x05D0, "Lo"), Map.entry(0x0301, "Mn"),
            Map.entry(0x20DD, "Me"), Map.entry(0x0903, "Mc"), Map.entry((int) '7', "Nd"),
            Map.entry(0x2160, "Nl"), Map.entry(0x00B2, "No"), Map.entry((int) ' ', "Zs"),
            Map.entry(0x2028, "Zl"), Map.entry(0x2029, "Zp"), Map.entry(0x0007, "Cc"),
            Map.entry(0x200E, "Cf"), Map.entry(0xE000, "Co"), Map.entry(0xD800, "Cs"),
            Map.entry((int) '-', "Pd"), Map.entry((int) '(', "Ps"), Map.entry((int) ')', "Pe"),
            Map.entry((int) '_', "Pc"), Map.entry((int) '!', "Po"), Map.entry((int) '+', "Sm"),
            Map.entry((int) '$', "Sc"), Map.entry((int) '^', "Sk"), Map.entry(0x00A9, "So"),
            Map.entry(0x00AB, "Pi"), Map.entry(0x00BB, "Pf"), Map.entry(0x0378, "Cn"));

        samples.forEach((cp, cat) -> assertThat(UnicodeData.category(cp)).as("U+%04X", cp)
            .isEqualTo(cat));
    }

    @Test
    @DisplayName("names bidi classes as Python does, empty for an unassigned code point")
    void bidiClasses() {
        assertThat(UnicodeData.bidirectional('a')).isEqualTo("L");
        assertThat(UnicodeData.bidirectional(0x05D0)).isEqualTo("R");
        assertThat(UnicodeData.bidirectional(0x0627)).isEqualTo("AL");
        assertThat(UnicodeData.bidirectional(0x0661)).isEqualTo("AN");
        assertThat(UnicodeData.bidirectional(0x064B)).isEqualTo("NSM");
        assertThat(UnicodeData.bidirectional(0x2067)).isEqualTo("RLI");
        assertThat(UnicodeData.bidirectional(0x1E900)).isEqualTo("R");
        assertThat(UnicodeData.bidirectional(0x0378)).isEmpty();
    }

    @Test
    @DisplayName("reads letters as str.isalpha does, by code point")
    void isAlpha() {
        assertThat(UnicodeData.isAlpha("alpha")).isTrue();
        assertThat(UnicodeData.isAlpha("𝐀b")).isTrue();
        assertThat(UnicodeData.isAlpha("")).isFalse();
        assertThat(UnicodeData.isAlpha("a1")).isFalse();
        assertThat(UnicodeData.isNonSpacingMark(0x20DD)).isTrue();
        assertThat(UnicodeData.isNonSpacingMark(0x0903)).isFalse();
    }
}
