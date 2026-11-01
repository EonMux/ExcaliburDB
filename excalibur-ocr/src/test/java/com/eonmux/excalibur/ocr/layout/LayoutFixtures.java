package com.eonmux.excalibur.ocr.layout;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.testing.Fixtures;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The reading-order fixtures of {@code tools/fixtures/gen_layout.py}: synthetic pages read by
 * Indra's pipeline, with every call's inputs and outputs, and hand-made edge cases.
 */
final class LayoutFixtures {

    /** The pages, each read with {@code table_heads} off and, suffixed {@code _heads}, on. */
    static final List<String> PAGES = List.of("two_column", "lists", "table", "table_unruled",
        "picture", "furniture", "table_heads", "table_unruled_heads", "two_column_heads");

    private LayoutFixtures() {
        throw new AssertionError("Static methods only");
    }

    /** One page's fixture: its arrays and its JSON document. */
    record Page(String name, Map<String, NdArray> arrays, Map<String, Object> doc) {

        List<Map<String, Object>> runs() {
            return maps(doc.get("runs"));
        }

        float[] array(String key) {
            return arrays.get(key).floats();
        }

        float[][] boxes(int run) {
            return LayoutFixtures.boxes(arrays.get("r" + run + "_boxes"));
        }
    }

    static Page page(String name) {
        String stem = "layout/page_" + name;
        return new Page(name, Fixtures.npz(stem + ".npz"), map(FixtureJson.parse(
            Fixtures.text(stem + ".json"))));
    }

    static Map<String, NdArray> syntheticArrays() {
        return Fixtures.npz("layout/synthetic.npz");
    }

    static Map<String, Object> syntheticDoc() {
        return map(FixtureJson.parse(Fixtures.text("layout/synthetic.json")));
    }

    /** A {@code [N, 4]} float32 array as rows. */
    static float[][] boxes(NdArray array) {
        int[] shape = array.shape();
        float[] v = array.floats();
        int cols = shape.length == 2 ? shape[1] : 4;
        float[][] out = new float[shape[0]][];
        for (int i = 0; i < out.length; i++) {
            out[i] = Arrays.copyOfRange(v, i * cols, i * cols + cols);
        }
        return out;
    }

    static int[] ints(Object list) {
        return ((List<?>) list).stream().mapToInt(o -> ((Number) o).intValue()).toArray();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object o) {
        return (List<Map<String, Object>>) o;
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Object o) {
        return (List<String>) o;
    }

    static List<int[]> groups(Object o) {
        return ((List<?>) o).stream().map(LayoutFixtures::ints).toList();
    }
}
