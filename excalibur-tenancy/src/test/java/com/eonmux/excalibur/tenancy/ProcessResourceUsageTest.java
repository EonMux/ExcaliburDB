package com.eonmux.excalibur.tenancy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Measuring what the whole process holds, which the mapping budget counts against its limits.
 *
 * <p>The figures come from {@code /proc}-shaped sources, so each test builds a process of known
 * shape out of a file and a directory rather than depending on the shape of the JVM running it.</p>
 */
@DisplayName("Process resource usage")
class ProcessResourceUsageTest {

    @Test
    @DisplayName("one mapping is counted per line of the maps file")
    void countsMappingsByLine(@TempDir Path dir) throws IOException {
        Path maps = dir.resolve("maps");
        Files.writeString(maps, "a\nb\nc\n");

        ProcessResourceUsage usage =
            ProcessResourceUsage.fromProcFs(maps, dir.resolve("fd"), () -> -1);

        assertThat(usage.mappings()).hasValue(3);
    }

    @Test
    @DisplayName("a final line without a newline is still a mapping")
    void countsAnUnterminatedLastLine() throws IOException {
        assertThat(ProcessResourceUsage.ProcFs.countLines(stream("a\nb"))).isEqualTo(2);
        assertThat(ProcessResourceUsage.ProcFs.countLines(stream(""))).isZero();
    }

    @Test
    @DisplayName("a maps file longer than one read buffer is counted whole")
    void countsAcrossReadBuffers() throws IOException {
        // /proc generates the file as it is read, so it arrives in pieces; the count must not
        // depend on where a piece ends.
        String line = "7f0000000000-7f0000001000 r--p 00000000 00:00 0 /some/mapped/file\n";
        assertThat(ProcessResourceUsage.ProcFs.countLines(stream(line.repeat(5_000))))
            .isEqualTo(5_000);
    }

    @Test
    @DisplayName("one descriptor is counted per entry of the descriptor directory")
    void countsDescriptorsByEntry(@TempDir Path dir) throws IOException {
        Path fd = Files.createDirectory(dir.resolve("fd"));
        for (int i = 0; i < 4; i++) {
            Files.createFile(fd.resolve(String.valueOf(i)));
        }

        ProcessResourceUsage usage =
            ProcessResourceUsage.fromProcFs(dir.resolve("maps"), fd, () -> 99);

        assertThat(usage.descriptors()).hasValue(4);
    }

    @Test
    @DisplayName("an unreadable maps file is unknown, not zero")
    void missingMapsIsUnknown(@TempDir Path dir) {
        // A zero would tell the budget the rest of the node holds nothing, which is the
        // under-count the measurement exists to remove.
        ProcessResourceUsage usage =
            ProcessResourceUsage.fromProcFs(dir.resolve("absent"), dir, () -> -1);

        assertThat(usage.mappings()).isEmpty();
    }

    @Test
    @DisplayName("descriptors fall back to the JVM's own count where the directory is missing")
    void descriptorsFallBack(@TempDir Path dir) {
        ProcessResourceUsage usage =
            ProcessResourceUsage.fromProcFs(dir.resolve("maps"), dir.resolve("absent"), () -> 17);

        assertThat(usage.descriptors()).hasValue(17);
    }

    @Test
    @DisplayName("descriptors are unknown when neither source can answer")
    void descriptorsUnknownWithoutEitherSource(@TempDir Path dir) {
        ProcessResourceUsage usage =
            ProcessResourceUsage.fromProcFs(dir.resolve("maps"), dir.resolve("absent"), () -> -1);

        assertThat(usage.descriptors()).isEmpty();
    }

    @Test
    @DisplayName("the unmeasured process knows nothing and says so")
    void unmeasuredKnowsNothing() {
        ProcessResourceUsage usage = ProcessResourceUsage.unmeasured();

        assertThat(usage.mappings()).isEqualTo(OptionalLong.empty());
        assertThat(usage.descriptors()).isEqualTo(OptionalLong.empty());
        assertThat(usage).hasToString("unmeasured");
    }

    @Test
    @DisplayName("the running process is measured where /proc exists")
    void measuresThisProcess() {
        ProcessResourceUsage usage = ProcessResourceUsage.ofThisProcess();

        // A JVM maps its heap, its code cache and its libraries, and holds at least its standard
        // streams; on a platform without /proc the figure is simply unknown.
        if (Files.isReadable(Path.of("/proc/self/maps"))) {
            assertThat(usage.mappings()).isPresent();
            assertThat(usage.mappings().getAsLong()).isPositive();
        }
        usage.descriptors().ifPresent(count -> assertThat(count).isPositive());
        assertThat(usage.toString()).contains("/proc/self/maps");
    }

    @Test
    @DisplayName("a source is required for every figure")
    void sourcesAreRequired(@TempDir Path dir) {
        assertThatThrownBy(() -> ProcessResourceUsage.fromProcFs(null, dir, () -> 0))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ProcessResourceUsage.fromProcFs(dir, null, () -> 0))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ProcessResourceUsage.fromProcFs(dir, dir, null))
            .isInstanceOf(NullPointerException.class);
    }

    private static InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.US_ASCII));
    }
}
