package com.eonmux.excalibur.ocr.lm;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.testing.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Beam search with a language model")
class BeamSearchTest {

    /** One recognizer line: what rec.onnx (or a group recognizer) gave for it. */
    private record Line(float[] blank, float[][] topv, long[][] topi, String lm) {
    }

    @Test
    @DisplayName("reads every recorded line exactly as Python's beam_search does")
    void matchesPython() {
        Map<String, NdArray> f = Fixtures.npz("text/beam.npz");
        List<Line> lines = lines(f);
        long[] input = f.get("case_input").longs();
        double[] alpha = f.get("case_alpha").doubles();
        double[] beta = f.get("case_beta").doubles();
        long[] beam = f.get("case_beam").longs();
        double[] skip = f.get("case_blank_skip").doubles();
        double[] floor = f.get("case_cand_floor").doubles();
        long[] outLen = f.get("out_len").longs();
        long[] outIds = f.get("out_ids").longs();
        long[] outSteps = f.get("out_steps").longs();

        List<String> mismatches = new ArrayList<>();
        int offset = 0;
        for (int k = 0; k < input.length; k++) {
            Line line = lines.get((int) input[k]);
            BeamSearch.Options options = new BeamSearch.Options(alpha[k], beta[k], (int) beam[k],
                skip[k], floor[k]);
            int n = (int) outLen[k];
            BeamSearch.Reading expected = new BeamSearch.Reading(
                ints(outIds, offset, n), ints(outSteps, offset, n));
            offset += n;

            BeamSearch.Reading actual = BeamSearch.search(line.blank(), line.topv(), line.topi(),
                LanguageModels.get(line.lm()), options);

            if (!actual.equals(expected)) {
                mismatches.add("case " + k + " (" + line.lm() + ", " + options + "): expected "
                    + expected + ", got " + actual);
            }
        }

        assertThat(input).hasSizeGreaterThan(100);
        assertThat(mismatches).isEmpty();
    }

    @Test
    @DisplayName("tries a class whose float32 log-probability equals the floor's, as numpy does")
    void floorComparedInFloat32() {
        double logFloor = Math.log(3e-3);
        float atFloor = (float) logFloor;

        assertThat((double) atFloor).isLessThan(logFloor);
        NgramLM lm = LanguageModels.get("base");
        float[] blank = {(float) Math.log(0.5)};
        float[][] topv = {{(float) Math.log(0.5), atFloor}};
        long[][] topi = {{0, 40}};
        BeamSearch.Options options = new BeamSearch.Options(0.5, 50.0, 8, 0.999, 3e-3);

        assertThat(BeamSearch.search(blank, topv, topi, lm, options).ids()).containsExactly(40);
    }

    @Test
    @DisplayName("reads nothing from an empty line, and only the blank from an all-blank one")
    void emptyAndBlank() {
        NgramLM lm = LanguageModels.get("base");
        BeamSearch.Reading empty = BeamSearch.search(new float[0], new float[0][], new long[0][],
            lm, BeamSearch.Options.DEFAULTS);
        float[][] topv = {{-1e-5f, -12f}, {-1e-5f, -12f}};
        long[][] topi = {{0, 5}, {0, 5}};
        BeamSearch.Reading blank = BeamSearch.search(new float[] {-1e-5f, -1e-5f}, topv, topi, lm,
            BeamSearch.Options.DEFAULTS);

        assertThat(empty.ids()).isEmpty();
        assertThat(empty.steps()).isEmpty();
        assertThat(blank).isEqualTo(new BeamSearch.Reading(new int[0], new int[0]));
    }

    @Test
    @DisplayName("adds log-probabilities exactly when one of them is minus infinity")
    void logSumExp() {
        double inf = Double.NEGATIVE_INFINITY;

        assertThat(BeamSearch.lse(inf, -2.0)).isEqualTo(-2.0);
        assertThat(BeamSearch.lse(-3.0, inf)).isEqualTo(-3.0);
        assertThat(BeamSearch.lse(inf, inf)).isEqualTo(inf);
        assertThat(BeamSearch.lse(Math.log(0.25), Math.log(0.25))).isCloseTo(Math.log(0.5),
            org.assertj.core.data.Offset.offset(1e-15));
    }

