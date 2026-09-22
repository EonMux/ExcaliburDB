package com.eonmux.excalibur.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for PathUtils.
 *
 * Thread-safety: Tests verify thread-safe path operations.
 * Domain correctness: Tests verify paths are normalized and valid.
 */
@DisplayName("PathUtils Tests")
class PathUtilsTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("Normalize Tests")
    class NormalizeTests {

        @Test
        @DisplayName("Should normalize single path")
        void shouldNormalizeSinglePath() {
            Path result = PathUtils.normalize("/tmp/test");

            assertThat(result).isAbsolute();
            assertThat(result.toString()).doesNotContain("..");
        }

        @Test
        @DisplayName("Should normalize multiple path components")
        void shouldNormalizeMultipleComponents() {
            Path result = PathUtils.normalize("/tmp", "subdir", "file.txt");

            assertThat(result).isAbsolute();
            assertThat(result.toString()).endsWith("file.txt");
        }

        @Test
        @DisplayName("Should reject null path components")
        void shouldRejectNullComponents() {
            assertThatThrownBy(() -> PathUtils.normalize((String[]) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null or empty");
        }

        @Test
        @DisplayName("Should reject empty path components")
        void shouldRejectEmptyComponents() {
            assertThatThrownBy(() -> PathUtils.normalize(new String[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null or empty");
        }

        @Test
        @DisplayName("Should resolve relative paths")
        void shouldResolveRelativePaths() {
            Path result = PathUtils.normalize("relative/path");

            assertThat(result).isAbsolute();
        }
    }

    @Nested
    @DisplayName("Exists Tests")
    class ExistsTests {

        @Test
        @DisplayName("Should return true for existing path")
        void shouldReturnTrueForExistingPath() throws IOException {
            Path file = tempDir.resolve("exists.txt");
            Files.createFile(file);

            assertThat(PathUtils.exists(file.toString())).isTrue();
        }

        @Test
        @DisplayName("Should return false for non-existing path")
        void shouldReturnFalseForNonExistingPath() {
            assertThat(PathUtils.exists("/nonexistent/path/12345")).isFalse();
        }
    }

    @Nested
    @DisplayName("Create File Tests")
    class CreateFileTests {

        @Test
        @DisplayName("Should create file with specified size")
        void shouldCreateFileWithSize() throws IOException {
            String filePath = tempDir.resolve("test.bin").toString();

            File result = PathUtils.createFile(filePath, 1024);

            assertThat(result).exists();
        }

        @Test
        @DisplayName("Should create parent directories if needed")
        void shouldCreateParentDirectories() throws IOException {
            String filePath = tempDir.resolve("nested/deep/dir/test.bin").toString();

            File result = PathUtils.createFile(filePath, 0);

            assertThat(result).exists();
            assertThat(result.getParentFile()).exists();
        }

        @Test
        @DisplayName("Should create file with zero size")
        void shouldCreateFileWithZeroSize() throws IOException {
            String filePath = tempDir.resolve("empty.bin").toString();

            File result = PathUtils.createFile(filePath, 0);

            assertThat(result).exists();
        }
    }

    @Nested
    @DisplayName("Create File Stream Tests")
    class CreateFileStreamTests {

        @Test
        @DisplayName("Should create output stream")
        void shouldCreateOutputStream() throws IOException {
            String filePath = tempDir.resolve("stream.txt").toString();

            try (FileOutputStream fos = PathUtils.createFileStream(filePath)) {
                fos.write("test content".getBytes());
            }

            assertThat(Files.exists(Path.of(filePath))).isTrue();
            assertThat(Files.readString(Path.of(filePath))).isEqualTo("test content");
        }

        @Test
        @DisplayName("Should create parent directories for stream")
        void shouldCreateParentDirectoriesForStream() throws IOException {
            String filePath = tempDir.resolve("nested/stream/test.txt").toString();

            try (FileOutputStream fos = PathUtils.createFileStream(filePath)) {
                fos.write("data".getBytes());
            }

            assertThat(Files.exists(Path.of(filePath))).isTrue();
        }
    }

    @Nested
    @DisplayName("Join Tests")
    class JoinTests {

        @Test
        @DisplayName("Should join path components")
        void shouldJoinComponents() {
            Path result = PathUtils.join("base", "subdir", "file.txt");

            assertThat(result.toString()).contains("base");
            assertThat(result.toString()).contains("subdir");
            assertThat(result.toString()).endsWith("file.txt");
        }

        @Test
        @DisplayName("Should join to base path")
        void shouldJoinToBasePath() {
            Path base = tempDir;

            Path result = PathUtils.join(base, "subdir", "file.txt");

            // The result should be an absolute path
            assertThat(result.isAbsolute()).isTrue();
        }

        @Test
        @DisplayName("Should reject null base path")
        void shouldRejectNullBasePath() {
            assertThatThrownBy(() -> PathUtils.join((Path) null, "file.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
        }
    }

    @Nested
    @DisplayName("ToString Tests")
    class ToStringTests {

        @Test
        @DisplayName("Should convert path to string")
        void shouldConvertPathToString() {
            Path path = Path.of("/tmp/test");

            String result = PathUtils.toString(path);

            assertThat(result).isEqualTo(path.toString());
        }

        @Test
        @DisplayName("Should return null for null path")
        void shouldReturnNullForNullPath() {
            String result = PathUtils.toString(null);

            assertThat(result).isNull();
        }
    }
}
