package com.eonmux.excalibur.ocr.layout;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.eonmux.excalibur.ocr.testing.Models;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.eonmux.excalibur.ocr.layout.LayoutFixtures.ints;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("order.onnx run from Java scores the captured features as it did in Python")
class OrderModelParityTest {

    static final List<String> PAGES = LayoutFixtures.PAGES;
    private static final float TOLERANCE = 1e-4f;

    private static OrtEnvironment env;
    private static OrtSession session;

    @BeforeAll
    static void load() throws OrtException {
        env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setCPUArenaAllocator(false);
            session = env.createSession(Models.dir().resolve("order.onnx").toString(), options);
        }
    }

    @AfterAll
    static void close() throws OrtException {
        session.close();
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("PAGES")
    @DisplayName("a, cls, same and tab agree to 1e-4 and decode to Indra's order")
    void logits(String name) throws OrtException {
        LayoutFixtures.Page page = LayoutFixtures.page(name);
        List<Map<String, Object>> runs = page.runs();
        for (int r = 0; r < runs.size(); r++) {
            String run = "r" + r + "_";
            float[] feats = page.array(run + "feats");
            int n = feats.length / OrderFeatures.N_FEATS;
            Map<String, float[]> out = score(feats, n);

            for (String head : List.of("a", "cls", "same", "tab")) {
                assertThat(out.get(head)).as("%s run %d %s", name, r, head)
                    .containsExactly(page.array(run + head), within(TOLERANCE));
            }
            assertThat(ReadingOrder.decodeBlocks(out.get("a"), out.get("same"), page.boxes(r),
                out.get("cls"), n)).as("%s run %d order", name, r)
                .containsExactly(ints(runs.get(r).get("order")));
        }
    }

    /** Runs the model on one page's features, all lines unmasked; outputs without the batch. */
    private static Map<String, float[]> score(float[] feats, int n) throws OrtException {
        boolean[][] mask = new boolean[1][n];
        Arrays.fill(mask[0], true);
        try (OnnxTensor f = OnnxTensor.createTensor(env, FloatBuffer.wrap(feats),
                 new long[] {1, n, OrderFeatures.N_FEATS});
             OnnxTensor m = OnnxTensor.createTensor(env, mask);
             OrtSession.Result result = session.run(Map.of("feats", f, "mask", m))) {
            Map<String, float[]> out = new java.util.HashMap<>();
            for (Map.Entry<String, OnnxValue> e : result) {
                FloatBuffer buf = ((OnnxTensor) e.getValue()).getFloatBuffer();
                float[] values = new float[buf.remaining()];
                buf.get(values);
                out.put(e.getKey(), values);
            }
            return out;
        }
    }
}
