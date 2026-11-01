package com.eonmux.excalibur.ocr.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Indra OCR's pinned model set")
class IndraModelsTest {

    @Test
    @DisplayName("is the files Indra OCR 1.1.2 downloads, the licence first, math only on request")
    void files() {
        List<ModelFile> core = IndraModels.files(false);
        List<ModelFile> all = IndraModels.files(true);

        assertThat(core.getFirst().name()).isEqualTo(IndraModels.LICENSE_FILE);
        assertThat(core).extracting(ModelFile::name).contains("det.onnx", "rec.onnx",
            "order.onnx", "meta.json", "lm.npz", "lm_world.npz", "lm_cjk.npz", "orient.onnx",
            "script.onnx", "rec_cjk.onnx", "rec_rtl.onnx", "rec_world.onnx")
            .doesNotContain("mathcls.onnx");
        assertThat(all).containsAll(core).hasSize(core.size() + 5)
            .extracting(ModelFile::name).contains("math_enc.onnx", "math_meta.json");
        assertThat(all).extracting(ModelFile::name).doesNotHaveDuplicates();
        assertThat(IndraModels.SOURCE.toString())
            .isEqualTo("https://huggingface.co/datalab-to/indra-ocr/resolve/v1.1.0/");
    }

    @Test
    @DisplayName("names each file by a plain name, a length and a SHA-256 digest")
    void pins() {
        assertThatThrownBy(() -> new ModelFile("../x", 1, "0".repeat(64)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelFile(".hidden", 1, "0".repeat(64)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelFile("x", 0, "0".repeat(64)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelFile("x", 1, "ABC"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ModelFile("x", 1, "ab".repeat(32)).digest()).hasSize(32);
    }
}
