package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.file.LogCorruptionException;
import com.eonmux.excalibur.graph.file.LogInspection;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A label interner's last record may be cut off only when no id it held was ever handed out.
 *
 * <p>An id is the only name every other artifact has for an entity: the triple log, the entity
 * text log, an outstanding erasure and the published dictionary all hold the integer and nothing
 * else. Trimming a last record whose id any of them holds, and then interning a new label, gives
 * that id to a stranger - and every one of those artifacts starts describing the stranger.</p>
 */
@DisplayName("Label id reuse")
class LabelIdReuseTest {

    private static final Provenance DOCUMENT = Provenance.of(SourceType.DOCUMENT, 7, 0.9, 1);

    private static ExtractedTriple fact(String subject, String relation, String object) {
        return ExtractedTriple.alwaysValid(subject, relation, object, DOCUMENT);
    }

    /** Breaks the checksum of an interner's last record, leaving its length and framing whole. */
    private static void damageLastLabel(Path interner) throws IOException {
        byte[] all = Files.readAllBytes(interner);
        // The byte before the trailing checksum: the last byte of the last label's text.
        all[all.length - Integer.BYTES - 1] ^= 0x40;
        Files.write(interner, all);
    }

    private static String fingerprint(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    @Nested
    @DisplayName("an id is durable before anyone holds it")
    class Durability {

        @Test
        @DisplayName("a new label's record is in the file before its id is returned")
        void theRecordPrecedesTheId(@TempDir Path dir) throws IOException {
            Path file = dir.resolve(GraphStagingStore.ENTITY_LABELS);
            try (LabelInterner interner = LabelInterner.open(file)) {
                interner.intern("Ada");
                interner.intern("Grace");

                // Read from the file while the interner is still open and unflushed: whatever
                // an id is handed to may write it somewhere durable on the next instruction.
                LogInspection onDisk = LabelInterner.inspect(file);
                assertThat(onDisk.records()).isEqualTo(2);
                assertThat(onDisk.hasTornTail()).isFalse();
            }
        }

        @Test
        @DisplayName("concurrent interning hands every label one id, the one it reads back as")
        void concurrentInterningIsConsistent(@TempDir Path dir) throws Exception {
            Path file = dir.resolve(GraphStagingStore.ENTITY_LABELS);
            Map<String, Integer> handedOut = new HashMap<>();
            try (LabelInterner interner = LabelInterner.open(file);
                 ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<Map<String, Integer>>> results = new ArrayList<>();
                for (int worker = 0; worker < 8; worker++) {
                    results.add(pool.submit(() -> {
                        Map<String, Integer> seen = new HashMap<>();
                        for (int i = 0; i < 200; i++) {
                            String label = "entity-" + (i % 150);
                            seen.put(label, interner.intern(label));
                        }
                        return seen;
                    }));
                }
                for (Future<Map<String, Integer>> result : results) {
                    for (Map.Entry<String, Integer> entry : result.get().entrySet()) {
                        Integer earlier = handedOut.putIfAbsent(entry.getKey(), entry.getValue());
                        assertThat(earlier == null || earlier.equals(entry.getValue()))
                            .as("%s was handed two ids", entry.getKey()).isTrue();
                    }
                }
                assertThat(interner.size()).isEqualTo(150);
            }

            try (LabelInterner reopened = LabelInterner.open(file)) {
                assertThat(reopened.size()).isEqualTo(150);
                handedOut.forEach((label, id) -> assertThat(reopened.labelOf(id)).isEqualTo(label));
            }
        }
    }

    @Nested
    @DisplayName("a damaged last label something refers to is refused")
    class Referenced {

        @Test
        @DisplayName("by a staged fact: the open refuses, and the file is left as it was")
        void aStagedFactHoldsTheId(@TempDir Path dir) throws IOException {
            int bob;
            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                staging.append(fact("Alice", "knows", "Bob"));
                bob = staging.entityIdOf("Bob");
            }
            Path entities = dir.resolve(GraphStagingStore.ENTITY_LABELS);
            damageLastLabel(entities);
            String before = fingerprint(entities);

            assertThatThrownBy(() -> GraphStagingStore.open(dir))
                .isInstanceOf(LogCorruptionException.class)
                .hasMessageContaining("id " + bob)
                .hasMessageContaining(GraphStagingStore.TRIPLE_LOG);
            assertThat(fingerprint(entities)).isEqualTo(before);
        }

        @Test
        @DisplayName("a relation held by a staged fact is refused the same way")
        void aStagedFactHoldsTheRelationId(@TempDir Path dir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                staging.append(fact("Alice", "knows", "Bob"));
            }
            Path relations = dir.resolve(GraphStagingStore.RELATION_LABELS);
            damageLastLabel(relations);
            String before = fingerprint(relations);

            assertThatThrownBy(() -> GraphStagingStore.open(dir))
                .isInstanceOf(LogCorruptionException.class)
                .hasMessageContaining(GraphStagingStore.RELATION_LABELS);
            assertThat(fingerprint(relations)).isEqualTo(before);
        }

