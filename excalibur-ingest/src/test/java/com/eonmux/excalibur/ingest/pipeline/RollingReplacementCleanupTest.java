package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.filter.CuckooFilter;
import com.eonmux.excalibur.ingest.processor.TextChunker;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.SegmentLifecycleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a rolling replacement retires when it cannot replace anything.
 *
 * <p>A hash-routed engine hands back a created segment that is not a write target, so the run
 * retires the entry it just made and re-indexes through the routing directory instead. That
 * fallback used to run inside the rolling replacement's own error handling: an ordinary reindex
 * that failed was then reported as a rolling replacement that broke, and the cleanup retired the
 * created entry a second time - a retirement recorded at a later epoch than the one the segment
 * actually left service at.</p>
 */
@DisplayName("Rolling replacement cleanup")
class RollingReplacementCleanupTest {

    @TempDir
    Path tempDir;

    private CountingCatalog catalog;
    private DocumentReindexer reindexer;

    @BeforeEach
    void setUp() {
        catalog = new CountingCatalog(tempDir.resolve("catalog"));
        DocumentRegistry registry = new DocumentRegistry();
        CuckooFilter filter = CuckooFilter.create(1024);
        VisibilityTracker visibility = new VisibilityTracker(catalog);
        UnwritableWriter writer = new UnwritableWriter();

        reindexer = new DocumentReindexer(
            new TextChunker(512, 64),
            null,
            new UpsertHandler(registry, filter, writer),
            registry,
            writer,
            new DocumentBatchPublisher(visibility, registry, filter, () -> { },
                new ReentrantLock()),
            catalog,
            new SegmentLifecycleManager(catalog),
            visibility);
    }

    @Test
    @DisplayName("the created segment is retired once when the fallback re-index fails")
    void retiresTheCreatedSegmentOnce() {
        assertThatThrownBy(() -> reindexer.reindexWithRollingReplacement(new BrokenSource(),
            tempDir))
            .as("the fallback is an ordinary reindex, and its failure is still a failure")
            .isInstanceOf(SourceException.class);

        assertThat(catalog.tombstones())
            .as("the entry was already retired before the fallback ran; retiring it again "
                + "records a second, later epoch for a retirement that already happened")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("a failed fallback is not reported as a failed rolling replacement")
    void doesNotRenameTheFallbackFailure() {
        assertThatThrownBy(() -> reindexer.reindexWithRollingReplacement(new BrokenSource(),
            tempDir))
            .as("no segment was being replaced by then, so blaming rolling replacement sends "
                + "an operator to look at segment lifecycle for a source that cannot be read")
            .hasMessageNotContaining("Rolling reindex failed");
    }

    /** Counts what the run asked it to retire. */
    private static final class CountingCatalog extends SegmentCatalog {

        private final AtomicInteger tombstones = new AtomicInteger();

        CountingCatalog(Path directory) {
            super(directory);
        }

        int tombstones() {
            return tombstones.get();
        }

        @Override
        public void tombstone(long segmentId, long visibilityEpoch) {
            tombstones.incrementAndGet();
            super.tombstone(segmentId, visibilityEpoch);
        }
    }

    /** Reports its target unwritable, exactly as a catalog-created segment is. */
    private static final class UnwritableWriter
            implements OffsetAwareStorageWriter, UpsertHandler.StorageAdapter {

        @Override
        public boolean canWriteTo(long segmentId) {
            return false;
        }

        @Override
        public long writeChunkWithOffset(String chunkId, String content, float[] embedding) {
            return 0L;
        }

        @Override
        public boolean delete(byte[] key) {
            return true;
        }
    }

    /** A source the fallback re-index cannot read at all. */
    private static final class BrokenSource implements Source {

        @Override
        public String getSourceId() {
            return "unreadable";
        }

        @Override
        public String getSourceType() {
            return "test";
        }

        @Override
        public Stream<String> crawl() throws SourceException {
            throw new SourceException("The source cannot be listed");
        }

        @Override
        public FetchedDocument fetch(String documentId) throws SourceException {
            throw new SourceException("The source cannot be read");
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    }
}
