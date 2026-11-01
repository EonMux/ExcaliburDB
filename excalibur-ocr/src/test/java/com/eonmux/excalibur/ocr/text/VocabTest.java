package com.eonmux.excalibur.ocr.text;

import com.eonmux.excalibur.ocr.json.Json;
import com.eonmux.excalibur.ocr.testing.Fixtures;
import com.eonmux.excalibur.ocr.testing.Models;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("A recognizer vocabulary")
class VocabTest {

    private static final Map<String, Object> DECODE =
        Json.parseObject(Fixtures.text("text/vocab_decode.json"));

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vocab", "vocab_world", "vocab_rtl", "vocab_cjk"})
    @DisplayName("read from meta.json decodes every sampled id sequence exactly as Python does")
    void decodeMatchesPython(String name) throws IOException {
        Map<String, Object> meta = Json.parseObject(
            Files.readString(Models.dir().resolve("meta.json")));
        Vocab vocab = Vocab.fromJson((String) meta.get(name));
        Map<?, ?> expected = (Map<?, ?>) DECODE.get(name);

        assertThat((long) vocab.nClasses()).isEqualTo(expected.get("n_classes"));
        assertThat(vocab.hasCommands()).isEqualTo(expected.get("has_commands"));
        assertThat(vocab.jamo()).isEqualTo(expected.get("jamo"));
        ((Map<?, ?>) expected.get("index")).forEach((token, id) ->
            assertThat(vocab.indexOf((String) token)).hasValue(((Long) id).intValue()));
        List<String> mismatches = new ArrayList<>();
        for (Object o : (List<?>) expected.get("cases")) {
            Map<?, ?> c = (Map<?, ?>) o;
            int[] ids = ((List<?>) c.get("ids")).stream().mapToInt(i -> ((Long) i).intValue())
                .toArray();
            String text = vocab.decode(ids);
            if (!text.equals(c.get("text"))) {
                mismatches.add(Arrays.toString(ids) + ": expected " + c.get("text") + ", got "
                    + text);
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    @DisplayName("spaces a letter command from a letter after it, and from nothing else")
    void commandSpacing() {
        Vocab vocab = Vocab.of(List.of("\\alpha", "x", "1", "\\{", "\\ab", "é", "\uD835\uDC00"));

        assertThat(vocab.decode(new int[] {1, 2})).isEqualTo("\\alpha x");
        assertThat(vocab.decode(new int[] {1, 3})).isEqualTo("\\alpha1");
        assertThat(vocab.decode(new int[] {1, 1})).isEqualTo("\\alpha\\alpha");
        assertThat(vocab.decode(new int[] {4, 2})).isEqualTo("\\{x");
        assertThat(vocab.decode(new int[] {5, 6})).isEqualTo("\\ab é");
        assertThat(vocab.decode(new int[] {1, 7})).isEqualTo("\\alpha \uD835\uDC00");
        assertThat(vocab.decode(new int[] {1})).isEqualTo("\\alpha");
    }

    @Test
    @DisplayName("skips the blank and ids out of range, and composes jamo into syllables")
    void skipsAndComposes() {
        Vocab plain = Vocab.of(List.of("a", "b"));
        Vocab jamo = Vocab.of(List.of("\u1100", "\u1161", "\u11A8"));

        assertThat(plain.decode(new int[] {0, 1, -1, 3, 2})).isEqualTo("ab");
        assertThat(plain.hasCommands()).isFalse();
        assertThat(jamo.jamo()).isTrue();
        assertThat(jamo.decode(new int[] {1, 2, 3})).isEqualTo("\uAC01");
    }

    @Test
    @DisplayName("counts a supplementary character as one character, not a command")
    void supplementaryIsOneToken() {
        Vocab vocab = Vocab.of(List.of("\uD835\uDC00"));

        assertThat(vocab.hasCommands()).isFalse();
        assertThat(vocab.nClasses()).isEqualTo(2);
    }

    @Test
    @DisplayName("maps a repeated token to its later class, as Python's dict does")
    void repeatedToken() {
        Vocab vocab = Vocab.of(List.of("a", "b", "a"));

        assertThat(vocab.index()).containsEntry("a", 3).containsEntry("b", 2);
        assertThat(vocab.indexOf("z")).isEmpty();
        assertThat(vocab.decode(new int[] {1, 3})).isEqualTo("aa");
    }

    @Test
    @DisplayName("is read from JSON, refusing what is not a blank-0 token list")
    void fromJson() {
        Vocab vocab = Vocab.fromJson("{\"blank\": 0, \"chars\": [\" \", \"\\u00e9\"]}");

        assertThat(vocab.chars()).containsExactly(" ", "é");
        assertThat(Vocab.fromJson("{\"chars\": [\"a\"]}").chars()).containsExactly("a");
        assertThat(Vocab.fromJson("{\"blank\": 0.0, \"chars\": []}").nClasses()).isEqualTo(1);
        assertThatThrownBy(() -> Vocab.fromJson("{\"blank\": 1, \"chars\": []}"))
            .hasMessageContaining("blank=0");
        assertThatThrownBy(() -> Vocab.fromJson("{\"blank\": null, \"chars\": []}"))
            .hasMessageContaining("blank=0");
        assertThatThrownBy(() -> Vocab.fromJson("{\"blank\": 0}"))
            .hasMessageContaining("\"chars\" array, got null");
        assertThatThrownBy(() -> Vocab.fromJson("{\"chars\": [\"a\", 2]}"))
            .hasMessageContaining("got number at 1");
        assertThatThrownBy(() -> Vocab.of(Arrays.asList("a", null)))
            .hasMessageContaining("token 1 is null");
    }

    @Test
    @DisplayName("is an immutable value")
    void value() {
        Vocab a = Vocab.of(new ArrayList<>(List.of("\\alpha", "\u1100")));

        assertThat(a).isEqualTo(Vocab.of(List.of("\\alpha", "\u1100")))
            .hasSameHashCodeAs(Vocab.of(List.of("\\alpha", "\u1100")))
            .isNotEqualTo(Vocab.of(List.of("x")));
        assertThat(a.toString()).contains("2 tokens", "commands", "jamo");
        assertThatThrownBy(() -> a.chars().add("y"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(a.normalize("Ș....")).isEqualTo("Ş...");
    }
}
