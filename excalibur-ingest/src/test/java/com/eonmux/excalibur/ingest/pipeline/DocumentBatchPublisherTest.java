package com.eonmux.excalibur.ingest.pipeline;

import com.eonmux.excalibur.ingest.processor.TextChunk;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker;
import com.eonmux.excalibur.store.VisibilityTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DocumentBatchPublisher}: one epoch per document, the registry entry written
 * with it, and a publication that fails leaving nothing registered.
 */
class DocumentBatchPublisherTest {

    private static final String KEY = "source1:doc1";

    private VisibilityTracker tracker;
    private DocumentRegistry registry;
    private DocumentBatchPublisher publisher;

    @BeforeEach
    void setUp() {
        tracker = new VisibilityTracker.Default();
        registry = new DocumentRegistry();
        publisher = new DocumentBatchPublisher(tracker, registry);
    }

    @Test
    void publishAdvancesTheEpochOnceAndRegistersTheDocument() {
        DocumentBatchBuffer buffer = createBuffer("doc1", 3);
        long before = tracker.currentEpoch();

        DocumentBatchPublisher.PublicationResult result =
            publisher.publish(buffer, createMetadata("doc1", buffer.getChunkIds()));

        assertEquals(before + 1, result.epoch());
        assertEquals(result.epoch(), tracker.currentEpoch());
        assertEquals(result.epoch(), publisher.currentEpoch());
        assertEquals(3, result.chunkCount());
        assertTrue(buffer.isPublished());
        DocumentMetadata registered = registry.get("source1", "doc1");
        assertNotNull(registered);
        assertEquals(buffer.getChunkIds(), registered.getChunkIds());
        assertSame(registry, publisher.getDocumentRegistry());
    }

    @Test
    void republishingADocumentMakesItVisibleAtALaterEpoch() {
        long first = publisher.publish(createBuffer("doc1", 1), createMetadata("doc1", List.of()))
            .epoch();
        long second = publisher.publish(createBuffer("doc1", 2), createMetadata("doc1", List.of()))
            .epoch();

        assertTrue(second > first);
        assertEquals(1, registry.size("source1"));
    }

    @Test
    void anEmptyBufferIsPublishedWithoutAdvancingTheEpoch() {
        DocumentBatchBuffer empty = new DocumentBatchBuffer("empty", "source1");
        empty.seal();
        long before = tracker.currentEpoch();

        DocumentBatchPublisher.PublicationResult result =
            publisher.publish(empty, createMetadata("empty", List.of()));

        assertEquals(before, result.epoch());
        assertEquals(0, result.chunkCount());
        assertTrue(empty.isPublished());
        assertNull(registry.get("source1", "empty"));
    }

    @Test
    void anUnsealedBufferIsRefused() {
        DocumentBatchBuffer open = new DocumentBatchBuffer("doc1", "source1");
        open.addChunk("doc1:0", createChunk("doc1", 0), 100);

        assertThrows(IllegalStateException.class,
            () -> publisher.publish(open, createMetadata("doc1", List.of())));
        assertEquals(0, tracker.currentEpoch());
        assertNull(registry.get("source1", "doc1"));
    }

    @Test
    void nullArgumentsAreRejected() {
        DocumentBatchBuffer buffer = createBuffer("doc1", 1);
        DocumentMetadata metadata = createMetadata("doc1", List.of());

        assertThrows(NullPointerException.class, () -> publisher.publish(null, metadata));
        assertThrows(NullPointerException.class, () -> publisher.publish(buffer, null));
        assertThrows(NullPointerException.class, () -> new DocumentBatchPublisher(null, registry));
        assertThrows(NullPointerException.class, () -> new DocumentBatchPublisher(tracker, null));
    }

    @Test
    void aFailedPublicationMarksTheBufferFailedAndRegistersNothing() {
        VisibilityTracker refusing = new VisibilityTracker.Default() {
            @Override
            public long advanceEpoch() {
                throw new IllegalStateException("epoch cannot advance");
            }
        };
        DocumentBatchPublisher failing = new DocumentBatchPublisher(refusing, registry);
        DocumentBatchBuffer buffer = createBuffer("doc1", 2);

        DocumentBatchPublisher.PublicationException e = assertThrows(
            DocumentBatchPublisher.PublicationException.class,
            () -> failing.publish(buffer, createMetadata("doc1", buffer.getChunkIds())));

        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertTrue(buffer.isFailed());
        assertSame(e.getCause(), buffer.getFailureCause());
        assertNull(registry.get("source1", "doc1"));
    }

