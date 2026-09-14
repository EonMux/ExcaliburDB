package io.excalibur.graph.dictionary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the entity and relation name dictionary.
 *
 * <p>The reader binary-searches, so the cases that matter are the ones that would make a search
 * wrong: unsorted input, a missing id, an id outside the range that is present, and a file that is
 * not what it claims to be.
 */
@DisplayName("LabelDictionary")
class LabelDictionaryTest {

    @Nested
    @DisplayName("Round trip")
    class RoundTrip {

        @Test
        @DisplayName("names come back by id")
        void namesComeBack(@TempDir Path dir) throws IOException {
            new LabelDictionaryWriter()
                .put(1, "Ada Lovelace")
                .put(2, "Charles Babbage")
                .write(dir, "entities");

            try (LabelDictionary dictionary = LabelDictionary.open(dir, "entities", "E")) {
                assertThat(dictionary.size()).isEqualTo(2);
                assertThat(dictionary.apply(1)).isEqualTo("Ada Lovelace");
                assertThat(dictionary.apply(2)).isEqualTo("Charles Babbage");
            }
        }

        @Test
        @DisplayName("ids written out of order are still found")
        void unsortedInputIsSorted(@TempDir Path dir) throws IOException {
            // The reader binary-searches. Writing in insertion order would make every lookup
            // past the first inversion a miss, and a miss returns a synthetic name rather than
            // failing -- so this would have degraded silently into E-numbers in prompts.
            LabelDictionaryWriter writer = new LabelDictionaryWriter();
            long[] ids = {900, 3, 71, 1, 4242, 12, 0};
            for (long id : ids) {
                writer.put(id, "name-" + id);
            }
            writer.write(dir, "entities");

            try (LabelDictionary dictionary = LabelDictionary.open(dir, "entities", "E")) {
                for (long id : ids) {
                    assertThat(dictionary.apply(id)).isEqualTo("name-" + id);
                }
            }
        }

        @Test
        @DisplayName("a large dictionary resolves every id")
        void manyEntries(@TempDir Path dir) throws IOException {
            LabelDictionaryWriter writer = new LabelDictionaryWriter();
            for (long id = 0; id < 5_000; id++) {
                writer.put(id * 7, "entity-" + id);
            }
            writer.write(dir, "entities");

            try (LabelDictionary dictionary = LabelDictionary.open(dir, "entities", "E")) {
                assertThat(dictionary.size()).isEqualTo(5_000);
                for (long id = 0; id < 5_000; id++) {
                    assertThat(dictionary.apply(id * 7)).isEqualTo("entity-" + id);
                }
            }
        }

        @Test
        @DisplayName("multi-byte names survive")
        void multiByteNames(@TempDir Path dir) throws IOException {
            new LabelDictionaryWriter()
                .put(1, "Ada Lovelace")
                .put(2, "東京")
                .put(3, "Ångström 🜲")
                .write(dir, "entities");

            try (LabelDictionary dictionary = LabelDictionary.open(dir, "entities", "E")) {
                assertThat(dictionary.apply(2)).isEqualTo("東京");
                assertThat(dictionary.apply(3)).isEqualTo("Ångström 🜲");
            }
        }

        @Test
        @DisplayName("a later name for the same id replaces the earlier one")
        void lastWriteWins(@TempDir Path dir) throws IOException {
            new LabelDictionaryWriter()
                .put(1, "old")
                .put(1, "new")
                .write(dir, "entities");

            try (LabelDictionary dictionary = LabelDictionary.open(dir, "entities", "E")) {
                assertThat(dictionary.size()).isEqualTo(1);
                assertThat(dictionary.apply(1)).isEqualTo("new");
            }
        }

        @Test
        @DisplayName("an empty dictionary opens and answers")
        void emptyDictionary(@TempDir Path dir) throws IOException {
            new LabelDictionaryWriter().write(dir, "entities");

            try (LabelDictionary dictionary = LabelDictionary.open(dir, "entities", "E")) {
                assertThat(dictionary.size()).isZero();
                assertThat(dictionary.apply(1)).isEqualTo("E1");
            }
        }
    }

    @Nested
    @DisplayName("Missing ids")
    class MissingIds {

        @Test
        @DisplayName("an unknown id gets a synthetic name rather than null")
        void unknownIdIsSynthesised(@TempDir Path dir) throws IOException {
            // A subgraph is not worth discarding because one entity has no name recorded, and a
            // caller that must null-check every label will eventually forget to.
            new LabelDictionaryWriter().put(10, "ten").put(30, "thirty").write(dir, "entities");

            try (LabelDictionary dictionary = LabelDictionary.open(dir, "entities", "E")) {
                assertThat(dictionary.apply(20)).isEqualTo("E20");   // between two present ids
                assertThat(dictionary.apply(5)).isEqualTo("E5");     // below the range
                assertThat(dictionary.apply(99)).isEqualTo("E99");   // above the range
                assertThat(dictionary.apply(-1)).isEqualTo("E-1");
            }
        }

        @Test
        @DisplayName("the prefix is the caller's, so relations do not look like entities")
        void prefixIsConfigurable(@TempDir Path dir) throws IOException {
            new LabelDictionaryWriter().put(1, "collaboratedWith").write(dir, "relations");

            try (LabelDictionary dictionary = LabelDictionary.open(dir, "relations", "R")) {
                assertThat(dictionary.apply(1)).isEqualTo("collaboratedWith");
                assertThat(dictionary.apply(2)).isEqualTo("R2");
            }
        }
    }

    @Nested
    @DisplayName("Refusing what it cannot read")
    class Refusing {

        @Test
        @DisplayName("an absent dictionary is absent, not an error, when asked optionally")
        void openIfPresentReturnsNull(@TempDir Path dir) throws IOException {
            assertThat(LabelDictionary.openIfPresent(dir, "entities", "E")).isNull();
        }

