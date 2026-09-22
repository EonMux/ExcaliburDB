package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A triple log written before records carried the day they were stated.
 *
 * <p>Every node that staged facts before this version has one on disk, and the staged facts are
 * the only durable record of what ingestion extracted: a log that no longer opened would cost
 * the whole graph and every extraction call that built it. The legacy bytes here are written by
 * hand, to the layout that version wrote, so the test does not depend on any code that could
 * still produce it.</p>
 */
@DisplayName("Triple log upgrade")
class TripleLogUpgradeTest {

    private static final int LEGACY_PAYLOAD = GraphStagingStore.LEGACY_RECORD_BYTES - Integer.BYTES;

    /** One version-1 record, exactly as that version laid it out. */
    private static byte[] legacyRecord(int subject, int relation, int object, int validFrom,
                                       int validTo, Provenance provenance) {
        ByteBuffer record = ByteBuffer.allocate(GraphStagingStore.LEGACY_RECORD_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
        record.putInt(subject);
        record.putInt(relation);
        record.putInt(object);
        record.putInt(validFrom);
        record.putInt(validTo);
        record.put(provenance.sourceType().toByte());
        record.put(provenance.confidenceByte());
        record.putShort(provenance.extractorVersionShort());
        record.putLong(provenance.sourceId());
        CRC32 crc = new CRC32();
        crc.update(record.array(), 0, LEGACY_PAYLOAD);
        record.putInt((int) crc.getValue());
        return record.array();
    }

    private static byte[] header(int version) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeLong(GraphStagingStore.MAGIC);
            out.writeInt(version);
            out.writeInt(0);
        }
        return bytes.toByteArray();
    }

    /** A staging directory whose labels are interned and whose log is version 1. */
    private static Path legacyStaging(Path dir, byte[]... records) throws IOException {
        try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
            staging.entityIdOf("Acme");
            staging.entityIdOf("Ada");
            staging.entityIdOf("Grace");
            staging.relationIdOf("ceo");
        }
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        log.write(header(GraphStagingStore.VERSION_WITHOUT_STATED_ON));
        for (byte[] record : records) {
            log.write(record);
        }
        Path path = dir.resolve(GraphStagingStore.TRIPLE_LOG);
        Files.write(path, log.toByteArray());
        return path;
    }

    private static final Provenance FIRST = Provenance.of(SourceType.DOCUMENT, 11, 0.8, 3);
    private static final Provenance SECOND = Provenance.of(SourceType.CONVERSATION, 22, 0.6, 4);

    /** Everything a replay reports about one fact. */
    private record Replayed(int subject, int relation, int object, int validFrom, int validTo,
                            int statedOn, Provenance provenance) {
    }

    private static List<Replayed> replay(GraphStagingStore staging) throws IOException {
        List<Replayed> facts = new ArrayList<>();
        staging.forEachStaged((s, r, o, from, to, statedOn, provenance) ->
            facts.add(new Replayed(s, r, o, from, to, statedOn, provenance)));
        return facts;
    }

    @Test
    @DisplayName("a version 1 log opens and replays every fact as it was staged")
    void legacyLogOpens(@TempDir Path dir) throws IOException {
        Path log = legacyStaging(dir,
            legacyRecord(1, 0, 0, TemporalFilter.UNDATED, TemporalFilter.OPEN, FIRST),
            legacyRecord(2, 0, 0, 100, 200, SECOND));

        try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
            assertThat(staging.stagedCount()).isEqualTo(2);
            assertThat(replay(staging))
                .as("in staging order, with nothing recorded about when they were stated")
                .containsExactly(
                    new Replayed(1, 0, 0, TemporalFilter.UNDATED, TemporalFilter.OPEN,
                        TemporalFilter.UNDATED, FIRST),
                    new Replayed(2, 0, 0, 100, 200, TemporalFilter.UNDATED, SECOND));
        }

        byte[] upgraded = Files.readAllBytes(log);
        assertThat(ByteBuffer.wrap(upgraded).getInt(Long.BYTES))
            .as("rewritten once, in the current version")
            .isEqualTo(GraphStagingStore.VERSION);
        assertThat(upgraded.length).isEqualTo(
            GraphStagingStore.HEADER_BYTES + 2 * GraphStagingStore.RECORD_BYTES);
        assertThat(Files.exists(log.resolveSibling(log.getFileName() + ".rewriting"))).isFalse();
    }

    @Test
    @DisplayName("an upgraded log takes new facts and keeps them across a restart")
    void upgradedLogAppends(@TempDir Path dir) throws IOException {
        legacyStaging(dir, legacyRecord(1, 0, 0, TemporalFilter.UNDATED, TemporalFilter.OPEN,
            FIRST));

        try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
            staging.append(ExtractedTriple.alwaysValid("Grace", "ceo", "Acme", SECOND)
                .withStatedOn(19_000));
        }

        try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
            List<Replayed> facts = replay(staging);
            assertThat(facts).hasSize(2);
            assertThat(facts.get(0).statedOn()).isEqualTo(TemporalFilter.UNDATED);
            assertThat(facts.get(1).statedOn()).isEqualTo(19_000);
            assertThat(facts.get(1).subject()).isEqualTo(staging.entityIdOf("Grace"));
        }
    }

    @Test
    @DisplayName("a record torn off the end of a version 1 log is dropped, as opening trims one")
    void legacyTornTailIsDropped(@TempDir Path dir) throws IOException {
        byte[] torn = Arrays.copyOf(
            legacyRecord(2, 0, 0, TemporalFilter.UNDATED, TemporalFilter.OPEN, SECOND), 11);
        legacyStaging(dir,
            legacyRecord(1, 0, 0, TemporalFilter.UNDATED, TemporalFilter.OPEN, FIRST), torn);

        try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
            assertThat(replay(staging)).extracting(Replayed::subject).containsExactly(1);
        }
    }

    @Test
    @DisplayName("a corrupt version 1 record refuses the upgrade and leaves the log as it was")
    void legacyCorruptionRefusesTheUpgrade(@TempDir Path dir) throws IOException {
        byte[] damaged = legacyRecord(1, 0, 0, TemporalFilter.UNDATED, TemporalFilter.OPEN, FIRST);
        damaged[3] ^= (byte) 0xFF;
        Path log = legacyStaging(dir, damaged,
            legacyRecord(2, 0, 0, TemporalFilter.UNDATED, TemporalFilter.OPEN, SECOND));
        byte[] before = Files.readAllBytes(log);

        assertThatThrownBy(() -> GraphStagingStore.open(dir))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("corrupt record at fact 0");
        assertThat(Files.readAllBytes(log))
            .as("the facts behind the damage are still there for an operator to recover")
            .isEqualTo(before);
    }

    @Test
    @DisplayName("a log of a version this build does not know is refused")
    void unknownVersionIsRefused(@TempDir Path dir) throws IOException {
        Path log = legacyStaging(dir);
        byte[] future = header(GraphStagingStore.VERSION + 1);
        Files.write(log, future);

        assertThatThrownBy(() -> GraphStagingStore.open(dir))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("unsupported triple log version");
        assertThatThrownBy(() -> GraphStagingStore.factsOnDisk(log))
            .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("facts on disk are counted in either version without writing anything")
    void factsOnDiskReadsEitherVersion(@TempDir Path dir) throws IOException {
        Path log = legacyStaging(dir,
            legacyRecord(1, 0, 0, TemporalFilter.UNDATED, TemporalFilter.OPEN, FIRST),
            legacyRecord(2, 0, 0, TemporalFilter.UNDATED, TemporalFilter.OPEN, SECOND),
            new byte[5]);
        byte[] legacy = Files.readAllBytes(log);

        assertThat(GraphStagingStore.factsOnDisk(log))
            .as("counted at the width version 1 wrote, the partial record excluded")
            .isEqualTo(2);
        assertThat(Files.readAllBytes(log)).as("a count does not upgrade").isEqualTo(legacy);

        try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
            staging.append(ExtractedTriple.alwaysValid("Ada", "ceo", "Acme", FIRST));
        }
        assertThat(GraphStagingStore.factsOnDisk(log)).isEqualTo(3);

        assertThat(GraphStagingStore.factsOnDisk(dir.resolve("absent.log"))).isZero();
        Path headerless = dir.resolve("short.log");
        Files.write(headerless, new byte[3]);
        assertThat(GraphStagingStore.factsOnDisk(headerless)).isZero();
    }
}
