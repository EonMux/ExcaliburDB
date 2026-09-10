package io.excalibur.store;

import io.excalibur.store.catalog.CatalogFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests that a catalog which could not be closed durably says so.
 *
 * <p>The catalog is the durability record for every segment's lifecycle state. When its final
 * flush fails the on-disk metadata is stale, and a restart re-processes write-log ranges that
 * were already integrated. {@link CatalogFile} detects that and records it; these tests pin the
 * requirement that {@link SegmentCatalog} passes the signal on rather than dropping it, and that
 * it still finishes closing either way - a shutdown that stops half way through is worse than one
 * that completes and reports.</p>
 */
@DisplayName("SegmentCatalog close failure reporting")
class SegmentCatalogCloseFailureTest {

    @TempDir
    Path tempDir;

    /**
     * A catalog file whose close throws instead of completing.
     */
    private static final class ThrowingCatalogFile extends CatalogFile {

        private final RuntimeException failure;

        ThrowingCatalogFile(Path path, RuntimeException failure) throws IOException {
            super(path);
            this.failure = failure;
        }

        @Override
        public synchronized void close() {
            throw failure;
        }
    }

    /**
     * A catalog file that closes normally but could not make its last write durable.
     */
    private static final class UnflushedCatalogFile extends CatalogFile {

        private final Throwable failure;

        UnflushedCatalogFile(Path path, Throwable failure) throws IOException {
            super(path);
            this.failure = failure;
        }

        @Override
        public Throwable closeFailure() {
            return failure;
        }
    }

    @Test
    @DisplayName("a catalog that closed durably reports no failure")
    void cleanCloseReportsNoFailure() {
        SegmentCatalog catalog = new SegmentCatalog(tempDir.resolve("catalog.dat"));

        catalog.close();

        assertThat(catalog.closeFailure()).isNull();
    }

    @Test
    @DisplayName("an in-memory catalog has nothing to fail at")
    void inMemoryCloseReportsNoFailure() {
        SegmentCatalog catalog = new SegmentCatalog();

        catalog.close();

        assertThat(catalog.closeFailure()).isNull();
    }

    @Test
    @DisplayName("a file that could not be flushed durably is reported, not dropped")
    void unflushedFileIsReported() throws IOException {
        IOException diskFull = new IOException("No space left on device");
        SegmentCatalog catalog = new SegmentCatalog(
            new UnflushedCatalogFile(tempDir.resolve("catalog.dat"), diskFull));

        catalog.close();

        assertThat(catalog.closeFailure())
            .as("the catalog file recorded the failure; dropping it makes the shutdown look clean")
            .isSameAs(diskFull);
    }

    @Test
    @DisplayName("a close that throws is reported, and does not escape close()")
    void throwingCloseIsReportedWithoutEscaping() throws IOException {
        IllegalStateException failure = new IllegalStateException("catalog file already unmapped");
        SegmentCatalog catalog = new SegmentCatalog(
            new ThrowingCatalogFile(tempDir.resolve("catalog.dat"), failure));

        assertThatCode(catalog::close)
            .as("later shutdown steps still have to run")
            .doesNotThrowAnyException();
        assertThat(catalog.closeFailure()).isSameAs(failure);
    }
}
