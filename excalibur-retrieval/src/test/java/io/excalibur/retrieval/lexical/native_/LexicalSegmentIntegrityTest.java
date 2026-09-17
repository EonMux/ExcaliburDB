package io.excalibur.retrieval.lexical.native_;

import io.excalibur.core.integrity.IntegrityReport;
import io.excalibur.core.integrity.IntegrityStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.excalibur.retrieval.lexical.native_.LexicalSegmentCorruptionTest.flipByte;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lexical segment's verification entry point, and what it says about older indexes.
 *
 * <p>Verification exists for tooling that has to survey every file a node owns, so it must reach
 * a verdict on an index it never opens, it must name the column that is damaged rather than the
 * directory, and it must distinguish a column it checked from one it could not check.</p>
 */
@DisplayName("Lexical segment verification")
class LexicalSegmentIntegrityTest {

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

    /**
     * Rewrites the metadata in the shape a build before this change produced: the first forty
     * bytes only, stamped with the version that wrote them and carrying no checksums at all.
     */
    private static void downgradeToVersion(Path dir, int version) throws IOException {
        Path meta = dir.resolve(IndexMeta.FILE_NAME);
        byte[] current = Files.readAllBytes(meta);
        byte[] legacy = java.util.Arrays.copyOf(current, IndexMeta.BYTES_WITH_BM25);
        ByteBuffer.wrap(legacy).order(ByteOrder.BIG_ENDIAN).putInt(12, version);
        Files.write(meta, legacy);
    }

    @Nested
    @DisplayName("an intact index")
    class Intact {

        @Test
        @DisplayName("verifies without being opened, and says its bytes were actually checked")
        void verifiesIntact(@TempDir Path dir) throws Exception {
            build(dir);

            IntegrityReport report = PostingsIndex.verifyIntegrity(dir);

            assertThat(report.status()).isEqualTo(IntegrityStatus.INTACT);
            assertThat(report.verified()).isTrue();
            assertThat(report.loadable()).isTrue();
        }

        @Test
        @DisplayName("the streaming writer protects its output the same way the accumulating "
            + "one does")
        void bothWritersProtectTheirOutput(@TempDir Path dir) throws Exception {
            // A merge writes through the streaming writer rather than the accumulating one, so
            // a segment produced by a compaction would otherwise be the one kind nothing covers.
            int[] lengths = {3, 3};
            try (StreamingIndexWriter writer = new StreamingIndexWriter(dir, lengths)) {
                writer.addTerm("alpha", new int[] {0, 1}, new int[] {1, 2}, 2);
                writer.addTerm("beta", new int[] {1}, new int[] {1}, 1);
                writer.finish();
            }

            assertThat(PostingsIndex.verifyIntegrity(dir).status())
                .isEqualTo(IntegrityStatus.INTACT);

            flipByte(dir.resolve(SegmentFiles.POST_BLK), 0);

            assertThat(PostingsIndex.verifyIntegrity(dir).status())
                .isEqualTo(IntegrityStatus.CORRUPT);
        }

        @Test
        @DisplayName("records a checksum for every column the reader maps")
        void everyColumnIsCovered(@TempDir Path dir) throws Exception {
            build(dir);

            assertThat(Files.size(dir.resolve(IndexMeta.FILE_NAME)))
                .isEqualTo(IndexMeta.BYTES_WITH_COLUMN_CHECKSUMS);
            for (String column : SegmentFiles.COLUMNS) {
                flipByte(dir.resolve(column), Files.size(dir.resolve(column)) - 1);
                assertThat(PostingsIndex.verifyIntegrity(dir).status())
                    .as("a flipped byte in %s must be seen", column)
                    .isEqualTo(IntegrityStatus.CORRUPT);
                flipByte(dir.resolve(column), Files.size(dir.resolve(column)) - 1);
            }
            assertThat(PostingsIndex.verifyIntegrity(dir).status())
                .isEqualTo(IntegrityStatus.INTACT);
        }
    }

    @Nested
    @DisplayName("a damaged index")
    class Damaged {