    @Test
    @DisplayName("refuses inputs that disagree in shape or are not log-probabilities")
    void refusals() {
        NgramLM lm = LanguageModels.get("base");
        BeamSearch.Options d = BeamSearch.Options.DEFAULTS;
        float[] one = {-1f};

        assertThatThrownBy(() -> BeamSearch.search(one, new float[0][], new long[0][], lm, d))
            .hasMessageContaining("one row per step");
        assertThatThrownBy(() -> BeamSearch.search(one, new float[][] {{-1f}},
            new long[][] {{1, 2}}, lm, d)).hasMessageContaining("1 values but 2 classes");
        assertThatThrownBy(() -> BeamSearch.search(new float[] {Float.NaN}, new float[][] {{-1f}},
            new long[][] {{1}}, lm, d)).hasMessageContaining("blank[0]");
        assertThatThrownBy(() -> BeamSearch.search(one, new float[][] {{Float.POSITIVE_INFINITY}},
            new long[][] {{1}}, lm, d)).hasMessageContaining("topv[0][0]");
        assertThatThrownBy(() -> BeamSearch.search(one, new float[][] {{-1f}},
            new long[][] {{-3}}, lm, d)).hasMessageContaining("not a class");
        assertThatThrownBy(() -> new BeamSearch.Options(Double.NaN, 1, 8, 0.9, 1e-3))
            .hasMessageContaining("finite");
        assertThatThrownBy(() -> new BeamSearch.Options(0.5, 1.5, 0, 0.9, 1e-3))
            .hasMessageContaining("beam");
        assertThatThrownBy(() -> new BeamSearch.Options(0.5, 1.5, 8, 0, 1e-3))
            .hasMessageContaining("blankSkip");
        assertThatThrownBy(() -> new BeamSearch.Options(0.5, 1.5, 8, 0.9, -1))
            .hasMessageContaining("candFloor");
        assertThatThrownBy(() -> new BeamSearch.Reading(new int[1], new int[0]))
            .hasMessageContaining("1 ids but 0 steps");
    }

    @Test
    @DisplayName("hands out readings that cannot be changed through their arrays")
    void readingIsImmutable() {
        int[] ids = {3, 4};
        BeamSearch.Reading r = new BeamSearch.Reading(ids, new int[] {0, 2});
        ids[0] = 9;
        r.ids()[1] = 9;

        assertThat(r.ids()).containsExactly(3, 4);
        assertThat(r).hasSameHashCodeAs(new BeamSearch.Reading(new int[] {3, 4}, new int[] {0, 2}));
        assertThat(r.toString()).contains("[3, 4]", "[0, 2]");
    }

    private static List<Line> lines(Map<String, NdArray> f) {
        long[] lengths = f.get("in_len").longs();
        long[] lms = f.get("in_lm").longs();
        float[] blank = f.get("blank").floats();
        float[] topv = f.get("topv").floats();
        long[] topi = f.get("topi").longs();
        int k = f.get("topv").shape()[1];
        List<Line> out = new ArrayList<>();
        int row = 0;
        for (int i = 0; i < lengths.length; i++) {
            int n = (int) lengths[i];
            float[][] v = new float[n][];
            long[][] c = new long[n][];
            for (int t = 0; t < n; t++) {
                v[t] = Arrays.copyOfRange(topv, (row + t) * k, (row + t + 1) * k);
                c[t] = Arrays.copyOfRange(topi, (row + t) * k, (row + t + 1) * k);
            }
            out.add(new Line(Arrays.copyOfRange(blank, row, row + n), v, c,
                LanguageModels.NAMES.get((int) lms[i])));
            row += n;
        }
        return out;
    }

    private static int[] ints(long[] values, int from, int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = Math.toIntExact(values[from + i]);
        }
        return out;
    }
}
