package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.file.LogCorruptionException;
import com.eonmux.excalibur.graph.file.LogInspection;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading a staging log's records without opening it, and reaching the verdict opening it would.
 *
 * <p>Opening any of these logs is not a question: it creates the file, trims a torn tail and
 * rewrites an earlier version in place. An offline checker needs the answer without any of that,
 * and it needs the same answer - a log inspection calls sound must open, one it calls damaged must
 * be refused, and the bytes it calls a torn tail must be exactly the bytes the open cuts off. So
 * every case here is asked twice, once of the inspection and once of the open, on identical
 * copies, and the two are held to agree.</p>
 */
@DisplayName("Staging log inspection")
class StagingLogInspectionTest {

    private static final Provenance DOCUMENT = Provenance.of(SourceType.DOCUMENT, 42, 0.9, 3);

    /** A digest of the file, to show an inspection left it exactly as it found it. */
    private static String fingerprint(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** Flips every bit of one byte. */
    private static void flip(Path file, long offset) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        bytes[Math.toIntExact(offset)] ^= (byte) 0xFF;
        Files.write(file, bytes);
    }

    /** Appends bytes, as a write cut short leaves them. */
    private static void appendBytes(Path file, byte[] tail) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        byte[] longer = new byte[bytes.length + tail.length];
        System.arraycopy(bytes, 0, longer, 0, bytes.length);
        System.arraycopy(tail, 0, longer, bytes.length, tail.length);
        Files.write(file, longer);
    }

    /** Overwrites the version field of a sixteen-byte, big-endian header. */
    private static void setVersion(Path file, int version) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer.wrap(bytes).putInt(Long.BYTES, version);
        Files.write(file, bytes);
    }

    /** A copy of the file beside it, for the open to act on while the original is inspected. */
    private static Path copyOf(Path file) throws IOException {
        Path copy = file.resolveSibling("copy-" + file.getFileName());
        Files.copy(file, copy);
        return copy;
    }

    @Nested
    @DisplayName("a label interner")
    class Labels {

        private Path write(Path dir, String... labels) throws IOException {
            Path file = dir.resolve(GraphStagingStore.ENTITY_LABELS);
            try (LabelInterner interner = LabelInterner.open(file)) {
                for (String label : labels) {
                    interner.intern(label);
                }
            }
            return file;
        }

        @Test
        @DisplayName("every label is verified and counted, and the file is left as it was")
        void anIntactInternerIsCounted(@TempDir Path dir) throws IOException {
            Path file = write(dir, "Ada", "Grace", "Barbara");
            String before = fingerprint(file);

            LogInspection inspection = LabelInterner.inspect(file);

            assertThat(inspection.version()).isEqualTo(LabelInterner.VERSION);
            assertThat(inspection.records()).isEqualTo(3);
            assertThat(inspection.tornBytes()).isZero();
            assertThat(inspection.intactBytes()).isEqualTo(Files.size(file));
            assertThat(fingerprint(file)).isEqualTo(before);
        }

        @Test
        @DisplayName("a torn tail is reported as exactly the bytes the open cuts off")
        void aTornTailMatchesWhatTheOpenTrims(@TempDir Path dir) throws IOException {
            Path file = write(dir, "Ada", "Grace");
            appendBytes(file, new byte[] {0, 0, 0, 40, 'B', 'a'});
            String before = fingerprint(file);
            Path copy = copyOf(file);

            LogInspection inspection = LabelInterner.inspect(file);

            assertThat(inspection.records()).isEqualTo(2);
            assertThat(inspection.tornBytes()).isEqualTo(6);
            assertThat(fingerprint(file)).isEqualTo(before);
            try (LabelInterner opened = LabelInterner.open(copy)) {
                assertThat(opened.size()).isEqualTo(2);
            }
            assertThat(Files.size(copy)).isEqualTo(inspection.intactBytes());
        }

        @Test
        @DisplayName("a damaged last label is a tail the open trims, and is reported as one")
        void aDamagedLastLabelIsATail(@TempDir Path dir) throws IOException {
            Path file = write(dir, "Ada", "Grace");
            flip(file, Files.size(file) - Integer.BYTES - 1);
            Path copy = copyOf(file);

            LogInspection inspection = LabelInterner.inspect(file);

            assertThat(inspection.records()).isEqualTo(1);
            assertThat(inspection.tornBytes()).isEqualTo(Integer.BYTES + 5 + Integer.BYTES);
            LabelInterner.open(copy).close();
            assertThat(Files.size(copy)).isEqualTo(inspection.intactBytes());
        }

        @Test
        @DisplayName("damage with an intact label after it is refused, as the open refuses it")
        void damageInTheMiddleIsCorruption(@TempDir Path dir) throws IOException {
            Path file = write(dir, "Ada", "Grace", "Barbara");
            // The first label's text: header, then its length.
            flip(file, LabelInterner.HEADER_BYTES + Integer.BYTES);
            String before = fingerprint(file);
            Path copy = copyOf(file);

            assertThatThrownBy(() -> LabelInterner.inspect(file))
                .isInstanceOf(LogCorruptionException.class)
                .hasMessageContaining("further intact record");
            assertThat(fingerprint(file)).isEqualTo(before);
            assertThatThrownBy(() -> LabelInterner.open(copy))
                .isInstanceOf(LogCorruptionException.class);
        }

        @Test
        @DisplayName("a version this build does not read is refused, and not as corruption")
        void anUnknownVersionIsNotCorruption(@TempDir Path dir) throws IOException {
            Path file = write(dir, "Ada");
            setVersion(file, LabelInterner.VERSION + 1);

            assertThatThrownBy(() -> LabelInterner.inspect(file))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(LogCorruptionException.class)
                .hasMessageContaining("unsupported label interner version");
        }

        @Test
        @DisplayName("a file that is not there is not created")
        void anAbsentFileIsNotCreated(@TempDir Path dir) {
            Path file = dir.resolve("absent").resolve(GraphStagingStore.ENTITY_LABELS);

            assertThatThrownBy(() -> LabelInterner.inspect(file)).isInstanceOf(IOException.class);
            assertThat(file.getParent()).doesNotExist();
        }
    }

    @Nested
    @DisplayName("an entity text log")
    class EntityText {

        private Path write(Path dir, String... snippets) throws IOException {
            Path file = dir.resolve(GraphStagingStore.ENTITY_TEXT_LOG);
            try (EntityTextLog log = EntityTextLog.open(file)) {
                for (int i = 0; i < snippets.length; i++) {
                    log.append(i, snippets[i], DOCUMENT);
                }
            }
            return file;
        }

        /** Bytes of one current-version record holding {@code text}. */
        private static int recordBytes(String text) {
            return 20 + text.getBytes(StandardCharsets.UTF_8).length + Integer.BYTES;
        }

        @Test
        @DisplayName("every snippet is verified and counted, and the file is left as it was")
        void anIntactLogIsCounted(@TempDir Path dir) throws IOException {
            Path file = write(dir, "Ada wrote the first program", "Grace wrote a compiler");
            String before = fingerprint(file);

            LogInspection inspection = EntityTextLog.inspect(file);

            assertThat(inspection.version()).isEqualTo(EntityTextLog.VERSION);
            assertThat(inspection.records()).isEqualTo(2);
            assertThat(inspection.tornBytes()).isZero();
            assertThat(fingerprint(file)).isEqualTo(before);
        }

        @Test
        @DisplayName("a torn tail is reported as exactly the bytes the open cuts off")
        void aTornTailMatchesWhatTheOpenTrims(@TempDir Path dir) throws IOException {
            Path file = write(dir, "Ada wrote the first program");
            appendBytes(file, new byte[] {0, 0, 0, 1, 1, 0});
            String before = fingerprint(file);
            Path copy = copyOf(file);

            LogInspection inspection = EntityTextLog.inspect(file);

            assertThat(inspection.records()).isEqualTo(1);
            assertThat(inspection.tornBytes()).isEqualTo(6);
            assertThat(fingerprint(file)).isEqualTo(before);
            EntityTextLog.open(copy).close();
            assertThat(Files.size(copy)).isEqualTo(inspection.intactBytes());
        }

        @Test
        @DisplayName("a damaged last snippet is a tail the open trims, and is reported as one")
        void aDamagedLastSnippetIsATail(@TempDir Path dir) throws IOException {
            String last = "Grace wrote a compiler";
            Path file = write(dir, "Ada wrote the first program", last);
            flip(file, Files.size(file) - Integer.BYTES - 1);
            Path copy = copyOf(file);

            LogInspection inspection = EntityTextLog.inspect(file);

            assertThat(inspection.records()).isEqualTo(1);
            assertThat(inspection.tornBytes()).isEqualTo(recordBytes(last));
            EntityTextLog.open(copy).close();
            assertThat(Files.size(copy)).isEqualTo(inspection.intactBytes());
        }

        @Test
        @DisplayName("damage with an intact snippet after it is refused, as the open refuses it")
        void damageInTheMiddleIsCorruption(@TempDir Path dir) throws IOException {
            Path file = write(dir, "Ada wrote the first program", "Grace wrote a compiler");
            // Inside the first snippet's text, which its checksum covers.
            flip(file, EntityTextLog.HEADER_BYTES + 20 + 3);
            String before = fingerprint(file);
            Path copy = copyOf(file);

            assertThatThrownBy(() -> EntityTextLog.inspect(file))
                .isInstanceOf(LogCorruptionException.class);
            assertThat(fingerprint(file)).isEqualTo(before);
            assertThatThrownBy(() -> EntityTextLog.open(copy))
                .isInstanceOf(LogCorruptionException.class);
        }

        @Test
        @DisplayName("a version this build does not read is refused, and not as corruption")
        void anUnknownVersionIsNotCorruption(@TempDir Path dir) throws IOException {
            Path file = write(dir, "Ada wrote the first program");
            setVersion(file, EntityTextLog.VERSION + 1);

            assertThatThrownBy(() -> EntityTextLog.inspect(file))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(LogCorruptionException.class)
                .hasMessageContaining("unsupported entity text log version");
        }

        @Test
        @DisplayName("a pre-provenance log is read in its own framing, and not upgraded")
        void aLegacyLogIsReadWithoutBeingUpgraded(@TempDir Path dir) throws IOException {
            Path file = dir.resolve(GraphStagingStore.ENTITY_TEXT_LOG);
            Files.write(file, legacyLog("Ada wrote the first program", "Grace wrote a compiler"));
            String before = fingerprint(file);

            LogInspection inspection = EntityTextLog.inspect(file);

            assertThat(inspection.version()).isEqualTo(EntityTextLog.VERSION_WITHOUT_PROVENANCE);
            assertThat(inspection.records()).isEqualTo(2);
            assertThat(inspection.tornBytes()).isZero();
            assertThat(fingerprint(file)).isEqualTo(before);
        }

        @Test
        @DisplayName("a pre-provenance log damaged in the middle is refused, as its upgrade is")
        void aDamagedLegacyLogIsCorruption(@TempDir Path dir) throws IOException {
            Path file = dir.resolve(GraphStagingStore.ENTITY_TEXT_LOG);
            Files.write(file, legacyLog("Ada wrote the first program", "Grace wrote a compiler"));
            flip(file, EntityTextLog.HEADER_BYTES + 8 + 2);
            Path copy = copyOf(file);

            assertThatThrownBy(() -> EntityTextLog.inspect(file))
                .isInstanceOf(LogCorruptionException.class);
            assertThatThrownBy(() -> EntityTextLog.open(copy))
                .isInstanceOf(LogCorruptionException.class);
        }

        /** A log in the pre-provenance format, as an earlier build wrote it. */
        private static byte[] legacyLog(String... snippets) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeLong(EntityTextLog.MAGIC);
                out.writeInt(EntityTextLog.VERSION_WITHOUT_PROVENANCE);
                out.writeInt(0);
                for (int id = 0; id < snippets.length; id++) {
                    byte[] text = snippets[id].getBytes(StandardCharsets.UTF_8);
                    CRC32 crc = new CRC32();
                    crc.update(text);
                    out.writeInt(id);
                    out.writeInt(text.length);
                    out.write(text);
                    out.writeInt((int) crc.getValue());
                }
            }
            return bytes.toByteArray();
        }
    }

    @Nested
    @DisplayName("a triple log")
    class Triples {

        private Path write(Path dir, int facts) throws IOException {
            try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
                for (int i = 0; i < facts; i++) {
                    staging.append(ExtractedTriple.alwaysValid("Ada" + i, "knows", "Grace",
                        DOCUMENT));
                }
            }
            return dir.resolve(GraphStagingStore.TRIPLE_LOG);
        }

        @Test
        @DisplayName("every fact is verified and counted, and the file is left as it was")
        void anIntactLogIsCounted(@TempDir Path dir) throws IOException {
            Path file = write(dir, 3);
            String before = fingerprint(file);

            LogInspection inspection = GraphStagingStore.inspectTripleLog(file);

            assertThat(inspection.version()).isEqualTo(GraphStagingStore.VERSION);
            assertThat(inspection.records()).isEqualTo(3);
            assertThat(inspection.tornBytes()).isZero();
            assertThat(fingerprint(file)).isEqualTo(before);
        }

        @Test
        @DisplayName("a partial last record is reported as exactly the bytes the open cuts off")
        void aPartialRecordMatchesWhatTheOpenTrims(@TempDir Path dir) throws IOException {
            Path file = write(dir, 2);
            appendBytes(file, new byte[GraphStagingStore.RECORD_BYTES - 1]);
            String before = fingerprint(file);

            LogInspection inspection = GraphStagingStore.inspectTripleLog(file);

            assertThat(inspection.records()).isEqualTo(2);
            assertThat(inspection.tornBytes()).isEqualTo(GraphStagingStore.RECORD_BYTES - 1);
            assertThat(fingerprint(file)).isEqualTo(before);
            try (GraphStagingStore opened = GraphStagingStore.open(dir)) {
                assertThat(opened.stagedCount()).isEqualTo(2);
            }
            assertThat(Files.size(file)).isEqualTo(inspection.intactBytes());
        }

        @Test
        @DisplayName("a whole record failing its checksum is corruption, even the last one")
        void aDamagedWholeRecordIsCorruption(@TempDir Path dir) throws IOException {
            Path file = write(dir, 2);
            flip(file, Files.size(file) - Integer.BYTES - 1);
            String before = fingerprint(file);

            assertThatThrownBy(() -> GraphStagingStore.inspectTripleLog(file))
                .isInstanceOf(LogCorruptionException.class)
                .hasMessageContaining("corrupt record at fact 1");
            assertThat(fingerprint(file)).isEqualTo(before);
            try (GraphStagingStore opened = GraphStagingStore.open(dir)) {
                assertThatThrownBy(() -> opened.forEach((s, r, o, from, to, provenance) -> { }))
                    .as("the build's replay refuses the same record")
                    .isInstanceOf(LogCorruptionException.class);
            }
        }

        @Test
        @DisplayName("a version this build does not read is refused, and not as corruption")
        void anUnknownVersionIsNotCorruption(@TempDir Path dir) throws IOException {
            Path file = write(dir, 1);
            setVersion(file, GraphStagingStore.VERSION + 1);

            assertThatThrownBy(() -> GraphStagingStore.inspectTripleLog(file))
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(LogCorruptionException.class)
                .hasMessageContaining("unsupported triple log version");
        }

        @Test
        @DisplayName("a version 1 log is read in its own framing, and not upgraded")
        void aLegacyLogIsReadWithoutBeingUpgraded(@TempDir Path dir) throws IOException {
            Path file = dir.resolve(GraphStagingStore.TRIPLE_LOG);
            Files.write(file, legacyLog(legacyRecord(1), legacyRecord(2), new byte[5]));
            String before = fingerprint(file);

            LogInspection inspection = GraphStagingStore.inspectTripleLog(file);

            assertThat(inspection.version()).isEqualTo(GraphStagingStore.VERSION_WITHOUT_STATED_ON);
            assertThat(inspection.records()).isEqualTo(2);
            assertThat(inspection.tornBytes()).isEqualTo(5);
            assertThat(fingerprint(file)).isEqualTo(before);
        }

        @Test
        @DisplayName("a version 1 log with a damaged record is refused, as its upgrade is")
        void aDamagedLegacyLogIsCorruption(@TempDir Path dir) throws IOException {
            byte[] damaged = legacyRecord(1);
            damaged[3] ^= (byte) 0xFF;
            Path file = dir.resolve(GraphStagingStore.TRIPLE_LOG);
            Files.write(file, legacyLog(damaged, legacyRecord(2)));

            assertThatThrownBy(() -> GraphStagingStore.inspectTripleLog(file))
                .isInstanceOf(LogCorruptionException.class);
            assertThatThrownBy(() -> GraphStagingStore.open(dir).close())
                .isInstanceOf(LogCorruptionException.class);
        }

        /** One version-1 record, exactly as that version laid it out. */
        private static byte[] legacyRecord(int subject) {
            ByteBuffer record = ByteBuffer.allocate(GraphStagingStore.LEGACY_RECORD_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
            record.putInt(subject).putInt(0).putInt(0).putInt(0).putInt(Integer.MAX_VALUE);
            record.put(DOCUMENT.sourceType().toByte());
            record.put(DOCUMENT.confidenceByte());
            record.putShort(DOCUMENT.extractorVersionShort());
            record.putLong(DOCUMENT.sourceId());
            CRC32 crc = new CRC32();
            crc.update(record.array(), 0, GraphStagingStore.LEGACY_RECORD_BYTES - Integer.BYTES);
            record.putInt((int) crc.getValue());
            return record.array();
        }

        private static byte[] legacyLog(byte[]... records) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeLong(GraphStagingStore.MAGIC);
                out.writeInt(GraphStagingStore.VERSION_WITHOUT_STATED_ON);
                out.writeInt(0);
                for (byte[] record : records) {
                    out.write(record);
                }
            }
            return bytes.toByteArray();
        }
    }
}