        @Test
        @DisplayName("names the column that is damaged, not the directory it sits in")
        void namesTheColumn(@TempDir Path dir) throws Exception {
            build(dir);
            flipByte(dir.resolve(SegmentFiles.POST_DAT), 0);

            IntegrityReport report = PostingsIndex.verifyIntegrity(dir);

            assertThat(report.status()).isEqualTo(IntegrityStatus.CORRUPT);
            assertThat(report.loadable()).isFalse();
            assertThat(report.file()).isEqualTo(dir.resolve(SegmentFiles.POST_DAT));
            assertThat(report.detail()).contains("checksum");
        }

        @Test
        @DisplayName("reports metadata whose own checksum no longer covers it")
        void namesTheMetadata(@TempDir Path dir) throws Exception {
            build(dir);
            // The document count, which every other file's geometry is derived from.
            flipByte(dir.resolve(IndexMeta.FILE_NAME), 7);

            IntegrityReport report = PostingsIndex.verifyIntegrity(dir);

            assertThat(report.status()).isEqualTo(IntegrityStatus.CORRUPT);
            assertThat(report.file()).isEqualTo(dir.resolve(IndexMeta.FILE_NAME));
        }

        @Test
        @DisplayName("reaches a verdict without mapping anything or opening the index")
        void reachesAVerdictWithoutOpening(@TempDir Path dir) throws Exception {
            build(dir);
            flipByte(dir.resolve(SegmentFiles.POST_DAT), 0);

            // No throw, no mapping, no index in service: a checking tool has to survive files
            // it would never be willing to serve.
            assertThat(PostingsIndex.verifyIntegrity(dir).status())
                .isEqualTo(IntegrityStatus.CORRUPT);
        }

        @Test
        @DisplayName("an index that is not there is missing rather than intact")
        void missingIndex(@TempDir Path dir) {
            assertThat(PostingsIndex.verifyIntegrity(dir.resolve("absent")).status())
                .isEqualTo(IntegrityStatus.MISSING);
        }
    }

    @Nested
    @DisplayName("an index written before checksums existed")
    class Legacy {

        @Test
        @DisplayName("is named as unprotected rather than refused")
        void namedUnprotected(@TempDir Path dir) throws Exception {
            build(dir);
            downgradeToVersion(dir, IndexMeta.VERSION_WITH_STATISTIC_FREE_BLOCKS);

            IntegrityReport report = PostingsIndex.verifyIntegrity(dir);

            assertThat(report.status()).isEqualTo(IntegrityStatus.LEGACY_UNPROTECTED);
            assertThat(report.loadable()).isTrue();
            assertThat(report.verified()).isFalse();
        }

        @Test
        @DisplayName("still opens and still answers, at its own version")
        void stillOpens(@TempDir Path dir) throws Exception {
            build(dir);
            downgradeToVersion(dir, IndexMeta.VERSION_WITH_STATISTIC_FREE_BLOCKS);

            try (PostingsIndex index = PostingsIndex.open(dir)) {
                assertThat(index.docCount()).isEqualTo(CORPUS.length);
                assertThat(index.termId("oom")).isNotNegative();
            }
        }

        @Test
        @DisplayName("is not upgraded in place, because nothing ever vouched for its bytes")
        void isNotUpgradedInPlace(@TempDir Path dir) throws Exception {
            build(dir);
            downgradeToVersion(dir, IndexMeta.VERSION_WITH_STATISTIC_FREE_BLOCKS);
            byte[] before = Files.readAllBytes(dir.resolve(IndexMeta.FILE_NAME));

            PostingsIndex.open(dir).close();
            PostingsIndex.verifyIntegrity(dir);

            assertThat(Files.readAllBytes(dir.resolve(IndexMeta.FILE_NAME))).isEqualTo(before);
        }

        @Test
        @DisplayName("gains protection when it is next rewritten, with nothing to migrate")
        void rebuildingProtectsIt(@TempDir Path dir) throws Exception {
            build(dir);
            downgradeToVersion(dir, IndexMeta.VERSION_WITH_STATISTIC_FREE_BLOCKS);
            assertThat(PostingsIndex.verifyIntegrity(dir).status())
                .isEqualTo(IntegrityStatus.LEGACY_UNPROTECTED);

            build(dir);

            assertThat(PostingsIndex.verifyIntegrity(dir).status())
                .isEqualTo(IntegrityStatus.INTACT);
        }
    }
}
