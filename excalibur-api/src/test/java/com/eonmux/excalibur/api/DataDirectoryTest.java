package com.eonmux.excalibur.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a server keeps its files, read from {@code EXCALIBUR_DATA_DIR}.
 */
@DisplayName("Data directory")
class DataDirectoryTest {

    private static final Path DEFAULT =
        Path.of(ExcaliburApplication.DEFAULT_DATA_DIR).toAbsolutePath();

    @Test
    @DisplayName("the variable names the directory")
    void theVariableNamesIt() {
        assertThat(ExcaliburApplication.dataDirectory(Map.of("EXCALIBUR_DATA_DIR", "/srv/excalibur")))
            .isEqualTo(Path.of("/srv/excalibur"));
    }

    @Test
    @DisplayName("without the variable the default is used")
    void absentMeansTheDefault() {
        assertThat(ExcaliburApplication.dataDirectory(Map.of())).isEqualTo(DEFAULT);
        assertThat(ExcaliburApplication.dataDirectory(Map.of("EXCALIBUR_DATA_DIR", "")))
            .isEqualTo(DEFAULT);
    }

    /**
     * What a compose file delivers when it interpolates a variable unset on the host. Read as a
     * path, {@code " "} is a directory named with a space under the working directory, and a node
     * started that way serves from an empty directory beside the one holding its data.
     */
    @Test
    @DisplayName("a variable that is only whitespace is unset, not a directory named with a space")
    void whitespaceMeansTheDefault() {
        assertThat(ExcaliburApplication.dataDirectory(Map.of("EXCALIBUR_DATA_DIR", "   ")))
            .isEqualTo(DEFAULT);
        assertThat(ExcaliburApplication.dataDirectory(Map.of("EXCALIBUR_DATA_DIR", "\t\n")))
            .isEqualTo(DEFAULT);
    }
}
