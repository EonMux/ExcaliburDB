package com.eonmux.excalibur.ocr.math;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.image.GrayImage;
import com.eonmux.excalibur.ocr.json.Json;
import com.eonmux.excalibur.ocr.recognize.LineCrops;
import com.eonmux.excalibur.ocr.testing.Fixtures;
import com.eonmux.excalibur.ocr.testing.Models;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** The math reader against Indra's own on a synthetic page ({@code gen_math.py}). */
@DisplayName("Math lines, read as Indra OCR reads them")
class MathReaderTest {

    @Test
    @DisplayName("flags the lines Indra flags and reads each as Indra does, early stops included")
    void readsAsIndra() throws Exception {
        Map<String, NdArray> arrays = Fixtures.npz("math/math.npz");
        Map<String, Object> want = Json.parseObject(Fixtures.text("math/math.json"));
        GrayImage page = Fixtures.image(arrays.get("page"));
        float[][] boxes = rows(arrays.get("boxes"));

        try (MathReader reader = MathReader.load(Models.dir())) {
            boolean[] flags = reader.flags(LineCrops.cropLines(page, boxes, 32, 1, null));
            List<MathReader.Reading> reads = reader.read(page, boxes);

            List<?> wantFlags = (List<?>) want.get("flags");
            List<?> wantReads = (List<?>) want.get("reads");
            for (int i = 0; i < boxes.length; i++) {
                List<?> r = (List<?>) wantReads.get(i);
                assertThat(flags[i]).as("flag %d", i).isEqualTo(wantFlags.get(i));
                assertThat(reads.get(i).text()).as("text %d", i).isEqualTo(r.get(0));
                assertThat(reads.get(i).conf()).as("confidence %d", i)
                    .isCloseTo(((Number) r.get(1)).doubleValue(), within(1e-6));
            }
        }
    }

    @Test
    @DisplayName("joins tokens, spacing them inside math")
    void detokenize() {
        Map<String, Object> want = Json.parseObject(Fixtures.text("math/math.json"));
        for (Object o : (List<?>) want.get("detokenize")) {
            List<?> c = (List<?>) o;
            List<String> tokens = ((List<?>) c.get(0)).stream().map(String.class::cast).toList();
            assertThat(MathText.detokenize(tokens)).isEqualTo(c.get(1));
        }
    }

    @Test
    @DisplayName("refuses a directory without the math models")
    void missingModels(@TempDir Path empty) {
        assertThatThrownBy(() -> MathReader.load(empty)).isInstanceOf(IOException.class);
    }

    private static float[][] rows(NdArray array) {
        int n = array.shape()[0];
        int cols = array.shape()[1];
        float[] v = array.floats();
        float[][] out = new float[n][cols];
        for (int i = 0; i < n; i++) {
            System.arraycopy(v, i * cols, out[i], 0, cols);
        }
        return out;
    }
}
