package com.eonmux.excalibur.ocr.pipeline;

import com.eonmux.excalibur.ocr.model.IndraModels;
import com.eonmux.excalibur.ocr.model.ModelFile;
import com.eonmux.excalibur.ocr.testing.Models;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("The OCR models of a directory")
class OcrModelsTest {

    @Test
    @DisplayName("loads the pinned files and ignores any other, even one named like a model")
    void onlyPinnedFiles(@TempDir Path dir) throws Exception {
        for (ModelFile file : IndraModels.files(false)) {
            Files.createSymbolicLink(dir.resolve(file.name()), Models.dir().resolve(file.name()));
        }
        // Not in the pinned revision: a language model for the right-to-left recognizer, here
        // bytes that would fail to load if they were read at all.
        Files.writeString(dir.resolve("lm_rtl.npz"), "not an archive");
        Files.writeString(dir.resolve("rec.int8.onnx"), "not a model");

        try (OcrModels models = OcrModels.load(dir)) {
            assertThat(models.group("rtl")).isNotNull();
            assertThat(models.group("rtl").lm()).isNull();
            assertThat(models.base.lm()).isNotNull();
        }
    }

    @Test
    @DisplayName("refuses a directory without the detector")
    void missingDetector(@TempDir Path dir) throws Exception {
        Files.createSymbolicLink(dir.resolve("meta.json"), Models.dir().resolve("meta.json"));

        assertThatThrownBy(() -> OcrModels.load(dir))
            .hasMessageContaining("det.onnx");
    }
}
