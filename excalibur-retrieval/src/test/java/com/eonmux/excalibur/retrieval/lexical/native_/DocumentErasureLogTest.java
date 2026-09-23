package com.eonmux.excalibur.retrieval.lexical.native_;

import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.core.integrity.IntegrityStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lexical erasure log's per-line checksums: what they let through, what they refuse, and
 * how the versions before them are read and upgraded.
 */
@DisplayName("Lexical erasure log checksums")
class DocumentErasureLogTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    private static Path log(Path dir) {
        return dir.resolve(DocumentErasureLog.FILE);
    }

    private static void writeLog(Path dir, String text) throws IOException {
        Files.writeString(log(dir), text, StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("at the current version")
    class Current {

        @Test
        @DisplayName("reads back every promise and confirmation, each line sealed")
        void roundTrip(@TempDir Path dir) throws Exception {
            DocumentErasureLog log = DocumentErasureLog.open(dir);
            log.append(List.of("a", "b"), AT);
            log.confirmApplied(List.of("a"));

            List<String> lines = Files.readAllLines(log(dir));
            assertThat(lines.get(0)).isEqualTo("excalibur-lexical-erasure v3");
            assertThat(lines.subList(1, lines.size()))
                .allSatisfy(line -> assertThat(line).matches(".*\\t[0-9a-f]{8}"));
            DocumentErasureLog reopened = DocumentErasureLog.open(dir);
            assertThat(reopened.outstandingKeys()).containsExactly("a", "b");
            assertThat(reopened.unappliedKeys()).containsExactly("b");
            IntegrityReport report = DocumentErasureLog.inspect(log(dir));
            assertThat(report.status()).isEqualTo(IntegrityStatus.INTACT);
            assertThat(report.detail()).contains("3 line(s)").contains("2 promise(s)");
        }

        @Test
        @DisplayName("refuses a promise whose key changed, rather than discharging it later")
        void changedKey(@TempDir Path dir) throws Exception {
            DocumentErasureLog.open(dir).append(List.of("chunk:17"), AT);
            writeLog(dir, Files.readString(log(dir)).replace("chunk:17", "chunk:71"));

            assertThatThrownBy(() -> DocumentErasureLog.open(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("line 2")
                .hasMessageContaining("checksum did not match");
            IntegrityReport report = DocumentErasureLog.inspect(log(dir));
            assertThat(report.status()).isEqualTo(IntegrityStatus.CORRUPT);
            assertThat(report.detail()).contains("checksum did not match");
        }

        @Test
        @DisplayName("refuses a confirmation that no longer matches its checksum")
        void changedConfirmation(@TempDir Path dir) throws Exception {
            DocumentErasureLog log = DocumentErasureLog.open(dir);
            log.append(List.of("a"), AT);
            log.confirmApplied(List.of("a"));
            writeLog(dir, Files.readString(log(dir)).replace("D\ta", "D\tb"));

            assertThatThrownBy(() -> DocumentErasureLog.open(dir))
                .hasMessageContaining("line 3")
                .hasMessageContaining("checksum did not match");
        }

        @Test
        @DisplayName("refuses a line cut short before its checksum")
        void truncatedLine(@TempDir Path dir) throws Exception {
            DocumentErasureLog.open(dir).append(List.of("a"), AT);
            String text = Files.readString(log(dir));
            writeLog(dir, text.substring(0, text.length() - 6));

            assertThatThrownBy(() -> DocumentErasureLog.open(dir))
                .hasMessageContaining("does not end with a checksum");
            assertThat(DocumentErasureLog.inspect(log(dir)).status())
                .isEqualTo(IntegrityStatus.CORRUPT);
        }

        @Test
        @DisplayName("reports an empty log as owing nothing")
        void empty(@TempDir Path dir) throws Exception {
            Files.write(log(dir), new byte[0]);

            assertThat(DocumentErasureLog.inspect(log(dir)).status())
                .isEqualTo(IntegrityStatus.INTACT);
        }
    }

    @Nested
    @DisplayName("from another build")
    class OtherBuilds {

        @Test
        @DisplayName("reads version 2 as it was written, and rewrites it before it grows")
        void versionTwoIsUpgraded(@TempDir Path dir) throws Exception {
            writeLog(dir, "excalibur-lexical-erasure v2\nP\t1767225600000\told\n"
                + "A\t1767225600000\tdone\n");

            IntegrityReport report = DocumentErasureLog.inspect(log(dir));
            assertThat(report.status()).isEqualTo(IntegrityStatus.LEGACY_UNPROTECTED);
            DocumentErasureLog log = DocumentErasureLog.open(dir);
            assertThat(log.unappliedKeys()).containsExactly("old");

            log.append(List.of("new"), AT);

            assertThat(Files.readAllLines(log(dir)).get(0))
                .isEqualTo("excalibur-lexical-erasure v3");
            DocumentErasureLog reopened = DocumentErasureLog.open(dir);
            assertThat(reopened.outstandingKeys()).containsExactly("old", "done", "new");
            assertThat(reopened.unappliedKeys()).containsExactly("old", "new");
            assertThat(DocumentErasureLog.inspect(log(dir)).status())
                .isEqualTo(IntegrityStatus.INTACT);
        }

        @Test
        @DisplayName("reports version 1 unprotected")
        void versionOneIsUnprotected(@TempDir Path dir) throws Exception {
            writeLog(dir, "excalibur-lexical-erasure v1\n1767225600000\told\n");

            assertThat(DocumentErasureLog.inspect(log(dir)).status())
                .isEqualTo(IntegrityStatus.LEGACY_UNPROTECTED);
        }

        @Test
        @DisplayName("refuses a newer build's log as unreadable, not damaged")
        void newerIsRefused(@TempDir Path dir) throws Exception {
            writeLog(dir, "excalibur-lexical-erasure v4\nwhatever it holds\n");

            assertThatThrownBy(() -> DocumentErasureLog.open(dir))
                .isInstanceOfSatisfying(DocumentErasureLog.UnsupportedVersionException.class,
                    e -> assertThat(e.version()).isEqualTo(4));
            IntegrityReport report = DocumentErasureLog.inspect(log(dir));
            assertThat(report.status()).isEqualTo(IntegrityStatus.UNREADABLE);
            assertThat(report.detail()).contains("version 4");
        }

        @Test
        @DisplayName("refuses a header naming a version no build writes")
        void versionZeroIsNotALog(@TempDir Path dir) throws Exception {
            writeLog(dir, "excalibur-lexical-erasure v0\n");

            assertThatThrownBy(() -> DocumentErasureLog.open(dir))
                .isNotInstanceOf(DocumentErasureLog.UnsupportedVersionException.class)
                .hasMessageContaining("Not an erasure log");
        }

        @Test
        @DisplayName("refuses a header whose version is not a number")
        void unparseableVersion(@TempDir Path dir) throws Exception {
            writeLog(dir, "excalibur-lexical-erasure vX\n");

            assertThatThrownBy(() -> DocumentErasureLog.open(dir))
                .hasMessageContaining("Not an erasure log");
        }
    }
}
