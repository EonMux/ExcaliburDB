package com.eonmux.excalibur.retrieval.lexical.native_;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What an index does when a column keeps its length and loses its contents.
 *
 * <p>{@link SegmentGeometry} measures every column against the counts in {@code index.meta} and
 * says so in its own comment: bytes that were rewritten rather than lost will pass. A flipped bit
 * in {@code post.dat} is exactly that - the file is the length the metadata describes, every
 * block header still addresses a region inside it, and the packed gaps it decodes to are simply
 * different numbers. The segment opens, the query runs, and the answer is wrong with nothing
 * anywhere to say so.</p>
 *
 * <p>These are the tests for the refusal that has to replace that: a damaged column names itself
 * and stops the open, and a store holding one keeps it out of every query rather than answering
 * from it.</p>
 */
@DisplayName("Lexical segment corruption")
class LexicalSegmentCorruptionTest {

    private static final String[] CORPUS = {
        "the OOM killer terminated the process after memory pressure",
        "the TLS handshake failed against the upstream certificate",
        "memory pressure triggered the OOM killer on the ingest node",
        "a slow query on the postings index blocked the ingest node",
    };

    /** Writes an index of {@link #CORPUS} and returns the directory holding it. */
    private static Path build(Path dir) throws IOException {
        PostingsWriter writer = new PostingsWriter();
        for (String document : CORPUS) {
            writer.add(document);
        }
        writer.write(dir);
        return dir;
    }

    /** Flips the low bit of one byte, leaving the file exactly the length it was. */
    static void flipByte(Path file, long offset) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {
            ByteBuffer one = ByteBuffer.allocate(1);
            if (channel.read(one, offset) != 1) {
                throw new IOException(file + " has no byte at " + offset);
            }
            one.flip();
            byte flipped = (byte) (one.get(0) ^ 0x01);
            channel.write(ByteBuffer.wrap(new byte[] {flipped}), offset);
        }
    }

    private static Map<String, String> docs(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static double[] ones(int n) {
        double[] weights = new double[n];
        java.util.Arrays.fill(weights, 1.0);
        return weights;
    }

    @Nested
    @DisplayName("a column whose bytes were rewritten rather than lost")
    class Rewritten {

        @Test
        @DisplayName("the packed payload is refused, naming the file that is damaged")
        void payload(@TempDir Path dir) throws Exception {
            build(dir);
            flipByte(dir.resolve(SegmentFiles.POST_DAT), 0);

            assertThatThrownBy(() -> PostingsIndex.open(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(SegmentFiles.POST_DAT);
        }

        @Test
        @DisplayName("the term hash is refused, naming the file that is damaged")
        void termHash(@TempDir Path dir) throws Exception {
            build(dir);
            flipByte(dir.resolve(SegmentFiles.TERMS_HASH), 4);

            assertThatThrownBy(() -> PostingsIndex.open(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(SegmentFiles.TERMS_HASH);
        }

        @Test
        @DisplayName("the length column is refused, naming the file that is damaged")
        void lengthColumn(@TempDir Path dir) throws Exception {
            build(dir);
            flipByte(dir.resolve(SegmentFiles.DOCS_LEN), 3);

            assertThatThrownBy(() -> PostingsIndex.open(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(SegmentFiles.DOCS_LEN);
        }

        @Test
        @DisplayName("the dictionary is refused, naming the file that is damaged")
        void dictionary(@TempDir Path dir) throws Exception {
            build(dir);
            flipByte(dir.resolve(SegmentFiles.TERMS_DICT), 6);

            assertThatThrownBy(() -> PostingsIndex.open(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(SegmentFiles.TERMS_DICT);
        }

        @Test
        @DisplayName("the refusal is a refusal, not an index that opens holding nothing")
        void neverOpensEmpty(@TempDir Path dir) throws Exception {
            build(dir);
            flipByte(dir.resolve(SegmentFiles.POST_DAT), 0);

            assertThatThrownBy(() -> PostingsIndex.open(dir)).isInstanceOf(IOException.class);
            // An index that loaded as empty would be the same corruption reported as an empty
            // corpus, which is the failure this whole check exists to prevent.
            assertThat(dir.resolve(SegmentFiles.POST_DAT)).exists();
        }
    }

    @Nested
    @DisplayName("a store holding one damaged segment")
    class Store {

        @Test
        @DisplayName("keeps it out of every query rather than answering from it")
        void queriesNeverReadTheDamagedSegment(@TempDir Path dir) throws Exception {
            String damaged;
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a1", "quorum replication latency",
                    "a2", "quorum election timeout"), 1);
                store.add(docs("b1", "quorum replication latency",
                    "b2", "quorum snapshot transfer"), 2);
                assertThat(store.search(new String[] {"quorum"}, ones(1), 10, 9)).hasSize(4);
                damaged = store.segments().get(0).name();
            }
            flipByte(dir.resolve(damaged).resolve(SegmentFiles.POST_DAT), 0);

            try (LexicalStore reopened = LexicalStore.open(dir)) {
                assertThat(reopened.unreadableSegments()).containsKey(damaged);
                assertThat(reopened.unreadableSegments().get(damaged))
                    .contains(SegmentFiles.POST_DAT);

                List<LexicalStore.Hit> hits =
                    reopened.search(new String[] {"quorum"}, ones(1), 10, 9);

                assertThat(hits).extracting(LexicalStore.Hit::key)
                    .containsExactlyInAnyOrder("b1", "b2");
            }
        }

        @Test
        @DisplayName("says so, rather than reporting a corpus that has shrunk")
        void reportsRatherThanForgets(@TempDir Path dir) throws Exception {
            String damaged;
            try (LexicalStore store = LexicalStore.open(dir)) {
                store.add(docs("a1", "quorum replication latency"), 1);
                store.add(docs("b1", "quorum snapshot transfer"), 2);
                damaged = store.segments().get(0).name();
            }
            flipByte(dir.resolve(damaged).resolve(SegmentFiles.DOCS_LEN), 1);

            try (LexicalStore reopened = LexicalStore.open(dir)) {
                assertThat(reopened.unreadableSegments()).hasSize(1);
                // Still named by the manifest and still counted, so nothing deletes the bytes
                // somebody may yet recover and nothing reports the documents as gone.
                assertThat(reopened.segments()).hasSize(2);
                assertThat(reopened.liveDocuments(9)).isEqualTo(2);
            }
        }
    }
}
