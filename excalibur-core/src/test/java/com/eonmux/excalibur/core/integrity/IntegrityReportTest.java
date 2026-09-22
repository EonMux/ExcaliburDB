package com.eonmux.excalibur.core.integrity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the verdict every format's verification entry point returns.
 *
 * <p>The distinction the report draws between "checked and sound" and "not refused" is the
 * whole point of it, so it is pinned here rather than re-asserted per format.</p>
 */
@DisplayName("IntegrityReport")
class IntegrityReportTest {

    private static final Path FILE = Path.of("/var/lib/excalibur/segment/post.dat");

    @Test
    @DisplayName("only an intact report counts as verified")
    void onlyIntactIsVerified() {
        assertThat(IntegrityReport.intact(FILE, "test", "all of it").verified()).isTrue();
        assertThat(IntegrityReport.unsealed(FILE, "test", "still open").verified()).isFalse();
        assertThat(IntegrityReport.legacy(FILE, "test", 1).verified()).isFalse();
        assertThat(IntegrityReport.missing(FILE, "test").verified()).isFalse();
    }

    @Test
    @DisplayName("damage is the only thing that makes an image unloadable")
    void onlyDamageBlocksLoading() {
        assertThat(IntegrityReport.intact(FILE, "test", "all of it").loadable()).isTrue();
        assertThat(IntegrityReport.unsealed(FILE, "test", "still open").loadable()).isTrue();
        assertThat(IntegrityReport.legacy(FILE, "test", 1).loadable()).isTrue();
        assertThat(IntegrityReport.corrupt(FILE, "test", "mismatch").loadable()).isFalse();
        assertThat(IntegrityReport.unreadable(FILE, "test", "eio").loadable()).isFalse();
    }

    @Test
    @DisplayName("refusing a damaged file names the file and says what to do")
    void refusalNamesTheFile() {
        IntegrityReport report = IntegrityReport.corrupt(FILE, "lexical segment column",
            "the stored checksum does not match");

        assertThatThrownBy(report::requireIntact)
            .isInstanceOf(IOException.class)
            .hasMessageContaining(FILE.toString())
            .hasMessageContaining("lexical segment column")
            .hasMessageContaining("the stored checksum does not match")
            .hasMessageContaining("will not be loaded");
    }

    @Test
    @DisplayName("a legacy report names the version it found")
    void legacyNamesTheVersion() {
        assertThat(IntegrityReport.legacy(FILE, "test", 2).status())
            .isEqualTo(IntegrityStatus.LEGACY_UNPROTECTED);
        assertThat(IntegrityReport.legacy(FILE, "test", 2).detail())
            .contains("format version 2");
    }

    @Test
    @DisplayName("an intact or unsealed report is not refused")
    void soundReportsPass() throws IOException {
        IntegrityReport.intact(FILE, "test", "all of it").requireIntact();
        IntegrityReport.unsealed(FILE, "test", "still open").requireIntact();
        IntegrityReport.legacy(FILE, "test", 1).requireIntact();
    }
}
