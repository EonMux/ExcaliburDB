package com.eonmux.excalibur.ocr.lm;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.testing.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("An n-gram language model")
class NgramLMTest {

    private static final Map<String, NdArray> LOGP = Fixtures.npz("text/logp.npz");

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"base", "world", "cjk"})
    @DisplayName("gives Python's exact log-probability for every sampled history and token")
    void logpMatchesPython(String name) {
        NgramLM lm = LanguageModels.get(name);
        int[] shape = LOGP.get(name + "_hist").shape();
        long[] hist = LOGP.get(name + "_hist").longs();
        long[] lengths = LOGP.get(name + "_hlen").longs();
        long[] tokens = LOGP.get(name + "_t").longs();
        double[] expected = LOGP.get(name + "_logp").doubles();

        double[] actual = new double[expected.length];
        for (int i = 0; i < expected.length; i++) {
            int[] h = new int[(int) lengths[i]];
            for (int j = 0; j < h.length; j++) {
                h[j] = (int) hist[i * shape[1] + j];
            }
            actual[i] = lm.logp(h, (int) tokens[i]);
        }

        assertThat(actual).containsExactly(expected);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"base", "world", "cjk"})
    @DisplayName("gives Python's exact log-probability for every packed key, and Python's bound")
    void logpKeysMatchesPython(String name) {
        NgramLM lm = LanguageModels.get(name);

        double[] actual = lm.logpKeys(LOGP.get(name + "_keys").longs());

        assertThat(actual).containsExactly(LOGP.get(name + "_logp_keys").doubles());
        assertThat(lm.maxLogp()).isEqualTo(LOGP.get(name + "_max_logp").doubles()[0]);
        assertThat(Arrays.stream(actual).max().orElseThrow()).isLessThanOrEqualTo(lm.maxLogp());
    }

    @Test
    @DisplayName("reads its order, vocabulary and key width from the file, 11 bits by default")
    void shape() {
        NgramLM base = LanguageModels.get("base");
        NgramLM world = LanguageModels.get("world");

        assertThat(base.order()).isEqualTo(5);
        assertThat(base.bits()).isEqualTo(NgramLM.BITS);
        assertThat(base.vocabSize()).isEqualTo(1678);
        assertThat(world.bits()).isEqualTo(12);
        assertThat(world.toString()).contains("order 4", "12 bits");
    }

    @Test
    @DisplayName("on a tiny model: seen pairs, back-off weights and the uniform floor")
    void tinyModel() {
        NgramLM lm = NgramLM.of(tiny());
        int bos = NgramLM.BOS;

        assertThat(lm.logp(new int[0], 0)).isEqualTo(-1.0);
        assertThat(lm.logp(new int[] {0}, 1)).isEqualTo(-0.5);
        assertThat(lm.logp(new int[] {7, 0}, 1)).isEqualTo(-0.5);
        assertThat(lm.logp(new int[] {0}, 0)).isEqualTo(-0.25 + -1.0);
        assertThat(lm.logp(new int[] {0}, 3)).isEqualTo(-0.25 - Math.log(4));
        assertThat(lm.logp(new int[] {bos}, 0)).isEqualTo(-1.0);
        assertThat(lm.logpKeys(new long[] {NgramLM.pack(new int[] {0, 1}, 4),
            NgramLM.pack(new int[] {0, 0}, 4), NgramLM.pack(new int[] {bos, 2}, 4)}))
            .containsExactly(-0.5, -1.25, -Math.log(4));
        assertThat(lm.maxLogp()).isEqualTo(-0.5);
    }

    @Test
    @DisplayName("packs tokens oldest first, the line start as zero")
    void pack() {
        assertThat(NgramLM.pack(new int[] {NgramLM.BOS, 0, 5}, 11))
            .isEqualTo((0L << 22) | (1L << 11) | 6L);
        assertThat(NgramLM.pack(new int[0], 11)).isZero();
        assertThatThrownBy(() -> NgramLM.pack(new int[] {2047}, 11))
            .hasMessageContaining("does not fit");
        assertThatThrownBy(() -> NgramLM.pack(new int[] {-2}, 11))
            .hasMessageContaining("does not fit");
        assertThatThrownBy(() -> NgramLM.pack(new int[7], 10))
            .hasMessageContaining("do not fit a key");
    }

    @Test
    @DisplayName("finds keys as unsigned values, as numpy searches uint64")
    void findUnsigned() {
        long[] keys = {1L, 5L, Long.MAX_VALUE, Long.MIN_VALUE, -1L};

        assertThat(NgramLM.find(keys, 5L)).isEqualTo(1);
        assertThat(NgramLM.find(keys, Long.MIN_VALUE)).isEqualTo(3);
        assertThat(NgramLM.find(keys, -1L)).isEqualTo(4);
        assertThat(NgramLM.find(keys, 4L)).isEqualTo(-1);
        assertThat(NgramLM.find(keys, -2L)).isEqualTo(-1);
        assertThat(NgramLM.find(new long[0], 0L)).isEqualTo(-1);
    }

    @Test
    @DisplayName("refuses tokens that do not fit a key field")
    void tokenChecks() {
        NgramLM lm = NgramLM.of(tiny());

        assertThatThrownBy(() -> lm.logp(new int[0], -1)).hasMessageContaining("token -1");
        assertThatThrownBy(() -> lm.logp(new int[] {15}, 0))
            .hasMessageContaining("history token 15");
        assertThatThrownBy(() -> lm.logp(new int[0], 15)).hasMessageContaining("4-bit");
    }

    @Test
    @DisplayName("refuses tables that break the file format")
    void malformed() {
        assertThatThrownBy(() -> NgramLM.of(with("order", scalar(9))))
            .hasMessageContaining("order must be");
        assertThatThrownBy(() -> NgramLM.of(with("vocab_size", scalar(0))))
            .hasMessageContaining("vocab_size");
        assertThatThrownBy(() -> NgramLM.of(with("bits", scalar(31))))
            .hasMessageContaining("do not fit a key");
        assertThatThrownBy(() -> NgramLM.of(with("k1", keys(0x11L, 0x10L))))
            .hasMessageContaining("not strictly ascending");
        assertThatThrownBy(() -> NgramLM.of(with("k1", keys(0x11L, 1L << 62))))
            .hasMessageContaining("tag bits");
        assertThatThrownBy(() -> NgramLM.of(with("v1", values(1))))
            .hasMessageContaining("holds 2 keys but v1 holds 1");
        assertThatThrownBy(() -> NgramLM.of(with("hk0", keys(3L))))
            .hasMessageContaining("empty history");
        Map<String, NdArray> missing = new HashMap<>(tiny());
        missing.remove("hb1");
        assertThatThrownBy(() -> NgramLM.of(missing)).hasMessageContaining("no array hb1");
    }

    @Test
    @DisplayName("names the file it cannot read as a model")
    void loadRefusal(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("broken.npz");
        Files.copy(Path.of("src/test/resources/fixtures/foundation/stored.npz"), file);

        assertThatThrownBy(() -> NgramLM.load(file))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("broken.npz")
            .hasMessageContaining("no array order");
    }

    /**
     * An order-2 model over 4 tokens, 4 bits per token: unigrams 0 (-1.0) and 1 (-2.0), the
     * bigrams (0, 1) at -0.5 and (0, 2) at -3.0, and the history (0) with back-off weight
     * -0.25.
     */
    private static Map<String, NdArray> tiny() {
        Map<String, NdArray> a = new HashMap<>();
        a.put("order", scalar(2));
        a.put("vocab_size", scalar(4));
        a.put("bits", scalar(4));
        a.put("k0", keys(1L, 2L));
        a.put("v0", values(-1.0f, -2.0f));
        a.put("hk0", keys(0L));
        a.put("hb0", values(0.0f));
        a.put("k1", keys(0x12L, 0x13L));
        a.put("v1", values(-0.5f, -3.0f));
        a.put("hk1", keys(1L));
        a.put("hb1", values(-0.25f));
        return a;
    }

    private static Map<String, NdArray> with(String name, NdArray value) {
        Map<String, NdArray> a = new HashMap<>(tiny());
        a.put(name, value);
        return a;
    }

    private static NdArray scalar(long v) {
        return new NdArray("i8", new int[0], new long[] {v});
    }

    private static NdArray keys(long... k) {
        return new NdArray("u8", new int[] {k.length}, k);
    }

    private static NdArray values(float... v) {
        return new NdArray("f4", new int[] {v.length}, v);
    }
}