    @Test
    void concurrentPublicationsEachGetTheirOwnEpoch() throws Exception {
        int numThreads = 5;
        int docsPerThread = 10;
        Set<Long> epochs = ConcurrentHashMap.newKeySet();
        Thread[] threads = new Thread[numThreads];

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < docsPerThread; i++) {
                    String docId = "doc" + threadId + "_" + i;
                    epochs.add(publisher.publish(createBuffer(docId, 2),
                        createMetadata(docId, List.of())).epoch());
                }
            });
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        int total = numThreads * docsPerThread;
        assertEquals(total, epochs.size(), "every document is published at an epoch of its own");
        assertEquals(total, tracker.currentEpoch());
        assertEquals(total, registry.size("source1"));
    }

    @Test
    void publishingThroughTheDocumentTrackerRegistersTheDocumentAtItsEpoch() {
        DocumentVisibilityTracker documents = new DocumentVisibilityTracker(registry, tracker);
        DocumentBatchPublisher tracked = new DocumentBatchPublisher(documents);
        DocumentBatchBuffer buffer = createBuffer("doc1", 2);

        tracked.begin("source1", "doc1", buffer.getChunkIds());
        assertTrue(documents.isDocumentTracked(KEY));
        assertFalse(documents.isDocumentFullyVisible(KEY, Long.MAX_VALUE),
            "a document is hidden from the moment it is marked in flight");

        long epoch = tracked.publish(buffer, createMetadata("doc1", buffer.getChunkIds())).epoch();

        assertEquals(epoch, tracker.currentEpoch(), "published on the node's clock");
        assertEquals(epoch, documents.getCommitEpoch(KEY));
        assertTrue(documents.isDocumentFullyVisible(KEY, epoch));
        assertFalse(documents.isDocumentFullyVisible(KEY, epoch - 1));
        assertEquals(0, documents.getPendingDocumentCount());
        assertSame(registry, tracked.getDocumentRegistry());
        assertNotNull(registry.get("source1", "doc1"));
    }

    @Test
    void anAbandonedDocumentIsReleasedAndAForgottenOneIsNoLongerTracked() {
        DocumentVisibilityTracker documents = new DocumentVisibilityTracker(registry, tracker);
        DocumentBatchPublisher tracked = new DocumentBatchPublisher(documents);

        tracked.begin("source1", "doc1", List.of(KEY + ":chunk:0"));
        tracked.abandon("source1", "doc1");
        assertFalse(documents.isDocumentTracked(KEY));

        tracked.publish(createBuffer("doc1", 1), createMetadata("doc1", List.of()));
        assertTrue(documents.isDocumentTracked(KEY));
        tracked.forget("source1", "doc1");
        assertFalse(documents.isDocumentTracked(KEY));
    }

    @Test
    void withoutADocumentTrackerTheMarksAreNoOps() {
        publisher.begin("source1", "doc1", List.of(KEY + ":chunk:0"));
        publisher.abandon("source1", "doc1");
        publisher.forget("source1", "doc1");

        assertEquals(1, publisher.publish(createBuffer("doc1", 1),
            createMetadata("doc1", List.of())).chunkCount());
        assertThrows(NullPointerException.class,
            () -> new DocumentBatchPublisher((DocumentVisibilityTracker) null));
    }

    private static TextChunk createChunk(String documentId, int chunkIndex) {
        return new TextChunk(
            documentId,
            "content for " + documentId + " chunk " + chunkIndex,
            10,
            0,
            10,
            chunkIndex
        );
    }

    private static DocumentBatchBuffer createBuffer(String documentId, int chunkCount) {
        DocumentBatchBuffer buffer = new DocumentBatchBuffer(documentId, "source1");
        for (int i = 0; i < chunkCount; i++) {
            buffer.addChunk(documentId + ":" + i, createChunk(documentId, i), 100 + i);
        }
        buffer.seal();
        return buffer;
    }

    private static DocumentMetadata createMetadata(String documentId, List<String> chunkIds) {
        return DocumentMetadata.builder()
            .documentId(documentId)
            .sourceId("source1")
            .chunkIds(chunkIds)
            .build();
    }
}