        @Test
        @DisplayName("by an entity's recorded text")
        void entityTextHoldsTheId(@TempDir Path dir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                staging.append(fact("Alice", "knows", "Bob"));
                staging.appendEntityText(staging.entityIdOf("Carol"), "Carol wrote it", DOCUMENT);
            }
            damageLastLabel(dir.resolve(GraphStagingStore.ENTITY_LABELS));

            assertThatThrownBy(() -> GraphStagingStore.open(dir))
                .isInstanceOf(LogCorruptionException.class)
                .hasMessageContaining(GraphStagingStore.ENTITY_TEXT_LOG);
        }

        @Test
        @DisplayName("by a published graph's dictionary")
        void aPublishedGraphHoldsTheId(@TempDir Path root) throws IOException {
            Path dir = root.resolve("graph-staging");
            Path graph = root.resolve("graph");
            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                staging.append(fact("Alice", "knows", "Bob"));
                // Named by nothing staged, only by the dictionary the build publishes.
                staging.entityIdOf("Carol");
                GraphBuilder.build(staging, graph);
            }
            Path entities = dir.resolve(GraphStagingStore.ENTITY_LABELS);
            damageLastLabel(entities);
            String before = fingerprint(entities);

            assertThatThrownBy(() -> GraphStagingStore.open(dir,
                    ReferencedLabels.publishedIn(graph)))
                .isInstanceOf(LogCorruptionException.class)
                .hasMessageContaining("published graph");
            assertThat(fingerprint(entities)).isEqualTo(before);
        }

        @Test
        @DisplayName("by an outstanding erasure of that entity")
        void anOutstandingErasureHoldsTheId(@TempDir Path dir) throws IOException {
            int carol;
            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                staging.append(fact("Alice", "knows", "Bob"));
                carol = staging.entityIdOf("Carol");
            }
            Path erasures = dir.resolve("erasure.log");
            try (ErasureLog log = ErasureLog.open(erasures)) {
                log.submit(new ErasureScope.Entity(carol), Instant.EPOCH);
            }
            damageLastLabel(dir.resolve(GraphStagingStore.ENTITY_LABELS));

            assertThatThrownBy(() -> GraphStagingStore.open(dir, ReferencedLabels.owedIn(erasures)))
                .isInstanceOf(LogCorruptionException.class)
                .hasMessageContaining("erasure");
        }

        @Test
        @DisplayName("the read-only inspection refuses it on the same terms")
        void theInspectionAgrees(@TempDir Path dir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                staging.append(fact("Alice", "knows", "Bob"));
            }
            Path entities = dir.resolve(GraphStagingStore.ENTITY_LABELS);
            damageLastLabel(entities);
            String before = fingerprint(entities);

            assertThatThrownBy(() -> GraphStagingStore.inspectLabels(entities,
                    ReferencedLabels.NONE))
                .isInstanceOf(LogCorruptionException.class);
            assertThat(fingerprint(entities)).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("what the holders report")
    class Holders {

        @Test
        @DisplayName("a graph that is not there, and logs that are not there, hold nothing")
        void absentHoldersHoldNothing(@TempDir Path dir) throws IOException {
            ReferencedLabels holders = ReferencedLabels.all(
                ReferencedLabels.publishedIn(dir.resolve("graph")),
                ReferencedLabels.owedIn(dir.resolve("erasure.log")));

            assertThat(holders.reach()).isEqualTo(ReferencedLabels.Reach.NONE);
            assertThat(ReferencedLabels.NONE.reach()).isEqualTo(ReferencedLabels.Reach.NONE);
        }

        @Test
        @DisplayName("a graph a publish set aside is read as well as the served one")
        void aDisplacedGraphIsRead(@TempDir Path root) throws IOException {
            Path graph = root.resolve("graph");
            try (GraphStagingStore staging = GraphStagingStore.open(root.resolve("staging"))) {
                staging.append(fact("Alice", "knows", "Bob"));
                staging.append(fact("Bob", "likes", "Carol"));
                GraphBuilder.build(staging, graph);
            }
            Files.move(graph, GraphBuilder.previousFor(graph));

            ReferencedLabels.Reach reach = ReferencedLabels.publishedIn(graph).reach();

            assertThat(reach.entities().highestId()).isEqualTo(2);
            assertThat(reach.relations().highestId()).isEqualTo(1);
            assertThat(reach.entities().heldBy()).contains(".previous");
        }

        @Test
        @DisplayName("only entity-scoped requests still owed hold an id")
        void onlyOwedEntityRequestsHoldAnId(@TempDir Path dir) throws IOException {
            Path log = dir.resolve("erasure.log");
            long applied;
            try (ErasureLog erasures = ErasureLog.open(log)) {
                applied = erasures.submit(new ErasureScope.Entity(9), Instant.EPOCH);
                erasures.submit(new ErasureScope.Entity(4), Instant.EPOCH);
                erasures.submit(new ErasureScope.Source(SourceType.DOCUMENT, 99), Instant.EPOCH);
                erasures.complete(List.of(applied));
            }

            assertThat(ErasureLog.highestEntityOwed(log)).isEqualTo(4);
        }

        @Test
        @DisplayName("an erasure log whose creation was cut short owes nothing; short junk is "
            + "refused")
        void aShortErasureLogIsJudgedAsTheOpenJudgesIt(@TempDir Path dir) throws IOException {
            Path torn = dir.resolve("torn.log");
            Files.write(torn, java.util.Arrays.copyOf(com.eonmux.excalibur.graph.file.LogHeader
                .of(ErasureLog.MAGIC, ErasureLog.VERSION), 5));
            Path junk = dir.resolve("junk.log");
            Files.write(junk, new byte[] {1, 2, 3});

            assertThat(ErasureLog.highestEntityOwed(torn)).isEqualTo(-1);
            assertThatThrownBy(() -> ErasureLog.highestEntityOwed(junk))
                .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("a published dictionary that is not one is refused rather than read as empty")
        void aDamagedDictionaryIsRefused(@TempDir Path dir) throws IOException {
            Path graph = Files.createDirectories(dir.resolve("graph"));
            Path index = graph.resolve(GraphBuilder.ENTITY_DICTIONARY + ".idx");

            Files.write(index, new byte[3]);
            assertThatThrownBy(() -> ReferencedLabels.publishedIn(graph).reach())
                .isInstanceOf(IOException.class).hasMessageContaining("shorter than its header");

            Files.write(index, new byte[16]);
            assertThatThrownBy(() -> ReferencedLabels.publishedIn(graph).reach())
                .isInstanceOf(IOException.class).hasMessageContaining("not a label dictionary");
        }
    }

    @Nested
    @DisplayName("a damaged last label nothing refers to")
    class Unreferenced {

        @Test
        @DisplayName("is trimmed as a torn tail, and the store opens")
        void isTrimmed(@TempDir Path dir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                staging.append(fact("Alice", "knows", "Bob"));
                staging.entityIdOf("Carol");
            }
            Path entities = dir.resolve(GraphStagingStore.ENTITY_LABELS);
            damageLastLabel(entities);

            LogInspection inspected = GraphStagingStore.inspectLabels(entities,
                ReferencedLabels.NONE);
            assertThat(inspected.hasTornTail()).isTrue();
            assertThat(inspected.records()).isEqualTo(2);

            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                assertThat(staging.entityCount()).isEqualTo(2);
                assertThat(staging.entityLabels()).containsExactly("Alice", "Bob");
            }
            assertThat(Files.size(entities)).isEqualTo(inspected.intactBytes());
        }
    }
}
