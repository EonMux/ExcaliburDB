package com.eonmux.excalibur.api.retrieval;

import com.eonmux.excalibur.core.text.ChunkIdentifiers;
import com.eonmux.excalibur.ingest.registry.DocumentLocks;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTrackerAdapter;
import com.eonmux.excalibur.shard.VisibilityTracker;
import com.eonmux.excalibur.store.SegmentCatalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a chunk's text back never shows a document that is being rewritten.
 *
 * <p>Storage is rewritten in place, one chunk at a time, under the document's lock, and a search
 * result's text is read after the search has ranked it. The read is where a document can go in
 * flight between ranking and rendering, and where a page of chunks can straddle a rewrite.</p>
 */
@DisplayName("Visible chunk text")
class VisibleChunkTextTest {

    @TempDir
    Path dataDir;

    private final Map<String, String> storage = new ConcurrentHashMap<>();
    private DocumentVisibilityTracker tracker;
    private Function<String, String> text;

    @BeforeEach
    void setUp() {
        tracker = new DocumentVisibilityTracker(
            new DocumentRegistry(dataDir.resolve("document-registry")),
            new VisibilityTracker(new SegmentCatalog(dataDir.resolve("catalog"))));
        text = VisibleChunkText.over(storage::get, new DocumentVisibilityTrackerAdapter(tracker));
        storage.put(chunk("a.txt"), "alpha");
    }

    @Test
    @DisplayName("reads a document the tracker has no opinion about")
    void untrackedIsRead() {
        assertThat(text.apply(chunk("a.txt"))).isEqualTo("alpha");
    }

    @Test
    @DisplayName("reads a published document")
    void publishedIsRead() {
        tracker.registerDocument(key("a.txt"), Set.of(chunk("a.txt")), tracker.advanceEpoch());

        assertThat(text.apply(chunk("a.txt"))).isEqualTo("alpha");
    }

    @Test
    @DisplayName("withholds a document in flight, published before or not")
    void inFlightIsWithheld() {
        tracker.registerDocument(key("a.txt"), Set.of(chunk("a.txt")), tracker.advanceEpoch());
        tracker.beginIngestion(key("a.txt"), 1);

        assertThat(text.apply(chunk("a.txt"))).isNull();

        tracker.registerDocument(key("a.txt"), Set.of(chunk("a.txt")), tracker.advanceEpoch());
        assertThat(text.apply(chunk("a.txt"))).as("published again, it is read again")
            .isEqualTo("alpha");
    }

    @Test
    @DisplayName("withholds a document another thread is rewriting, before any mark is visible")
    void lockedByAWriterIsWithheld() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread writer = Thread.ofPlatform().start(() ->
            DocumentLocks.shared().inDocument(key("a.txt"), () -> {
                holding.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        try {
            assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

            assertThat(text.apply(chunk("a.txt"))).isNull();
        } finally {
            release.countDown();
            writer.join(TimeUnit.SECONDS.toMillis(30));
        }
        assertThat(text.apply(chunk("a.txt"))).as("the writer gone, it is read again")
            .isEqualTo("alpha");
    }

    @Test
    @DisplayName("reads for the writer itself, which already holds the document's lock")
    void writerReadsItsOwnDocument() {
        String read = DocumentLocks.shared().inDocument(key("a.txt"),
            () -> text.apply(chunk("a.txt")));

        assertThat(read).isEqualTo("alpha");
    }

    @Test
    @DisplayName("answers null for a chunk storage does not hold, and for no chunk at all")
    void absentIsNull() {
        assertThat(text.apply(chunk("missing.txt"))).isNull();
        assertThat(text.apply(null)).isNull();
    }

    @Test
    @DisplayName("is the stored text itself on a node that filters nothing")
    void noCheckerReadsThrough() {
        Function<String, String> source = storage::get;

        assertThat(VisibleChunkText.over(source, null)).isSameAs(source);
    }

    private static String key(String documentId) {
        return ChunkIdentifiers.documentKey("docs", documentId);
    }

    private static String chunk(String documentId) {
        return ChunkIdentifiers.chunkId("docs", documentId, 0);
    }
}
