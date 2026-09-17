package io.excalibur.store;

import io.excalibur.store.catalog.CatalogFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The durable half of {@link SegmentCatalog}: one record per segment, on disk.
 *
 * <p>The catalog is usable with no file behind it at all - tests and embedded uses build
 * one that way deliberately - so every operation here is a no-op when there is no
 * {@link CatalogFile}. Keeping that decision in one class is the point of separating it:
 * the in-memory registry never has to ask whether persistence is enabled, and there is one
 * null check per operation rather than one per call site.</p>
 *
 * <h2>Closing is reported, not thrown</h2>
 * <p>A close that does not reach the device is exactly the failure this class exists to
 * surface, and it is also the one that must not abort a shutdown: the steps that follow a
 * catalog close - releasing the segment mappings above all - still have to run, and an
 * arena left open holds those mappings for the life of the JVM. So {@link #close()} always
 * completes, records the first failure on {@link #closeFailure()} and logs it, and whoever
 * reports the shutdown's outcome reads it back. Both halves are checked: {@code close()}
 * throwing, and {@code close()} returning normally over a final flush that the file itself
 * knows did not land.</p>
 *
 * <p>Every other failure is thrown as an {@link UncheckedIOException}. A write that did not
 * persist means the on-disk metadata now disagrees with memory, and continuing would let
 * the next restart recover a catalog describing a state the node was never in.</p>
 *
 * <p><strong>Thread safety:</strong> thread-safe. {@link CatalogFile} synchronizes its own
 * writes, and {@link #closeFailure} is volatile because it is read by whichever thread is
 * winding the process down, which is not always the one that closed the catalog.</p>
 */
final class CatalogPersistence {

    /**
     * Persistent catalog file for durable storage.
     * May be null if persistence is not enabled (in-memory only mode).
     */
    private final CatalogFile catalogFile;

    /**
     * First failure seen while closing, or null when the catalog closed durably.
     *
     * <p>Volatile because {@link #closeFailure()} is read by whichever thread is winding the
     * process down, which is not always the one that closed the catalog.</p>
     */
    private volatile Throwable closeFailure;

    /**
     * Wraps an already-open catalog file, or nothing.
     *
     * @param catalogFile The file to persist metadata to, or null for in-memory only
     */
    CatalogPersistence(CatalogFile catalogFile) {
        this.catalogFile = catalogFile;
    }

    /**
     * Opens the catalog file at the given path.
     *
     * @param catalogPath Path to the catalog file
     * @return The open file
     * @throws UncheckedIOException if the file cannot be created or opened
     */
    static CatalogFile open(Path catalogPath) {
        try {
            return new CatalogFile(catalogPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open catalog file: " + catalogPath, e);
        }
    }

    /**
     * Checks if persistence is enabled.
     *
     * @return true if there is a backing file
     */
    boolean isPersistent() {
        return catalogFile != null;
    }

    /**
     * Reads back every persisted segment record.
     *
     * @return The persisted metadata, empty when persistence is not enabled
     * @throws UncheckedIOException if the catalog file cannot be read
     */
    List<SegmentCatalog.SegmentMetadata> readAll() {
        if (catalogFile == null) {
            return List.of();
        }
        try {
            return catalogFile.readAll();
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Failed to recover catalog from: " + catalogFile.getPath(), e);
        }
    }

    /**
     * Persists a single segment's metadata to disk.
     *
     * <p>Called after each in-memory update, so that the change is durable before the caller
     * is told it happened. Does nothing when persistence is not enabled.</p>
     *
     * @param metadata The metadata to persist
     * @throws UncheckedIOException if the record cannot be written
     */
    void persist(SegmentCatalog.SegmentMetadata metadata) {
        if (catalogFile == null || metadata == null) {
            return;
        }

        try {
            catalogFile.writeSegment(metadata);
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Failed to persist segment metadata: " + metadata.getSegmentId(), e);
        }
    }

    /**
     * Forces all metadata changes to the device.
     *
     * @throws UncheckedIOException if the flush fails
     */
    void flush() {
        if (catalogFile == null) {
            return;
        }

        try {
            catalogFile.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Failed to flush catalog file: " + catalogFile.getPath(), e);
        }
    }

    /**
     * Closes the catalog file, recording rather than throwing whatever went wrong.
     *
     * <p>See this class's own documentation for why a close-time failure is reported through
     * {@link #closeFailure()} instead of propagating.</p>
     */
    void close() {
        if (catalogFile == null) {
            return;
        }

        try {
            catalogFile.close();
        } catch (Exception e) {
            recordCloseFailure(e);
        }

        // The file records a final flush that did not reach the device even when close() itself
        // returns normally. That is the case this catalog exists to report.
        Throwable fileFailure = catalogFile.closeFailure();
        if (fileFailure != null) {
            recordCloseFailure(fileFailure);
        }
    }

    /**
     * Returns the failure that prevented the catalog from closing durably.
     *
     * @return The first close-time failure, or null when the close was clean
     */
    Throwable closeFailure() {
        return closeFailure;
    }

    /**
     * Logs a close-time failure and keeps the first one.
     *
     * @param failure The failure to record
     */
    private void recordCloseFailure(Throwable failure) {
        SegmentCatalog.logger.error("Catalog {} did not close durably; on-disk segment metadata may be stale",
            catalogFile.getPath(), failure);
        if (closeFailure == null) {
            closeFailure = failure;
        }
    }
}
