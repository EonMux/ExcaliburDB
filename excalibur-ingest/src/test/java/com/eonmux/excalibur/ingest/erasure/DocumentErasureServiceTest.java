package com.eonmux.excalibur.ingest.erasure;

import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lexical.NativeChunkIndex;
import com.eonmux.excalibur.ingest.registry.DocumentLocks;
import com.eonmux.excalibur.ingest.registry.DocumentMetadata;
import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.UpsertHandler;
import com.eonmux.excalibur.retrieval.expand.QueryExpander;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionPlanner;
import com.eonmux.excalibur.retrieval.rank.RetrievalQuery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Removing a document because someone asked, rather than because its source stopped listing it.
 *
 * <p>The property that matters is not that the document stops being returned - hiding is the easy
 * half. It is that the content leaves the places it was written to, and that the reply says which
 * of those actually happened rather than reporting the request as accepted and leaving the caller
 * to assume the rest.
 */
@DisplayName("Document erasure")
class DocumentErasureServiceTest {

    @TempDir
    Path tempDir;

    /** Chunk text, standing in for the storage engine: the erasure has to empty this. */
    private final Map<String, String> storage = new LinkedHashMap<>();
    private DocumentRegistry registry;
    private NativeChunkIndex index;
    private final AtomicLong epoch = new AtomicLong(100);

    /** A policy that always fires, so a forced compaction can be told from a due one. */
    private static CompactionPlanner eager() {
        return new CompactionPlanner((stats, now) -> true, 0.2, Duration.ofHours(1), 16, 8);
    }

    /** A policy that never fires, so nothing reclaims unless the caller asks for it. */
    private static CompactionPlanner never() {
        return new CompactionPlanner((stats, now) -> false, 1.0, Duration.ofDays(3650), 4096, 8);
    }

    private final UpsertHandler.StorageAdapter adapter =
        key -> storage.remove(new String(key, StandardCharsets.UTF_8)) != null;

    @BeforeEach
    void setUp() {
        registry = new DocumentRegistry();
    }

    @AfterEach
    void tearDown() {
        if (index != null) {
            index.close();
            index = null;
        }
    }

    private NativeChunkIndex open(CompactionPlanner planner) throws IOException {
        index = NativeChunkIndex.open(tempDir.resolve("chunk-index"), epoch::get, 0, planner);
        return index;
    }

    /** Writes a document's chunks into storage, the index and the registry, as ingestion would. */
    private void ingest(String sourceId, String documentId, String... texts) throws IOException {
        DocumentMetadata.Builder metadata = DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .contentHash(documentId.hashCode())
            .contentLength(100);
        for (int i = 0; i < texts.length; i++) {
            String chunkId = sourceId + ":" + documentId + ":chunk:" + i;
            storage.put(chunkId, texts[i]);
            metadata.addChunkId(chunkId);
            if (index != null) {
                index.add(chunkId, texts[i]);
            }
        }
        registry.register(metadata.build());
    }

    private DocumentErasureService service() {
        return new DocumentErasureService(registry, adapter, index, storage::get);
    }

    private int hits(String term) {
        return index.ranker(QueryExpander.identity())
            .rank(RetrievalQuery.ofText(term), 100).documents().size();
    }

