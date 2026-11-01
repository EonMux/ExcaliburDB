package com.eonmux.excalibur.ocr.model;

import com.eonmux.excalibur.ocr.testing.Models;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("The pinned model set, as published")
class PinnedModelsTest {

    @Test
    @DisplayName("is what the repository serves, licence included")
    void published() throws Exception {
        var dir = Models.dir();

        assertThat(ModelStore.local(dir).ensure(IndraModels.files(true))).isEqualTo(dir);
        assertThat(Files.readString(dir.resolve(IndraModels.LICENSE_FILE)))
            .contains("OPEN RAIL-M");
    }
}
