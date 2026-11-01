package com.eonmux.excalibur.ocr.runtime;

import com.eonmux.excalibur.ocr.array.NdArray;
import com.eonmux.excalibur.ocr.testing.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("An ONNX model")
class OnnxModelTest {

    private static OnnxModel det;
    private static OnnxModel order;
    private static OnnxModel rec;

    @BeforeAll
    static void load() throws Exception {
        det = OnnxModel.load(Models.dir().resolve("det.onnx"), 1);
        order = OnnxModel.load(Models.dir().resolve("order.onnx"), 2);
        rec = OnnxModel.load(Models.dir().resolve("rec.onnx"), 1);
    }

    @AfterAll
    static void close() {
        det.close();
        order.close();
        rec.close();
    }

    @Test
    @DisplayName("runs on float input and returns its output with its shape")
    void floats() {
        NdArray page = new NdArray("f4", new int[] {1, 1, 64, 96}, new float[64 * 96]);

        List<NdArray> out = det.run(Map.of("x", page));

        assertThat(out).hasSize(1);
        assertThat(out.getFirst().dtype()).isEqualTo("f4");
        assertThat(out.getFirst().shape()).containsExactly(1, 3, 32, 48);
        assertThat(det.inputNames()).containsExactly("x");
        assertThat(det.name()).isEqualTo("det.onnx");
        assertThat(det.inputShapes()).containsKey("x");
        assertThat(det.toString()).contains("det.onnx");
    }

    @Test
    @DisplayName("feeds booleans and 64-bit integers, and returns the outputs asked for in order")
    void otherTypes() {
        NdArray feats = new NdArray("f4", new int[] {1, 2, 16}, new float[32]);
        NdArray mask = new NdArray("b1", new int[] {1, 2}, new boolean[] {true, true});

        Map<String, NdArray> out = order.run(Map.of("feats", feats, "mask", mask),
            List.of("cls", "a"));

        assertThat(out.keySet()).containsExactly("cls", "a");
        assertThat(out.get("a").shape()).containsExactly(1, 2, 2);

        NdArray line = new NdArray("f4", new int[] {1, 1, 96, 64}, new float[96 * 64]);
        NdArray widths = new NdArray("i8", new int[] {1}, new long[] {64});
        Map<String, NdArray> read = rec.run(Map.of("x", line, "widths", widths),
            List.of("ids", "lengths"));
        assertThat(read.get("ids").dtype()).isIn("i8", "i4");
        assertThat(read.get("lengths").longs()).hasSize(1);
    }

    @Test
    @DisplayName("feeds a scalar and tensors with no elements, as a decoder's first step has")
    void emptyAndScalar() throws Exception {
        try (OnnxModel enc = OnnxModel.load(Models.dir().resolve("math_enc.onnx"), 1);
             OnnxModel init = OnnxModel.load(Models.dir().resolve("math_dec_init.onnx"), 1);
             OnnxModel step = OnnxModel.load(Models.dir().resolve("math_dec_step.onnx"), 1)) {
            Map<String, NdArray> encoded = enc.run(Map.of(
                "x", new NdArray("f4", new int[] {1, 1, 64, 128}, new float[64 * 128]),
                "widths", new NdArray("i8", new int[] {1}, new long[] {128})),
                List.of("memory", "mask"));
            Map<String, NdArray> cross = init.run(Map.of("memory", encoded.get("memory")),
                List.of("cross_k", "cross_v"));
            NdArray empty = new NdArray("f4", new int[] {3, 1, 8, 0, 24}, new float[0]);

            Map<String, NdArray> out = step.run(Map.of(
                "token", new NdArray("i8", new int[] {1}, new long[] {1}),
                "pos", new NdArray("i8", new int[] {}, new long[] {0}),
                "self_k", empty, "self_v", empty,
                "cross_k", cross.get("cross_k"), "cross_v", cross.get("cross_v"),
                "mask", encoded.get("mask")), List.of("logits", "new_k"));

            assertThat(out.get("logits").shape()).containsExactly(1, 652);
            assertThat(out.get("new_k").shape()).containsExactly(3, 1, 8, 1, 24);
        }
    }

    @Test
    @DisplayName("reports a model that cannot be loaded, or inputs it rejects")
    void failures(@TempDir Path dir) throws Exception {
        Path junk = Files.writeString(dir.resolve("junk.onnx"), "not a model");

        assertThatThrownBy(() -> OnnxModel.load(junk, 1))
            .isInstanceOf(java.io.IOException.class).hasMessageContaining("junk.onnx");
        assertThatThrownBy(() -> OnnxModel.load(junk, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> det.run(Map.of("x",
                new NdArray("f4", new int[] {3}, new float[3]))))
            .isInstanceOf(ModelRunException.class).hasMessageContaining("det.onnx");
        assertThatThrownBy(() -> det.run(Map.of("x",
                new NdArray("f8", new int[] {1}, new double[1]))))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
