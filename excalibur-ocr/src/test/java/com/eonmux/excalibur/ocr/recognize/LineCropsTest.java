package com.eonmux.excalibur.ocr.recognize;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.image.GrayImage;
import com.eonmux.excalibur.ocr.image.InkMap;
import com.eonmux.excalibur.ocr.json.Json;
import com.eonmux.excalibur.ocr.testing.Fixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Line crops and batches against Indra's own on synthetic pages
 * ({@code tools/fixtures/gen_crops.py}).
 */
@DisplayName("Recognizer inputs, as Indra OCR cuts and batches them")
class LineCropsTest {

    private static Map<String, NdArray> arrays;
    private static Map<String, Object> meta;

    @BeforeAll
    static void load() {
        arrays = Fixtures.npz("recognize/crops.npz");
        meta = Json.parseObject(Fixtures.text("recognize/crops.json"));
    }

    @Test
    @DisplayName("finds light-on-dark lines, and cuts every line as Indra does, to the bit")
    void crops() {
        for (Map<String, Object> page : list(meta.get("pages"))) {
            int p = ((Number) page.get("page")).intValue();
            GrayImage gray = Fixtures.image(arrays.get("page" + p));
            float[][] boxes = rows(arrays.get("boxes" + p));
            boolean[] dark = LineCrops.lineDarkness(gray, boxes);
            List<?> expectedDark = (List<?>) page.get("dark");
            for (int i = 0; i < boxes.length; i++) {
                assertThat(dark[i]).as("page %d line %d", p, i).isEqualTo(expectedDark.get(i));
            }
            for (int window : new int[] {1, 3}) {
                List<InkMap> crops = LineCrops.cropLines(gray, boxes, 32, window,
                    window == 1 ? null : dark);
                for (int i = 0; i < crops.size(); i++) {
                    assertThat(crops.get(i)).as("page %d window %d line %d", p, window, i)
                        .isEqualTo(ink(arrays.get("crop" + p + "_" + window + "_" + i),
                            dark[i]));
                }
                LineBatches.Batch batch = LineBatches.pad(crops);
                assertThat(batch.x().shape())
                    .containsExactly(ints(arrays.get("shape" + p + "_" + window)));
                assertThat(batch.widths().longs())
                    .containsExactly(arrays.get("widths" + p + "_" + window).longs());
                double[] sums = arrays.get("sum" + p + "_" + window).doubles();
                float[] x = batch.x().floats();
                int per = x.length / crops.size();
                for (int i = 0; i < crops.size(); i++) {
                    double sum = 0;
                    for (int k = i * per; k < (i + 1) * per; k++) {
                        sum += x[k];
                    }
                    assertThat(sum).isCloseTo(sums[i], within(1e-6));
                }
            }
        }
    }

    @Test
    @DisplayName("takes percentiles from the histogram as numpy interpolates them")
    void percentiles() {
        for (Map<String, Object> page : list(meta.get("pages"))) {
            int p = ((Number) page.get("page")).intValue();
            GrayImage gray = Fixtures.image(arrays.get("page" + p));
            float[][] boxes = rows(arrays.get("boxes" + p));
            List<?> expected = (List<?>) page.get("percentiles");
            for (int i = 0; i < boxes.length; i++) {
                float[] b = boxes[i];
                GrayImage region = gray.region((int) b[0], (int) b[1], (int) b[2] + 1,
                    (int) b[3] + 1);
                if (expected.get(i) == null) {
                    assertThat(region).isNull();
                    continue;
                }
                double[] want = ((List<?>) expected.get(i)).stream()
                    .mapToDouble(v -> ((Number) v).doubleValue()).toArray();
                assertThat(LineCrops.percentiles(region)).containsExactly(want);
            }
        }
    }

    @Test
    @DisplayName("groups lines into batches of similar width, the recognizers' and math's")
    void widthBatches() {
        for (Map<String, Object> c : list(meta.get("width_batches"))) {
            int[] widths = ((List<?>) c.get("widths")).stream()
                .mapToInt(v -> ((Number) v).intValue()).toArray();
            int max = ((Number) c.get("max_batch")).intValue();

            assertThat(LineBatches.widthBatches(widths, max))
                .usingRecursiveComparison().isEqualTo(batches(c.get("batches")));
            assertThat(LineBatches.widthBatches(widths, max, 4.0, LineBatches.MIN_WIDTH,
                    16L * 64 * 640))
                .usingRecursiveComparison().isEqualTo(batches(c.get("math")));
        }
    }

    @Test
    @DisplayName("refuses a batch of lines of different heights")
    void mixedHeights() {
        assertThatThrownBy(() -> LineBatches.pad(List.of(InkMap.of(8, 32, new float[256]),
                InkMap.of(8, 96, new float[768]))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static InkMap ink(NdArray stored, boolean dark) {
        int[] shape = stored.shape();
        long[] u = stored.longs();
        float[] values = new float[u.length];
        for (int i = 0; i < u.length; i++) {
            float v = u[i] / 255f;
            values[i] = dark ? v : 1f - v;
        }
        return InkMap.of(shape[1], shape[0], values);
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

    private static int[] ints(NdArray array) {
        return java.util.Arrays.stream(array.longs()).mapToInt(Math::toIntExact).toArray();
    }

    private static List<int[]> batches(Object value) {
        return ((List<?>) value).stream().map(b -> ((List<?>) b).stream()
            .mapToInt(v -> ((Number) v).intValue()).toArray()).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
