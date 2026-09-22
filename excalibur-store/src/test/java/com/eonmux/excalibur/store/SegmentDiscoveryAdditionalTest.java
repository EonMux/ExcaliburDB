package com.eonmux.excalibur.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional tests for SegmentDiscovery to improve coverage.
 */
@DisplayName("SegmentDiscovery Additional Tests")
class SegmentDiscoveryAdditionalTest {

    @TempDir
    Path tempDir;

    private SegmentDiscovery discovery;

    @BeforeEach
    void setUp() {
        discovery = new SegmentDiscovery(tempDir);
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should throw on null data directory")
        void shouldThrowOnNullDataDirectory() {
            assertThatThrownBy(() -> new SegmentDiscovery(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Data directory cannot be null");
        }

        @Test
        @DisplayName("Should store data directory")
        void shouldStoreDataDirectory() {
            assertThat(discovery.getDataDir()).isEqualTo(tempDir);
        }
    }

    @Nested
    @DisplayName("scanDirectory Tests")
    class ScanDirectoryTests {

        @Test
        @DisplayName("Should return empty list when directory is empty")
        void shouldReturnEmptyListWhenDirectoryIsEmpty() {
            List<SegmentDiscovery.DiscoveredSegment> segments = discovery.scanDirectory();

            assertThat(segments).isEmpty();
        }

        @Test
        @DisplayName("Should return empty list when directory does not exist")
        void shouldReturnEmptyListWhenDirectoryDoesNotExist() {
            SegmentDiscovery nonExistentDirDiscovery = new SegmentDiscovery(tempDir.resolve("nonexistent"));

            List<SegmentDiscovery.DiscoveredSegment> segments = nonExistentDirDiscovery.scanDirectory();

            assertThat(segments).isEmpty();
        }

        @Test
        @DisplayName("Should discover segment with valid file")
        void shouldDiscoverSegmentWithValidFile() throws IOException {
            Path segmentFile = tempDir.resolve("segment_0.dat");
            Files.write(segmentFile, new byte[1024 * 1024]); // 1MB file

            List<SegmentDiscovery.DiscoveredSegment> segments = discovery.scanDirectory();

            assertThat(segments).hasSize(1);
            assertThat(segments.get(0).segmentIndex()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("findWriteLogForSegment Tests")
    class FindWriteLogForSegmentTests {

        @Test
        @DisplayName("Should return null when WAL does not exist")
        void shouldReturnNullWhenWalDoesNotExist() {
            Path walPath = discovery.findWriteLogForSegment(0);

            assertThat(walPath).isNull();
        }

        @Test
        @DisplayName("Should return path when WAL exists")
        void shouldReturnPathWhenWalExists() throws IOException {
            Path walFile = tempDir.resolve("segment_0.wal");
            Files.write(walFile, new byte[100]);

            Path walPath = discovery.findWriteLogForSegment(0);

            assertThat(walPath).isEqualTo(walFile);
        }

        @Test
        @DisplayName("Should handle large segment index")
        void shouldHandleLargeSegmentIndex() throws IOException {
            Path walFile = tempDir.resolve("segment_999999.wal");
            Files.write(walFile, new byte[100]);

            Path walPath = discovery.findWriteLogForSegment(999999);

            assertThat(walPath).isEqualTo(walFile);
        }
    }

    @Nested
    @DisplayName("DiscoveredSegment Tests")
    class DiscoveredSegmentTests {

        @Test
        @DisplayName("Should create with valid parameters")
        void shouldCreateWithValidParameters() {
            Path segmentPath = tempDir.resolve("segment_0.dat");
            SegmentDiscovery.DiscoveredSegment segment = new SegmentDiscovery.DiscoveredSegment(
                0, segmentPath, 1024, null, 0
            );

            assertThat(segment.segmentIndex()).isEqualTo(0);
            assertThat(segment.path()).isEqualTo(segmentPath);
            assertThat(segment.fileSize()).isEqualTo(1024);
            assertThat(segment.walPath()).isNull();
            assertThat(segment.walSize()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should throw on negative segment index")
        void shouldThrowOnNegativeSegmentIndex() {
            Path segmentPath = tempDir.resolve("segment_0.dat");

            assertThatThrownBy(() -> new SegmentDiscovery.DiscoveredSegment(
                -1, segmentPath, 1024, null, 0
            ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Segment index cannot be negative");
        }

        @Test
        @DisplayName("Should throw on null path")
        void shouldThrowOnNullPath() {
            assertThatThrownBy(() -> new SegmentDiscovery.DiscoveredSegment(
                0, null, 1024, null, 0
            ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path cannot be null");
        }

        @Test
        @DisplayName("Should throw on negative file size")
        void shouldThrowOnNegativeFileSize() {
            Path segmentPath = tempDir.resolve("segment_0.dat");

            assertThatThrownBy(() -> new SegmentDiscovery.DiscoveredSegment(
                0, segmentPath, -1, null, 0
            ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File size cannot be negative");
        }

        @Test
        @DisplayName("Should have write log when WAL exists and has size")
        void shouldHaveWriteLogWhenWalExistsAndHasSize() {
            Path segmentPath = tempDir.resolve("segment_0.dat");
            Path walPath = tempDir.resolve("segment_0.wal");
            SegmentDiscovery.DiscoveredSegment segment = new SegmentDiscovery.DiscoveredSegment(
                0, segmentPath, 1024, walPath, 100
            );

            assertThat(segment.hasWriteLog()).isTrue();
        }

        @Test
        @DisplayName("Should not have write log when WAL is null")
        void shouldNotHaveWriteLogWhenWalIsNull() {
            Path segmentPath = tempDir.resolve("segment_0.dat");
            SegmentDiscovery.DiscoveredSegment segment = new SegmentDiscovery.DiscoveredSegment(
                0, segmentPath, 1024, null, 0
            );

            assertThat(segment.hasWriteLog()).isFalse();
        }

        @Test
        @DisplayName("Should not have write log when WAL size is 0")
        void shouldNotHaveWriteLogWhenWalSizeIs0() {
            Path segmentPath = tempDir.resolve("segment_0.dat");
            Path walPath = tempDir.resolve("segment_0.wal");
            SegmentDiscovery.DiscoveredSegment segment = new SegmentDiscovery.DiscoveredSegment(
                0, segmentPath, 1024, walPath, 0
            );

            assertThat(segment.hasWriteLog()).isFalse();
        }

        @Test
        @DisplayName("Should likely have entries when size > 1MB")
        void shouldLikelyHaveEntriesWhenSizeGreaterThan1MB() {
            Path segmentPath = tempDir.resolve("segment_0.dat");
            SegmentDiscovery.DiscoveredSegment segment = new SegmentDiscovery.DiscoveredSegment(
                0, segmentPath, 1024 * 1024 + 1, null, 0
            );

            assertThat(segment.likelyHasEntries()).isTrue();
        }

        @Test
        @DisplayName("Should not likely have entries when size <= 1MB")
        void shouldNotLikelyHaveEntriesWhenSizeLessOrEqual1MB() {
            Path segmentPath = tempDir.resolve("segment_0.dat");
            SegmentDiscovery.DiscoveredSegment segment = new SegmentDiscovery.DiscoveredSegment(
                0, segmentPath, 1024 * 1024, null, 0
            );

            assertThat(segment.likelyHasEntries()).isFalse();
        }

        @Test
        @DisplayName("Should generate toString")
        void shouldGenerateToString() {
            Path segmentPath = tempDir.resolve("segment_0.dat");
            SegmentDiscovery.DiscoveredSegment segment = new SegmentDiscovery.DiscoveredSegment(
                0, segmentPath, 1024, null, 0
            );

            String str = segment.toString();

            assertThat(str).contains("DiscoveredSegment");
            assertThat(str).contains("index=0");
            assertThat(str).contains("size=1024");
        }

        @Test
        @DisplayName("Should generate toString with WAL")
        void shouldGenerateToStringWithWal() {
            Path segmentPath = tempDir.resolve("segment_0.dat");
            Path walPath = tempDir.resolve("segment_0.wal");
            SegmentDiscovery.DiscoveredSegment segment = new SegmentDiscovery.DiscoveredSegment(
                0, segmentPath, 1024, walPath, 100
            );

            String str = segment.toString();

            assertThat(str).contains("walPath=");
            assertThat(str).contains("walSize=100");
        }
    }

    @Nested
    @DisplayName("Integration Tests")
    class IntegrationTests {

        @Test
        @DisplayName("Should discover segment with WAL")
        void shouldDiscoverSegmentWithWal() throws IOException {
            // Create segment file
            Path segmentFile = tempDir.resolve("segment_0.dat");
            Files.write(segmentFile, new byte[1024 * 1024]);

            // Create WAL file
            Path walFile = tempDir.resolve("segment_0.wal");
            Files.write(walFile, new byte[100]);

            List<SegmentDiscovery.DiscoveredSegment> segments = discovery.scanDirectory();

            assertThat(segments).hasSize(1);
            assertThat(segments.get(0).hasWriteLog()).isTrue();
            assertThat(segments.get(0).walPath()).isEqualTo(walFile);
            assertThat(segments.get(0).walSize()).isEqualTo(100);
        }

        @Test
        @DisplayName("Should discover multiple segments")
        void shouldDiscoverMultipleSegments() throws IOException {
            for (int i = 0; i < 5; i++) {
                Path segmentFile = tempDir.resolve("segment_" + i + ".dat");
                Files.write(segmentFile, new byte[1024 * 1024]);
            }

            List<SegmentDiscovery.DiscoveredSegment> segments = discovery.scanDirectory();

            assertThat(segments).hasSize(5);
        }

        @Test
        @DisplayName("Should ignore non-segment files")
        void shouldIgnoreNonSegmentFiles() throws IOException {
            // Create segment file
            Path segmentFile = tempDir.resolve("segment_0.dat");
            Files.write(segmentFile, new byte[1024 * 1024]);

            // Create non-segment files
            Files.write(tempDir.resolve("readme.txt"), new byte[10]);
            Files.write(tempDir.resolve("other.dat"), new byte[10]);
            Files.write(tempDir.resolve("segment_backup.dat"), new byte[10]);

            List<SegmentDiscovery.DiscoveredSegment> segments = discovery.scanDirectory();

            assertThat(segments).hasSize(1);
            assertThat(segments.get(0).segmentIndex()).isEqualTo(0);
        }
    }
}
