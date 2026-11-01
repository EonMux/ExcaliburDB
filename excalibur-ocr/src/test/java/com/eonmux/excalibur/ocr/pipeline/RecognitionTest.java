package com.eonmux.excalibur.ocr.pipeline;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.image.GrayImage;
import com.eonmux.excalibur.ocr.json.Json;
import com.eonmux.excalibur.ocr.math.MathReader;
import com.eonmux.excalibur.ocr.testing.Fixtures;
import com.eonmux.excalibur.ocr.testing.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Line recognition against Indra's own on pages of several scripts
 * ({@code draw_pages.py}, {@code gen_recognize.py}).
 */
@DisplayName("Line recognition, as Indra OCR reads lines")
class RecognitionTest {

    private static OcrModels models;
    private static MathReader math;
    private static Map<String, NdArray> pages;
    private static Map<String, Object> want;

    @BeforeAll
    static void load() throws Exception {
        models = OcrModels.load(Models.dir());
        math = MathReader.load(Models.dir());
        pages = Fixtures.npz("pages/pages.npz");
        want = Json.parseObject(Fixtures.text("pipeline/recognize.json"));
    }

    @AfterAll
    static void close() {
        models.close();
        math.close();
    }

    @Test
    @DisplayName("routes each line to its script's recognizer as Indra does")
    void groups() {
        GrayImage page = Fixtures.image(pages.get("page0"));
        Recognition.Groups groups = new Recognition(models, null)
            .boxGroups(page, LineRulesTest.boxes(rows(pages.get("boxes0"))));

        List<?> expected = (List<?>) want.get("groups0");
        assertThat(groups.names()).containsExactly(((List<?>) expected.get(0)).stream()
            .map(String.class::cast).toArray(String[]::new));
        List<?> sure = (List<?>) expected.get(1);
        for (int i = 0; i < sure.size(); i++) {
            assertThat(groups.sure()[i]).isEqualTo(sure.get(i));
        }
    }

    @Test
    @DisplayName("reads every line as Indra does: upright, vertical, with and without math")
    void readsAsIndra() {
        GrayImage page = Fixtures.image(pages.get("page0"));
        float[][] boxes = LineRulesTest.boxes(rows(pages.get("boxes0")));
        for (boolean withMath : new boolean[] {false, true}) {
            Recognition recognition = new Recognition(models, withMath ? math : null);
            for (boolean vertical : new boolean[] {false, true}) {
                List<Reading> got = recognition.recognize(page, boxes, vertical);
                String key = "page0_vertical" + (vertical ? 1 : 0) + "_math" + (withMath ? 1 : 0);

                assertThat(got).as(key).isEqualTo(LineRulesTest.readings(want.get(key)));
            }
        }
    }

    private static List<List<Double>> rows(NdArray array) {
        int n = array.shape()[0];
        int cols = array.shape()[1];
        float[] v = array.floats();
        return java.util.stream.IntStream.range(0, n).mapToObj(i ->
            java.util.stream.IntStream.range(0, cols).mapToObj(c -> (double) v[i * cols + c])
                .toList()).toList();
    }
}