    private boolean anyIndexFileContains(String needle) throws IOException {
        Path dir = tempDir.resolve("chunk-index");
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nested
    @DisplayName("What it tells a caller it erased")
    class Reporting {

        @Test
        @DisplayName("names the documents it erased, not only how many")
        void namesWhatItErased() throws IOException {
            // A count cannot be joined to anything. The facts a document contributed to the
            // graph are addressed by a provenance id derived from its key, so a caller that has
            // to withdraw the graph half of an erasure needs the keys this actually removed -
            // which for a source-scoped request it never knew, because the node chose the batch.
            ingest("docs", "a.md", "alpha content");
            ingest("docs", "b.md", "beta content");

            DocumentErasureService.Outcome outcome =
                service().eraseSource("docs", 10, false);

            assertThat(outcome.documentsErased()).isEqualTo(2);
            assertThat(outcome.erasedDocumentIds())
                .containsExactlyInAnyOrder("a.md", "b.md");
        }

        @Test
        @DisplayName("does not name a document it could not finish erasing")
        void doesNotNameAnUnfinishedDocument() throws IOException {
            ingest("docs", "a.md", "alpha content");
            DocumentErasureService refusing = new DocumentErasureService(
                registry, key -> {
                    throw new IllegalStateException("storage is down");
                }, null, null);

            DocumentErasureService.Outcome outcome =
                refusing.erase("docs", List.of("a.md"), false);

            assertThat(outcome.documentsIncomplete()).isEqualTo(1);
            assertThat(outcome.erasedDocumentIds())
                .as("naming it would have the graph half withdrawn for content still here")
                .isEmpty();
        }

        @Test
        @DisplayName("says when the oldest reclamation the index still owes was accepted, and "
            + "nothing once it is done")
        void reportsTheOldestOutstandingReclamation() throws IOException {
            open(never());
            ingest("docs", "a.md", "alpha content");
            ingest("docs", "b.md", "beta content");
            DocumentErasureService service = service();

            assertThat(service.oldestOutstandingErasure()).isEmpty();
            service.erase("docs", List.of("a.md"), false);
            assertThat(service.oldestOutstandingErasure()).isPresent();
            service.erase("docs", List.of("b.md"), true);
            assertThat(service.oldestOutstandingErasure()).isEmpty();
            assertThat(new DocumentErasureService(registry, adapter, null, storage::get)
                .oldestOutstandingErasure()).isEmpty();
        }

        @Test
        @DisplayName("does not name a document this node never held")
        void doesNotNameAnAbsentDocument() {
            DocumentErasureService.Outcome outcome =
                service().erase("docs", List.of("missing.md"), false);

            assertThat(outcome.documentsNotFound()).isEqualTo(1);
            assertThat(outcome.erasedDocumentIds()).isEmpty();
        }
    }

    @Nested
    @DisplayName("What it removes")
    class Removal {

        @Test
        @DisplayName("the document's chunks leave storage, the index and the registry")
        void erasesEverywhere() throws Exception {
            open(never());
            ingest("docs", "secret.md", "confidential incident postmortem", "second half");
            ingest("docs", "keep.md", "routine capacity planning");
            index.refresh();
            epoch.incrementAndGet();

            DocumentErasureService.Outcome outcome =
                service().erase("docs", List.of("secret.md"), false);

            assertThat(outcome.documentsErased()).isEqualTo(1);
            assertThat(outcome.chunksTombstoned()).isEqualTo(2);
            assertThat(outcome.chunksUnindexed()).isEqualTo(2);
            assertThat(storage).containsOnlyKeys("docs:keep.md:chunk:0");
            assertThat(registry.contains("docs", "secret.md")).isFalse();
            assertThat(registry.contains("docs", "keep.md"))
                .as("erasing one document must not take its neighbours with it")
                .isTrue();
            assertThat(hits("postmortem")).isZero();
            assertThat(hits("capacity")).isEqualTo(1);
        }

        @Test
        @DisplayName("a document this node never held is reported, not assumed removed")
        void reportsWhatItNeverHad() throws Exception {
            open(never());
            ingest("docs", "present.md", "alpha");
            index.refresh();
            epoch.incrementAndGet();

            DocumentErasureService.Outcome outcome =
                service().erase("docs", List.of("present.md", "absent.md"), false);

            assertThat(outcome.documentsErased()).isEqualTo(1);
            assertThat(outcome.documentsNotFound())
                .as("a caller under an obligation has to be told this, not left to assume")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("a chunk still in the run's buffer is erased with the rest")
        void erasesUnpublishedChunks() throws Exception {
            // Never refreshed: the chunks are written but not searchable yet. An erasure that
            // could not see them would report the document gone and then publish it.
            open(never());
            ingest("docs", "secret.md", "confidential incident postmortem");

            DocumentErasureService.Outcome outcome =
                service().erase("docs", List.of("secret.md"), false);

            assertThat(outcome.chunksUnindexed()).isEqualTo(1);
            epoch.incrementAndGet();
            assertThat(hits("postmortem")).isZero();
        }

        @Test
        @DisplayName("erasing the same document twice is not an error")
        void isIdempotent() throws Exception {
            open(never());
            ingest("docs", "secret.md", "confidential");
            index.refresh();
            epoch.incrementAndGet();
            service().erase("docs", List.of("secret.md"), false);

            DocumentErasureService.Outcome again =
                service().erase("docs", List.of("secret.md"), false);

            assertThat(again.documentsErased()).isZero();
            assertThat(again.documentsNotFound()).isEqualTo(1);
        }

        @Test
        @DisplayName("a repeated name is one document's worth of work")
        void deduplicatesNames() throws Exception {
            open(never());
            ingest("docs", "secret.md", "confidential");
            index.refresh();
            epoch.incrementAndGet();

            DocumentErasureService.Outcome outcome =
                service().erase("docs", List.of("secret.md", "secret.md"), false);

            assertThat(outcome.documentsErased()).isEqualTo(1);
            assertThat(outcome.chunksTombstoned())
                .as("the second mention must not count the chunks a second time")
                .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Erasing a whole source")
    class SourceScope {

        @Test
        @DisplayName("every document of the source goes, without the caller naming any")
        void erasesTheWholeSource() throws Exception {
            // The usual shape of an obligation about a person rather than a file: the caller has
            // no list, and the node is the only thing that has one.
            open(never());
            ingest("docs", "a.md", "alpha");
            ingest("docs", "b.md", "beta");
            ingest("other", "c.md", "gamma");
            index.refresh();
            epoch.incrementAndGet();

            DocumentErasureService.Outcome outcome = service().eraseSource("docs", 100, false);

            assertThat(outcome.documentsErased()).isEqualTo(2);
            assertThat(outcome.documentsRemaining()).isZero();
            assertThat(storage)
                .as("another source's documents are not part of this one")
                .containsOnlyKeys("other:c.md:chunk:0");
            assertThat(hits("alpha")).isZero();
            assertThat(hits("gamma")).isEqualTo(1);
        }

        @Test
        @DisplayName("a bounded request says how much of the source is left")
        void reportsWhatIsLeft() throws Exception {
            open(never());
            for (int i = 0; i < 5; i++) {
                ingest("docs", "doc" + i + ".md", "shared text " + i);
            }
            index.refresh();
            epoch.incrementAndGet();

            DocumentErasureService.Outcome first = service().eraseSource("docs", 2, false);

            assertThat(first.documentsErased()).isEqualTo(2);
            assertThat(first.documentsRemaining())
                .as("a caller looping over a large source needs to know when to stop")
                .isEqualTo(3);
        }

        @Test
        @DisplayName("repeating it finishes the source")
        void repeatingFinishes() throws Exception {
            open(never());
            for (int i = 0; i < 5; i++) {
                ingest("docs", "doc" + i + ".md", "shared text " + i);
            }
            index.refresh();
            epoch.incrementAndGet();

            DocumentErasureService.Outcome outcome;
            int calls = 0;
            do {
                outcome = service().eraseSource("docs", 2, false);
                calls++;
            } while (outcome.documentsRemaining() > 0 && calls < 10);

            assertThat(outcome.documentsRemaining()).isZero();
            assertThat(calls).isEqualTo(3);
            assertThat(storage).isEmpty();
        }

        @Test
        @DisplayName("a source this node holds nothing of is not an error")
        void anEmptySourceIsNotAnError() throws Exception {
            open(never());

            DocumentErasureService.Outcome outcome = service().eraseSource("nothing", 100, false);

            assertThat(outcome.documentsErased()).isZero();
            assertThat(outcome.documentsRemaining()).isZero();
        }

        @Test
        @DisplayName("a limit that would do no work is refused rather than looping forever")
        void limitMustBePositive() throws Exception {
            open(never());

            assertThatThrownBy(() -> service().eraseSource("docs", 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be positive");
        }
    }

    @Nested
    @DisplayName("Reclaiming it from disk")
    class Reclamation {

        @Test
        @DisplayName("an erasure the policy declines is reported as still outstanding")
        void hidingIsNotRemoving() throws Exception {
            open(never());
            ingest("docs", "secret.md", "zzconfidentialmarker");
            index.refresh();
            epoch.incrementAndGet();

            DocumentErasureService.Outcome outcome =
                service().erase("docs", List.of("secret.md"), false);

            assertThat(outcome.compacted()).isFalse();
            assertThat(outcome.outstanding())
                .as("the promise is not kept yet, and the reply must not imply it is")
                .isEqualTo(1);
            assertThat(anyIndexFileContains("zzconfidentialmarker"))
                .as("hidden from queries, still on disk")
                .isTrue();
        }

        @Test
        @DisplayName("compact:true takes it off disk before the reply")
        void forcedCompactionRemovesItNow() throws Exception {
            open(never());
            ingest("docs", "secret.md", "zzconfidentialmarker");
            ingest("docs", "keep.md", "routine capacity planning");
            index.refresh();
            epoch.incrementAndGet();

            DocumentErasureService.Outcome outcome =
                service().erase("docs", List.of("secret.md"), true);

            assertThat(outcome.compacted())
                .as("the policy would have declined; the caller asked anyway")
                .isTrue();
            assertThat(outcome.chunksReclaimed()).isEqualTo(1);
            assertThat(outcome.outstanding()).isZero();
            assertThat(anyIndexFileContains("zzconfidentialmarker")).isFalse();
            assertThat(hits("capacity"))
                .as("the surviving document is rewritten, not lost with it")
                .isEqualTo(1);
        }

        @Test
        @DisplayName("a forced compaction that abandoned the group holding the chunks is not "
            + "reported as having taken them off disk")
        void anAbandonedRewriteIsNotCompacted() throws Exception {
            open(never());
            ingest("docs", "secret.md", "zzconfidentialmarker");
            ingest("docs", "keep.md", "routine capacity planning");
            index.refresh();
            epoch.incrementAndGet();

            // The surviving document's text cannot be read back, so the rewrite of the segment
            // holding both is abandoned and the erased chunk stays where it is.
            DocumentErasureService unreadable = new DocumentErasureService(registry, adapter,
                index, key -> {
                    throw new java.io.UncheckedIOException(new IOException("device busy"));
                });

            DocumentErasureService.Outcome outcome =
                unreadable.erase("docs", List.of("secret.md"), true);

            assertThat(outcome.compacted()).isFalse();
            assertThat(outcome.outstanding()).isEqualTo(1);
            assertThat(outcome.detail()).contains("abandoned");
            assertThat(anyIndexFileContains("zzconfidentialmarker")).isTrue();
        }

        @Test
        @DisplayName("once asked to stop, a forced compaction ends without publishing and the "
            + "erasure stays owed")
        void aStoppedServiceLeavesTheErasureOwed() throws Exception {
            open(never());
            ingest("docs", "secret.md", "zzconfidentialmarker");
            ingest("docs", "keep.md", "routine capacity planning");
            index.refresh();
            epoch.incrementAndGet();
            DocumentErasureService service = service();

            service.requestStop();
            DocumentErasureService.Outcome outcome =
                service.erase("docs", List.of("secret.md"), true);

            assertThat(outcome.documentsErased())
                .as("hiding and removing are not what a shutdown cuts short")
                .isEqualTo(1);
            assertThat(outcome.compacted()).isFalse();
            assertThat(outcome.outstanding()).isEqualTo(1);
            assertThat(outcome.detail()).startsWith("stopped:");
        }

        @Test
        @DisplayName("a due compaction reclaims without being asked")
        void dueCompactionRunsOnItsOwn() throws Exception {
            open(eager());
            ingest("docs", "secret.md", "zzconfidentialmarker");
            ingest("docs", "keep.md", "routine capacity planning");
            index.refresh();
            epoch.incrementAndGet();

            DocumentErasureService.Outcome outcome =
                service().erase("docs", List.of("secret.md"), false);

            assertThat(outcome.compacted()).isTrue();
            assertThat(outcome.outstanding()).isZero();
            assertThat(anyIndexFileContains("zzconfidentialmarker")).isFalse();
        }
    }

    @Nested
    @DisplayName("Visibility state")
    class Visibility {

        @Test
        @DisplayName("an erased document is taken out of visibility tracking too")
        void clearsVisibilityState() throws Exception {
            // An entry for a document whose chunks are gone describes nothing. It cannot bring an
            // erased chunk back - a result's text is resolved from storage - but it is state the
            // erasure should take with it rather than leave to accumulate.
            open(never());
            com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker tracker =
                new com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker(registry);
            ingest("docs", "secret.md", "confidential");
            index.refresh();
            epoch.incrementAndGet();
            tracker.registerFromMetadata(registry.get("docs", "secret.md"), 1);
            assertThat(tracker.isDocumentTracked("docs:secret.md"))
                .as("precondition: publication recorded it")
                .isTrue();

            new DocumentErasureService(registry, adapter, index, storage::get, tracker)
                .erase("docs", List.of("secret.md"), false);

            assertThat(tracker.isDocumentTracked("docs:secret.md"))
                .as("a document that is gone is not one this node has anything to say about")
                .isFalse();
            assertThat(tracker.getDocumentCount()).isZero();
        }

        @Test
        @DisplayName("a node that tracks no visibility erases just the same")
        void trackerIsOptional() throws Exception {
            open(never());
            ingest("docs", "secret.md", "confidential");
            index.refresh();
            epoch.incrementAndGet();

            assertThat(service().erase("docs", List.of("secret.md"), false).documentsErased())
                .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Across a restart")
    class Restart {

        /** A registry on disk, which is what a node has after a restart. */
        private DocumentRegistry persisted() {
            return new DocumentRegistry(tempDir.resolve("document-registry"));
        }

        @Test
        @DisplayName("a document ingested before a restart can still be erased after one")
        void erasesWhatAnEarlierRunRecorded() throws Exception {
            // The registry is the only record of which chunk ids a document was written under.
            // Held in memory alone, an erasure request after a restart reports every document as
            // one this node never held, while its chunks sit in storage.
            registry = persisted();
            open(never());
            ingest("docs", "secret.md", "confidential incident postmortem");
            index.refresh();
            epoch.incrementAndGet();
            registry.persist();

            registry = persisted();
            DocumentErasureService.Outcome outcome =
                service().erase("docs", List.of("secret.md"), false);

            assertThat(outcome.documentsErased()).isEqualTo(1);
            assertThat(outcome.chunksTombstoned()).isEqualTo(1);
            assertThat(storage).isEmpty();
        }

        @Test
        @DisplayName("an erasure is written down, so the document does not come back")
        void erasureIsRecorded() throws Exception {
            registry = persisted();
            open(never());
            ingest("docs", "secret.md", "confidential");
            ingest("docs", "keep.md", "routine");
            index.refresh();
            epoch.incrementAndGet();
            registry.persist();

            service().erase("docs", List.of("secret.md"), false);

            DocumentRegistry afterRestart = persisted();
            assertThat(afterRestart.contains("docs", "secret.md"))
                .as("the erasure has to be written down, not only applied in memory")
                .isFalse();
            assertThat(afterRestart.contains("docs", "keep.md")).isTrue();
        }
    }

    @Nested
    @DisplayName("Nodes that are not fully configured")
    class Partial {

        @Test
        @DisplayName("a node with no lexical index still erases from storage")
        void worksWithoutAnIndex() throws Exception {
            ingest("docs", "secret.md", "confidential");
            DocumentErasureService service =
                new DocumentErasureService(registry, adapter, null, null);

            DocumentErasureService.Outcome outcome =
                service.erase("docs", List.of("secret.md"), true);

            assertThat(outcome.documentsErased()).isEqualTo(1);
            assertThat(outcome.chunksTombstoned()).isEqualTo(1);
            assertThat(outcome.chunksUnindexed()).isZero();
            assertThat(outcome.compacted()).isFalse();
            assertThat(outcome.detail()).contains("no lexical index");
            assertThat(storage).isEmpty();
        }

        @Test
        @DisplayName("an index without a way to read text back is refused at construction")
        void aTextSupplierIsRequired() throws Exception {
            open(never());

            assertThatThrownBy(() -> new DocumentErasureService(registry, adapter, index, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never reclaimed");
        }

        @Test
        @DisplayName("the collaborators it cannot work without are refused")
        void requiredCollaborators() {
            assertThatThrownBy(() -> new DocumentErasureService(null, adapter, null, null))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new DocumentErasureService(registry, null, null, null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("The vector index")
    class Vectors {

        private RecordingVectorEraser eraser;

        private DocumentErasureService.VectorEraser recording(int perDocument) {
            eraser = new RecordingVectorEraser(perDocument);
            return eraser;
        }

        private DocumentErasureService withEraser(DocumentErasureService.VectorEraser eraser) {
            return new DocumentErasureService(registry, adapter, index, storage::get, null, eraser);
        }

        @Test
        @DisplayName("the document's vectors are retired, named the way nodes are attributed")
        void retiresTheDocumentsVectors() throws Exception {
            // A node carries no text, so an erased document's vectors look like harmless residue.
            // They are not: a hit names its document and its distance, which says that document
            // matched the query - the one fact the erasure was asked to remove.
            open(never());
            ingest("docs", "secret.md", "confidential incident postmortem", "second half");
            index.refresh();
            epoch.incrementAndGet();

            DocumentErasureService.Outcome outcome =
                withEraser(recording(2)).erase("docs", List.of("secret.md"), false);

            assertThat(eraser.documents())
                .as("nodes are attributed by source and document, and must be asked for that way")
                .containsExactly("docs:secret.md");
            assertThat(outcome.vectorsHidden()).isEqualTo(2);
        }

        @Test
        @DisplayName("erasing a whole source reaches the vectors of every document in the batch")
        void reachesEveryDocumentOfASource() throws Exception {
            open(never());
            ingest("docs", "first.md", "confidential");
            ingest("docs", "second.md", "also confidential");
            index.refresh();
            epoch.incrementAndGet();

            DocumentErasureService.Outcome outcome =
                withEraser(recording(3)).eraseSource("docs", 100, false);

            assertThat(eraser.documents()).containsExactlyInAnyOrder("docs:first.md", "docs:second.md");
            assertThat(outcome.vectorsHidden()).isEqualTo(6);
        }

        @Test
        @DisplayName("a node with no vector index erases just the same")
        void aVectorIndexIsOptional() throws Exception {
            open(never());
            ingest("docs", "secret.md", "confidential");
            index.refresh();
            epoch.incrementAndGet();

            DocumentErasureService.Outcome outcome =
                service().erase("docs", List.of("secret.md"), false);

            assertThat(outcome.documentsErased()).isEqualTo(1);
            assertThat(outcome.vectorsHidden()).isZero();
        }

        @Test
        @DisplayName("vectors that will not retire are reported, not thrown")
        void aFailureDoesNotStopTheRest() throws Exception {
            // The content leaving storage is the part of the promise this node can always keep.
            // Losing it because the graph would not take a write would be the worse outcome.
            open(never());
            ingest("docs", "secret.md", "confidential");
            index.refresh();
            epoch.incrementAndGet();

            DocumentErasureService.Outcome outcome = withEraser(
                new DocumentErasureService.VectorEraser() {
                    @Override
                    public int erase(String documentKey) {
                        throw new IllegalStateException("graph is closed");
                    }

                    @Override
                    public int eraseChunks(java.util.Collection<String> chunkIds) {
                        throw new IllegalStateException("graph is closed");
                    }
                }).erase("docs", List.of("secret.md"), false);

            assertThat(outcome.documentsErased()).isEqualTo(1);
            assertThat(outcome.chunksTombstoned()).isEqualTo(1);
            assertThat(outcome.vectorsHidden())
                .as("nothing was retired, and the reply must not claim otherwise")
                .isZero();
            assertThat(storage).isEmpty();
        }
    }

    @Nested
    @DisplayName("When the content will not go")
    class Shortfall {

        /** Storage that refuses, the way a segment a split retired but never purged does. */
        private final UpsertHandler.StorageAdapter refusing = key -> {
            throw new IllegalStateException("segment is not writable");
        };

        /** An index that publishes and ranks normally but will not accept an erasure. */
        private ChunkIndex refusingErase(ChunkIndex delegate) {
            return new ForwardingChunkIndex(delegate) {
                @Override
                public int erase(java.util.Set<String> chunkIds) throws IOException {
                    throw new IOException("index directory is read-only");
                }
            };
        }

        @Test
        @DisplayName("a document whose chunks stayed in storage keeps its registry entry")
        void storageRefusalKeepsTheEntry() throws Exception {
            // The registry entry is the only map from a document to its chunk ids. Dropping it
            // over chunks that are still in storage does not erase them - it makes them
            // permanently unerasable, because nothing left can name them.
            open(never());
            ingest("docs", "secret.md", "confidential incident postmortem", "second half");
            index.refresh();
            epoch.incrementAndGet();

            DocumentErasureService.Outcome outcome =
                new DocumentErasureService(registry, refusing, index, storage::get)
                    .erase("docs", List.of("secret.md"), false);

            assertThat(registry.contains("docs", "secret.md"))
                .as("what still holds content must still be nameable, or it can never be erased")
                .isTrue();
            assertThat(outcome.documentsErased())
                .as("a document still in storage has not been erased")
                .isZero();
            assertThat(outcome.documentsIncomplete()).isEqualTo(1);
            assertThat(outcome.chunksNotRemoved()).isEqualTo(2);
        }

        @Test
        @DisplayName("a document whose chunks stayed in storage keeps its visibility entry")
        void storageRefusalKeepsVisibility() throws Exception {
            open(never());
            com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker tracker =
                new com.eonmux.excalibur.ingest.visibility.DocumentVisibilityTracker(registry);
            ingest("docs", "secret.md", "confidential");
            index.refresh();
            epoch.incrementAndGet();
            tracker.registerFromMetadata(registry.get("docs", "secret.md"), 1);

            new DocumentErasureService(registry, refusing, index, storage::get, tracker)
                .erase("docs", List.of("secret.md"), false);

            assertThat(tracker.isDocumentTracked("docs:secret.md"))
                .as("the document is still here, so the state describing it has to stay too")
                .isTrue();
        }

        @Test
        @DisplayName("an index that refuses the erasure keeps the registry entry too")
        void indexRefusalKeepsTheEntry() throws Exception {
            // Chunks out of storage but still in the index are still answering queries under the
            // document's name. Dropping the entry would leave nothing able to retry the erase.
            open(never());
            ingest("docs", "secret.md", "confidential incident postmortem");
            index.refresh();
            epoch.incrementAndGet();

            DocumentErasureService.Outcome outcome =
                new DocumentErasureService(registry, adapter, refusingErase(index), storage::get)
                    .erase("docs", List.of("secret.md"), false);

            assertThat(registry.contains("docs", "secret.md")).isTrue();
            assertThat(outcome.documentsErased()).isZero();
            assertThat(outcome.documentsIncomplete()).isEqualTo(1);
        }

        @Test
        @DisplayName("chunks an index refused to erase are not reported compacted")
        void indexRefusalIsNotCompacted() throws Exception {
            // The index recorded no obligation for chunks it would not erase, so it owes nothing
            // and a forced compaction finds nothing to do - while every one of them still
            // matches. Owing nothing is not the same as holding nothing.
            open(never());
            ingest("docs", "secret.md", "zzconfidentialmarker");
            index.refresh();
            epoch.incrementAndGet();

            DocumentErasureService.Outcome outcome =
                new DocumentErasureService(registry, adapter, refusingErase(index), storage::get)
                    .erase("docs", List.of("secret.md"), true);

            assertThat(outcome.outstanding()).isZero();
            assertThat(outcome.chunksStillSearchable()).isEqualTo(1);
            assertThat(outcome.compacted())
                .as("the chunks are still in the index, so they are not off disk")
                .isFalse();
            assertThat(hits("zzconfidentialmarker")).isEqualTo(1);
        }

        @Test
        @DisplayName("the request can be repeated once storage accepts the delete")
        void isRepeatableAfterAFailure() throws Exception {
            open(never());
            ingest("docs", "secret.md", "confidential incident postmortem", "second half");
            index.refresh();
            epoch.incrementAndGet();

            new DocumentErasureService(registry, refusing, index, storage::get)
                .erase("docs", List.of("secret.md"), false);
            DocumentErasureService.Outcome retry =
                service().erase("docs", List.of("secret.md"), false);

            assertThat(retry.documentsErased())
                .as("the whole point of keeping the entry is that the request can be re-run")
                .isEqualTo(1);
            assertThat(retry.documentsIncomplete()).isZero();
            assertThat(storage).isEmpty();
            assertThat(registry.contains("docs", "secret.md")).isFalse();
        }

        @Test
        @DisplayName("one document that will not go does not stop the rest of the batch")
        void oneFailureDoesNotStopTheBatch() throws Exception {
            open(never());
            ingest("docs", "stuck.md", "confidential");
            ingest("docs", "free.md", "also confidential");
            index.refresh();
            epoch.incrementAndGet();

            UpsertHandler.StorageAdapter stuckOnOne = key -> {
                String chunkId = new String(key, StandardCharsets.UTF_8);
                if (chunkId.startsWith("docs:stuck.md:")) {
                    throw new IllegalStateException("segment is not writable");
                }
                return storage.remove(chunkId) != null;
            };

            DocumentErasureService.Outcome outcome =
                new DocumentErasureService(registry, stuckOnOne, index, storage::get)
                    .eraseSource("docs", 100, false);

            assertThat(outcome.documentsErased()).isEqualTo(1);
            assertThat(outcome.documentsIncomplete()).isEqualTo(1);
            assertThat(registry.contains("docs", "free.md")).isFalse();
            assertThat(registry.contains("docs", "stuck.md")).isTrue();
        }

        @Test
        @DisplayName("an erasure holds the document's lock while it works")
        void erasureExcludesConcurrentPublication() throws Exception {
            // Publication and erasure both rewrite the registry, the visibility entry and the
            // chunks. Interleaved, one of them resurrects what the other removed; the order that
            // looks harmless is worse, because it leaves chunks nothing can name.
            open(never());
            ingest("docs", "secret.md", "confidential", "second half");
            index.refresh();
            epoch.incrementAndGet();

            java.util.concurrent.CountDownLatch entered =
                new java.util.concurrent.CountDownLatch(1);
            UpsertHandler.StorageAdapter probing = key -> {
                Thread contender = new Thread(() -> DocumentLocks.shared()
                    .inDocument("docs:secret.md", entered::countDown));
                contender.setDaemon(true);
                contender.start();
                try {
                    assertThat(entered.await(250, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .as("nothing else may touch the document while it is being erased")
                        .isFalse();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return storage.remove(new String(key, StandardCharsets.UTF_8)) != null;
            };

            new DocumentErasureService(registry, probing, index, storage::get)
                .erase("docs", List.of("secret.md"), false);

            assertThat(entered.await(30, java.util.concurrent.TimeUnit.SECONDS))
                .as("and it is released again once the erasure is done")
                .isTrue();
        }
    }

    /** A ChunkIndex that behaves like another, so a test can replace one thing it does. */
    private abstract static class ForwardingChunkIndex implements ChunkIndex {

        private final ChunkIndex delegate;

        ForwardingChunkIndex(ChunkIndex delegate) {
            this.delegate = delegate;
        }

        @Override
        public void add(String chunkId, String text) throws IOException {
            delegate.add(chunkId, text);
        }

        @Override
        public void addDocument(java.util.Map<String, String> chunks) throws IOException {
            delegate.addDocument(chunks);
        }

        @Override
        public void refresh() throws IOException {
            delegate.refresh();
        }

        @Override
        public int erase(java.util.Set<String> chunkIds) throws IOException {
            return delegate.erase(chunkIds);
        }

        @Override
        public String chunkIdOf(long id) throws IOException {
            return delegate.chunkIdOf(id);
        }

        @Override
        public long indexedCount() {
            return delegate.indexedCount();
        }

        @Override
        public com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult compactIfDue(
                java.util.function.Function<String, String> textOf) throws IOException {
            return delegate.compactIfDue(textOf);
        }

        @Override
        public com.eonmux.excalibur.retrieval.lexical.native_.CompactionResult compactNow(
                java.util.function.Function<String, String> textOf) throws IOException {
            return delegate.compactNow(textOf);
        }

        @Override
        public com.eonmux.excalibur.retrieval.rank.Ranker ranker(QueryExpander expander,
                java.util.function.Predicate<String> admits) {
            return delegate.ranker(expander, admits);
        }

        @Override
        public String engine() {
            return delegate.engine();
        }

        @Override
        public com.eonmux.excalibur.ingest.lexical.ChunkIndexStats stats() {
            return delegate.stats();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