        @Test
        @DisplayName("an absent dictionary is an error when opened directly")
        void openFailsWhenAbsent(@TempDir Path dir) {
            assertThatThrownBy(() -> LabelDictionary.open(dir, "entities", "E"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not readable");
        }

        @Test
        @DisplayName("a file that is not a dictionary is refused by its magic")
        void wrongMagic(@TempDir Path dir) throws IOException {
            Files.write(dir.resolve("entities.idx"), new byte[32]);
            Files.write(dir.resolve("entities.dat"), new byte[0]);

            assertThatThrownBy(() -> LabelDictionary.open(dir, "entities", "E"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("magic");
        }

        @Test
        @DisplayName("a truncated index is refused rather than read past its end")
        void truncatedIndex(@TempDir Path dir) throws IOException {
            new LabelDictionaryWriter().put(1, "one").put(2, "two").write(dir, "entities");
            Path index = dir.resolve("entities.idx");
            byte[] full = Files.readAllBytes(index);
            Files.write(index, java.util.Arrays.copyOf(full, full.length - 8));

            assertThatThrownBy(() -> LabelDictionary.open(dir, "entities", "E"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("does not fit");
        }

        @Test
        @DisplayName("a header shorter than the format is refused")
        void shortHeader(@TempDir Path dir) throws IOException {
            Files.write(dir.resolve("entities.idx"), new byte[4]);
            Files.write(dir.resolve("entities.dat"), new byte[0]);

            assertThatThrownBy(() -> LabelDictionary.open(dir, "entities", "E"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("header");
        }

        @Test
        @DisplayName("a future version is refused rather than misread")
        void futureVersion(@TempDir Path dir) throws IOException {
            ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            header.putLong(LabelDictionary.MAGIC);
            header.putInt(LabelDictionary.VERSION + 1);
            header.putInt(0);
            Files.write(dir.resolve("entities.idx"), header.array());
            Files.write(dir.resolve("entities.dat"), new byte[0]);

            assertThatThrownBy(() -> LabelDictionary.open(dir, "entities", "E"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("version");
        }

        @Test
        @DisplayName("a blank name is refused at write time")
        void blankName() {
            assertThatThrownBy(() -> new LabelDictionaryWriter().put(1, "  "))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LabelDictionaryWriter().put(1, null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("rewriting replaces the previous dictionary in place")
    void rewriteReplaces(@TempDir Path dir) throws IOException {
        new LabelDictionaryWriter().put(1, "first").write(dir, "entities");
        new LabelDictionaryWriter().put(1, "second").put(2, "also").write(dir, "entities");

        try (LabelDictionary dictionary = LabelDictionary.open(dir, "entities", "E")) {
            assertThat(dictionary.apply(1)).isEqualTo("second");
            assertThat(dictionary.apply(2)).isEqualTo("also");
        }
        assertThat(Files.list(dir).map(p -> p.getFileName().toString()).toList())
            .containsExactlyInAnyOrder("entities.idx", "entities.dat");
    }

    @Test
    @DisplayName("names are the UTF-8 bytes, concatenated in id order")
    void dataFileIsTheConcatenatedText(@TempDir Path dir) throws IOException {
        new LabelDictionaryWriter().put(2, "second").put(1, "first").write(dir, "entities");

        assertThat(Files.readAllBytes(dir.resolve("entities.dat")))
            .isEqualTo("firstsecond".getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("A crash between the two renames")
    class MismatchedPair {

        @Test
        @DisplayName("an old index over a new data file is refused, not answered wrongly")
        void anOldIndexOverNewDataIsRefused(@TempDir Path dir) throws IOException {
            // The dictionary is two files published by two renames, and two renames cannot be one
            // atomic act. A crash between them pairs the previous .idx with the new .dat. Every
            // offset in the old index still lands inside the larger new blob, so the bounds check
            // passes and apply() hands back a slice of somebody else's name - silently, which for
            // a dictionary that names people after an erasure is the worst way to be wrong.
            new LabelDictionaryWriter().put(0, "alice").put(1, "bob").write(dir, "entities");
            byte[] oldIndex = Files.readAllBytes(dir.resolve("entities.idx"));

            new LabelDictionaryWriter()
                .put(0, "christopher").put(1, "bartholomew").put(2, "wilhelmina")
                .write(dir, "entities");
            Files.write(dir.resolve("entities.idx"), oldIndex);

            assertThatThrownBy(() -> LabelDictionary.open(dir, "entities", "E"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("entities.dat");
        }

        @Test
        @DisplayName("a new index over an old data file is refused as well")
        void aNewIndexOverOldDataIsRefused(@TempDir Path dir) throws IOException {
            new LabelDictionaryWriter().put(0, "alice").put(1, "bob").write(dir, "entities");
            byte[] oldData = Files.readAllBytes(dir.resolve("entities.dat"));

            new LabelDictionaryWriter()
                .put(0, "christopher").put(1, "bartholomew").put(2, "wilhelmina")
                .write(dir, "entities");
            Files.write(dir.resolve("entities.dat"), oldData);

            assertThatThrownBy(() -> LabelDictionary.open(dir, "entities", "E"))
                .isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("a matched pair opens as it always did")
        void aMatchedPairIsFine(@TempDir Path dir) throws IOException {
            new LabelDictionaryWriter().put(0, "alice").put(7, "bob").write(dir, "entities");

            try (LabelDictionary dictionary = LabelDictionary.open(dir, "entities", "E")) {
                assertThat(dictionary.apply(0)).isEqualTo("alice");
                assertThat(dictionary.apply(7)).isEqualTo("bob");
            }
        }
    }
}
