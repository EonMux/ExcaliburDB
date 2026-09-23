package com.eonmux.excalibur.graph.staging;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Label interner")
class LabelInternerTest {

    private static final String PERSONAL = "Jane Doe (DOB 1972-04-03, NHS 4857392)";

    private static Path file(Path dir) {
        return dir.resolve("entities.labels");
    }

    /**
     * Byte offsets at which each record frame starts, by walking the framing the interner writes.
     *
     * <p>Computed rather than assumed so a test that corrupts "the second record" corrupts the
     * second record and not whatever happens to live at a hard-coded offset.
     */
    private static List<Integer> frameOffsets(Path path) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(path));
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

    /** Flips one bit of the label bytes of the record at {@code offset}, breaking its checksum. */
    private static void corruptLabelAt(Path path, int offset) throws IOException {
        byte[] all = Files.readAllBytes(path);
        all[offset + Integer.BYTES] ^= 0x40;
        Files.write(path, all);
    }

    @Nested
    @DisplayName("erasure")
    class Erasure {

        @Test
        @DisplayName("a tombstoned label is gone from the file, in memory, and from every reopen")
        void aTombstonedLabelIsGone(@TempDir Path dir) throws IOException {
            // The finding this guards: a name erased from the triples and the snippets was still
            // sitting in entities.labels, from where the next build republished it into the
            // searchable label index under the name of the person who asked to be forgotten.
            Path path = file(dir);
            int jane;
            int other;
            try (LabelInterner interner = LabelInterner.open(path)) {
                jane = interner.intern(PERSONAL);
                other = interner.intern("Acme Corporation");
                assertThat(interner.tombstone(id -> id == jane)).isEqualTo(1);

                assertThat(interner.labels()).doesNotContain(PERSONAL);
                assertThat(interner.labelOf(jane)).isNotEqualTo(PERSONAL);
                assertThat(interner.labelOf(other)).isEqualTo("Acme Corporation");
            }

            assertThat(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
                .doesNotContain("Jane Doe")
                .doesNotContain("4857392");

            try (LabelInterner reopened = LabelInterner.open(path)) {
                assertThat(reopened.labels()).doesNotContain(PERSONAL);
                assertThat(reopened.labelOf(other)).isEqualTo("Acme Corporation");
            }
        }

        @Test
        @DisplayName("tombstoning keeps every ordinal exactly where it was")
        void ordinalsDoNotMove(@TempDir Path dir) throws IOException {
            // Ids are referenced by the published edge store, the lexical index and any serialized
            // subgraph. An erasure that renumbered them would serve one entity under another's
            // name, which is the failure this whole class is built to prevent.
            Path path = file(dir);
            try (LabelInterner interner = LabelInterner.open(path)) {
                List<Integer> before = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    before.add(interner.intern("label-" + i));
                }
                int size = interner.size();

                interner.tombstone(id -> id == 2 || id == 4);

                for (int i = 0; i < 6; i++) {
                    if (i != 2 && i != 4) {
                        assertThat(interner.intern("label-" + i)).isEqualTo(before.get(i));
                    }
                }
                assertThat(interner.size()).isEqualTo(size);
                assertThat(interner.intern("label-new")).isEqualTo(size);
            }
        }

        @Test
        @DisplayName("an erased label interns again as a new id rather than resurrecting the old")
        void anErasedLabelIsNotRemembered(@TempDir Path dir) throws IOException {
            Path path = file(dir);
            try (LabelInterner interner = LabelInterner.open(path)) {
                int jane = interner.intern(PERSONAL);
                interner.intern("Acme Corporation");
                interner.tombstone(id -> id == jane);

                assertThat(interner.intern(PERSONAL)).isNotEqualTo(jane);
            }
        }

        @Test
        @DisplayName("tombstoning nothing leaves the file byte for byte as it was")
        void tombstoningNothingChangesNothing(@TempDir Path dir) throws IOException {
            Path path = file(dir);
            try (LabelInterner interner = LabelInterner.open(path)) {
                interner.intern("a");
                interner.intern("b");
                byte[] before = Files.readAllBytes(path);

                assertThat(interner.tombstone(id -> false)).isZero();

                assertThat(Files.readAllBytes(path)).isEqualTo(before);
            }
        }
    }

    @Nested
    @DisplayName("tail integrity")
    class TailIntegrity {

        @Test
        @DisplayName("a genuine torn tail is trimmed and the labels before it keep their ids")
        void aTornTailIsTrimmed(@TempDir Path dir) throws IOException {
            Path path = file(dir);
            try (LabelInterner interner = LabelInterner.open(path)) {
                interner.intern("first");
                interner.intern("second");
            }
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                channel.truncate(Files.size(path) - 3);
            }

            try (LabelInterner reopened = LabelInterner.open(path)) {
                assertThat(reopened.labels()).containsExactly("first");
                assertThat(reopened.intern("second")).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("a corrupt record with intact labels after it fails the open rather than "
            + "silently renumbering them")
        void midFileCorruptionFailsTheOpen(@TempDir Path dir) throws IOException {
            // Truncating here would hand ids 1.. to whatever is interned next while the published
            // entities.idx still maps them to the labels that were lost - entity 1 served under a
            // stranger's name, with nothing anywhere reporting it.
            Path path = file(dir);
            try (LabelInterner interner = LabelInterner.open(path)) {
                interner.intern("zero");
                interner.intern("one");
                interner.intern("two");
                interner.intern("three");
            }
            corruptLabelAt(path, frameOffsets(path).get(1));

            assertThatThrownBy(() -> LabelInterner.open(path))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("entities.labels");
        }

        @Test
        @DisplayName("a corrupt record misaligning everything after it still fails the open")
        void misalignedRecordsAfterCorruptionFailTheOpen(@TempDir Path dir) throws IOException {
            // The partial-write case rather than the bit-rot one: a record cut short leaves the
            // records appended after it at offsets nothing is looking at, so the scan for intact
            // records has to be over byte offsets rather than over frame boundaries.
            Path path = file(dir);
            try (LabelInterner interner = LabelInterner.open(path)) {
                interner.intern("zero");
                interner.intern("one");
                interner.intern("two");
            }
            byte[] all = Files.readAllBytes(path);
            int second = frameOffsets(path).get(1);
            byte[] spliced = new byte[all.length + 5];
            System.arraycopy(all, 0, spliced, 0, second);
            System.arraycopy(all, second, spliced, second + 5, all.length - second);
            ByteBuffer.wrap(spliced).putInt(second, 64);
            Files.write(path, spliced);

            assertThatThrownBy(() -> LabelInterner.open(path))
                .isInstanceOf(IOException.class);
        }
    }
}
