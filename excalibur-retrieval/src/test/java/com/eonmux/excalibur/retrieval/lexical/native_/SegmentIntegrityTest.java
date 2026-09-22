package com.eonmux.excalibur.retrieval.lexical.native_;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What an index does when its files do not agree with the counts that describe them.
 *
 * <p>Six files are mapped at open and every one of them is addressed by arithmetic on the three
 * counts in {@code index.meta}. A file that lost its tail is therefore not a read error: it is a
 * shorter postings row, an empty block range, a dictionary entry that lands on other bytes - all
 * of which decode into an answer, and none of which is the right one. A store that answers a
 * query from half a segment and says nothing is worse than one that refuses to open, so these
 * fix that every column is measured against the geometry the metadata declares before a single
 * posting is read.
 */
@DisplayName("Segment integrity")
class SegmentIntegrityTest {

    private static final String[] CORPUS = {
        "the OOM killer terminated the process after memory pressure",
        "the TLS handshake failed against the upstream certificate",
        "memory pressure triggered the OOM killer on the ingest node",
        "a slow query on the postings index blocked the ingest node",
    };

    private static Path build(Path dir) throws IOException {
        PostingsWriter writer = new PostingsWriter();
        for (String document : CORPUS) {
            writer.add(document);
        }
        writer.write(dir);
        return dir;
    }

    private static void truncateBy(Path file, long bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(Math.max(0, channel.size() - bytes));
        }
    }

    private static void zero(Path file) throws IOException {
        Files.write(file, new byte[(int) Files.size(file)]);
    }

    private static void assertRefuses(Path dir) {
        assertThatThrownBy(() -> PostingsIndex.open(dir)).isInstanceOf(IOException.class);
    }

    @Nested
    @DisplayName("an intact segment")
    class Intact {

        @Test
        @DisplayName("opens and answers, so the checks are not merely strict")
        void opens(@TempDir Path dir) throws Exception {
            build(dir);
            try (PostingsIndex index = PostingsIndex.open(dir)) {
                assertThat(index.termCount()).isPositive();
                assertThat(index.docCount()).isEqualTo(CORPUS.length);
                assertThat(index.termId("oom")).isNotNegative();
            }
        }

        @Test
        @DisplayName("leaves no half-written metadata beside the file it published")
        void leavesNoScratchFile(@TempDir Path dir) throws Exception {
            build(dir);

            try (var listing = Files.list(dir)) {
                assertThat(listing.map(path -> path.getFileName().toString()))
                    .containsExactlyInAnyOrder("index.meta", "terms.dict", "terms.hash",
                        "post.off", "post.blk", "post.dat", "docs.len");
            }
        }
    }

    @Nested
    @DisplayName("a column that lost its tail")
    class Truncated {

        @Test
        @DisplayName("the row index is refused rather than read as shorter rows")
        void rowIndex(@TempDir Path dir) throws Exception {
            truncateBy(build(dir).resolve("post.off"), 8);
            assertRefuses(dir);
        }

        @Test
        @DisplayName("the block column is refused rather than read as fewer blocks")
        void blockColumn(@TempDir Path dir) throws Exception {
            truncateBy(build(dir).resolve("post.blk"), BlockLayout.RECORD_BYTES);
            assertRefuses(dir);
        }

        @Test
        @DisplayName("the packed payload is refused rather than decoded past its end")
        void payload(@TempDir Path dir) throws Exception {
            truncateBy(build(dir).resolve("post.dat"), 1);
            assertRefuses(dir);
        }

        @Test
        @DisplayName("the dictionary is refused rather than compared against other bytes")
        void dictionary(@TempDir Path dir) throws Exception {
            truncateBy(build(dir).resolve("terms.dict"), 2);
            assertRefuses(dir);
        }

        @Test
        @DisplayName("the term hash is refused rather than probed round a shorter table")
        void termHash(@TempDir Path dir) throws Exception {
            truncateBy(build(dir).resolve("terms.hash"), 8);
            assertRefuses(dir);
        }

        @Test
        @DisplayName("the length column is refused rather than normalised against zero")
        void lengthColumn(@TempDir Path dir) throws Exception {
            truncateBy(build(dir).resolve("docs.len"), 4);
            assertRefuses(dir);
        }
    }

    @Nested
    @DisplayName("a column of the right size holding the wrong bytes")
    class Zeroed {

        @Test
        @DisplayName("a zeroed row index is refused rather than answered as an empty index")
        void rowIndex(@TempDir Path dir) throws Exception {
            // The failure this whole check exists for: every row then starts and ends at block
            // zero, so every term in the dictionary resolves and matches nothing. The query
            // comes back empty and correct-looking.
            zero(build(dir).resolve("post.off"));
            assertRefuses(dir);
        }

        @Test
        @DisplayName("a zeroed block column is refused rather than scored as one empty block")
        void blockColumn(@TempDir Path dir) throws Exception {
            zero(build(dir).resolve("post.blk"));
            assertRefuses(dir);
        }
    }
}
