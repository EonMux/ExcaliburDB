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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a crash leaves when records were written and the force covering them never finished.
 *
 * <p>The operating system writes a file's dirty pages back in whatever order it likes, not the
 * order they were appended in, and a record that was written but never forced can reach the
 * device as the bytes that were there before - zeros, or garbage - while a record appended after
 * it does reach it intact. Nothing written after the last force that completed was ever
 * acknowledged, so none of it is anybody's; what may not happen is that it wedges the log, or
 * that tolerating it hides the rot of a record somebody was told about.</p>
 */
@DisplayName("Recovery from writes that never finished")
class UnfinishedWriteRecoveryTest {

    private static final Provenance DOCUMENT = Provenance.of(SourceType.DOCUMENT, 7, 0.9, 1);

    private static ExtractedTriple fact(String subject, String relation, String object) {
        return ExtractedTriple.alwaysValid(subject, relation, object, DOCUMENT);
    }

    private static String fingerprint(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** Where each label record of an interner begins, in id order. */
    private static List<Integer> labelOffsets(Path interner) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(interner));
        buffer.position(LabelInterner.HEADER_BYTES);
        List<Integer> offsets = new ArrayList<>();
        while (buffer.remaining() >= Integer.BYTES) {
            int start = buffer.position();
            int length = buffer.getInt();
            if (length <= 0 || buffer.remaining() < length + Integer.BYTES) {
                break;
            }
            buffer.position(buffer.position() + length + Integer.BYTES);
            offsets.add(start);
        }
        return offsets;
    }

    /** Overwrites one label record, framing and all, with zeros: a page that never reached disk. */
    private static void zeroLabel(Path interner, int id) throws IOException {
        byte[] all = Files.readAllBytes(interner);
        List<Integer> offsets = labelOffsets(interner);
        int start = offsets.get(id);
        int end = id + 1 < offsets.size() ? offsets.get(id + 1) : all.length;
        java.util.Arrays.fill(all, start, end, (byte) 0);
        Files.write(interner, all);
    }

    @Nested
    @DisplayName("labels written by one unfinished group commit")
    class Labels {

        @Test
        @DisplayName("a damaged label with intact labels after it, none of whose ids anything "
            + "holds, is cut off with them and the store opens")
        void unheldDamageIsCutOff(@TempDir Path dir) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                staging.append(fact("Alice", "knows", "Bob"));
                // Interned together and never named by a fact: a crash before the force covering
                // them finished lost the facts they were interned for.
                staging.entityIdOf("Carol");
                staging.entityIdOf("Dave");
                staging.entityIdOf("Erin");
            }
            Path entities = dir.resolve(GraphStagingStore.ENTITY_LABELS);
            zeroLabel(entities, 2);

            LogInspection inspected = GraphStagingStore.inspectLabels(entities,
                ReferencedLabels.NONE);
            assertThat(inspected.hasTornTail()).isTrue();
            assertThat(inspected.records()).isEqualTo(2);
            assertThat(inspected.intactRecordsCut()).isEqualTo(2);

            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                assertThat(staging.entityLabels()).containsExactly("Alice", "Bob");
                assertThat(staging.entityIdOf("Zed")).isEqualTo(2);
            }
            assertThat(labelOffsets(entities)).hasSize(3);
        }

        @Test
        @DisplayName("an intact label after the damage whose id a staged fact holds refuses the "
            + "open: the damaged one was forced, so it rotted")
        void damageBeforeAHeldLabelIsRefused(@TempDir Path dir) throws IOException {
            int dave;
            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                staging.append(fact("Alice", "knows", "Bob"));
                staging.entityIdOf("Carol");
                staging.append(fact("Dave", "knows", "Alice"));
                dave = staging.entityIdOf("Dave");
            }
            Path entities = dir.resolve(GraphStagingStore.ENTITY_LABELS);
            zeroLabel(entities, 2);
            String before = fingerprint(entities);

            assertThatThrownBy(() -> GraphStagingStore.open(dir))
                .isInstanceOf(LogCorruptionException.class)
                .hasMessageContaining("id " + dave)
                .hasMessageContaining(GraphStagingStore.TRIPLE_LOG);
            assertThatThrownBy(() -> GraphStagingStore.inspectLabels(entities,
                    ReferencedLabels.NONE))
                .isInstanceOf(LogCorruptionException.class);
            assertThat(fingerprint(entities)).isEqualTo(before);
        }

        @Test
        @DisplayName("the damaged label's own id held by an outstanding erasure refuses the open")
        void damageToAnOwedIdIsRefused(@TempDir Path dir) throws IOException {
            int carol;
            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                staging.append(fact("Alice", "knows", "Bob"));
                carol = staging.entityIdOf("Carol");
                staging.entityIdOf("Dave");
            }
            Path erasures = dir.resolve("erasure.log");
            try (ErasureLog log = ErasureLog.open(erasures)) {
                log.submit(new ErasureScope.Entity(carol), Instant.EPOCH);
            }
            zeroLabel(dir.resolve(GraphStagingStore.ENTITY_LABELS), carol);

            assertThatThrownBy(() -> GraphStagingStore.open(dir, ReferencedLabels.owedIn(erasures)))
                .isInstanceOf(LogCorruptionException.class)
                .hasMessageContaining("erasure");
        }

        @Test
        @DisplayName("the inspection counts exactly what the open keeps and cuts")
        void theInspectionMatchesTheOpen(@TempDir Path dir) throws IOException {
            Path file = dir.resolve(GraphStagingStore.ENTITY_LABELS);
            try (LabelInterner interner = LabelInterner.open(file)) {
                for (String label : List.of("zero", "one", "two", "three", "four")) {
                    interner.intern(label);
                }
            }
            zeroLabel(file, 1);

            LogInspection inspected = LabelInterner.inspect(file);
            try (LabelInterner opened = LabelInterner.open(file)) {
                assertThat(opened.labels()).containsExactly("zero");
            }

            assertThat(inspected.records()).isEqualTo(1);
            assertThat(inspected.intactRecordsCut()).isEqualTo(3);
            assertThat(Files.size(file)).isEqualTo(inspected.intactBytes());
        }
    }

    @Nested
    @DisplayName("a triple log ending in whole records that never reached the device")
    class Triples {

        private Path stage(Path dir, int facts) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                for (int i = 0; i < facts; i++) {
                    staging.append(fact("Ada" + i, "knows", "Grace"));
                }
            }
            return dir.resolve(GraphStagingStore.TRIPLE_LOG);
        }

        private static void fill(Path log, int fromRecord, int records, byte value)
                throws IOException {
            byte[] all = Files.readAllBytes(log);
            int start = GraphStagingStore.HEADER_BYTES + fromRecord * GraphStagingStore.RECORD_BYTES;
            java.util.Arrays.fill(all, start, start + records * GraphStagingStore.RECORD_BYTES,
                value);
            Files.write(log, all);
        }

        @Test
        @DisplayName("a last record of full-length garbage is trimmed on open, and builds and "
            + "erasures read the rest")
        void aGarbageLastRecordIsTrimmed(@TempDir Path root) throws IOException {
            Path dir = root.resolve("graph-staging");
            Path log = stage(dir, 3);
            fill(log, 2, 1, (byte) 0x5A);

            LogInspection inspected = GraphStagingStore.inspectTripleLog(log);
            assertThat(inspected.hasTornTail()).isTrue();
            assertThat(inspected.records()).isEqualTo(2);
            assertThat(inspected.tornBytes()).isEqualTo(GraphStagingStore.RECORD_BYTES);

            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                assertThat(staging.stagedCount()).isEqualTo(2);
                List<Integer> subjects = new ArrayList<>();
                staging.forEach((s, r, o, from, to, provenance) -> subjects.add(s));
                assertThat(subjects).hasSize(2);
                staging.append(fact("Babbage", "knows", "Boole"));
                staging.flush();
                assertThat(staging.forEach((s, r, o, from, to, provenance) -> { })).isEqualTo(3);
                GraphBuilder.build(staging, root.resolve("graph"));
            }
            assertThat(GraphStagingStore.inspectTripleLog(log).hasTornTail()).isFalse();
        }

        @Test
        @DisplayName("several zeroed records and a partial one after them are trimmed together")
        void zeroedRecordsAreTrimmed(@TempDir Path dir) throws IOException {
            Path log = stage(dir, 4);
            fill(log, 1, 3, (byte) 0);
            Files.write(log, new byte[7], java.nio.file.StandardOpenOption.APPEND);

            LogInspection inspected = GraphStagingStore.inspectTripleLog(log);
            assertThat(inspected.records()).isEqualTo(1);
            assertThat(inspected.tornBytes()).isEqualTo(3L * GraphStagingStore.RECORD_BYTES + 7);

            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                assertThat(staging.stagedCount()).isEqualTo(1);
            }
            assertThat(Files.size(log)).isEqualTo(inspected.intactBytes());
        }

        @Test
        @DisplayName("a damaged record with an intact one after it is still refused, and not "
            + "trimmed")
        void damageWithAnIntactRecordAfterItIsRefused(@TempDir Path dir) throws IOException {
            Path log = stage(dir, 3);
            fill(log, 1, 1, (byte) 0x5A);
            String before = fingerprint(log);

            assertThatThrownBy(() -> GraphStagingStore.inspectTripleLog(log))
                .isInstanceOf(LogCorruptionException.class);
            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                assertThatThrownBy(() -> staging.forEach((s, r, o, from, to, provenance) -> { }))
                    .isInstanceOf(LogCorruptionException.class);
            }
            assertThat(fingerprint(log)).isEqualTo(before);
        }

        @Test
        @DisplayName("a garbage tail does not stop an interner with a damaged last label being "
            + "judged by the facts before it")
        void aGarbageTailIsNotAHolder(@TempDir Path dir) throws IOException {
            Path log = stage(dir, 2);
            fill(log, 1, 1, (byte) 0x5A);
            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                staging.entityIdOf("Carol");
            }
            // The open above trimmed the log; put a garbage record back, as a second crash would.
            Files.write(log, new byte[GraphStagingStore.RECORD_BYTES],
                java.nio.file.StandardOpenOption.APPEND);
            Path entities = dir.resolve(GraphStagingStore.ENTITY_LABELS);
            byte[] all = Files.readAllBytes(entities);
            all[all.length - Integer.BYTES - 1] ^= 0x40;
            Files.write(entities, all);

            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                assertThat(staging.entityLabels()).containsExactly("Ada0", "Grace", "Ada1");
                assertThat(staging.stagedCount()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a version 1 log ending in a garbage record is upgraded without it")
        void aLegacyGarbageTailIsDropped(@TempDir Path dir) throws IOException {
            Path log = dir.resolve(GraphStagingStore.TRIPLE_LOG);
            ByteBuffer buffer = ByteBuffer.allocate(GraphStagingStore.HEADER_BYTES
                + 3 * GraphStagingStore.LEGACY_RECORD_BYTES);
            buffer.putLong(GraphStagingStore.MAGIC).putInt(GraphStagingStore.VERSION_WITHOUT_STATED_ON)
                .putInt(0);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 2; i++) {
                int start = buffer.position();
                buffer.putInt(i).putInt(0).putInt(i + 1).putInt(Integer.MIN_VALUE)
                    .putInt(Integer.MAX_VALUE)
                    .put((byte) 0).put((byte) 0).putShort((short) 0).putLong(0);
                CRC32 crc = new CRC32();
                crc.update(buffer.array(), start, GraphStagingStore.LEGACY_RECORD_BYTES
                    - Integer.BYTES);
                buffer.putInt((int) crc.getValue());
            }
            Files.write(log, buffer.array());

            LogInspection inspected = GraphStagingStore.inspectTripleLog(log);
            assertThat(inspected.records()).isEqualTo(2);
            assertThat(inspected.tornBytes()).isEqualTo(GraphStagingStore.LEGACY_RECORD_BYTES);

            try (LabelInterner entities = LabelInterner.open(
                    dir.resolve(GraphStagingStore.ENTITY_LABELS));
                 LabelInterner relations = LabelInterner.open(
                    dir.resolve(GraphStagingStore.RELATION_LABELS))) {
                entities.intern("a");
                entities.intern("b");
                entities.intern("c");
                relations.intern("r");
            }
            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                assertThat(staging.stagedCount()).isEqualTo(2);
            }
        }
    }
}
