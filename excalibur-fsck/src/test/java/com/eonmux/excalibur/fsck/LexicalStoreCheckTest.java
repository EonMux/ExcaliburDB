package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.DataDirectoryScanner;
import com.eonmux.excalibur.fsck.check.LexicalStoreCheck;
import com.eonmux.excalibur.fsck.repair.RepairPlan;
import com.eonmux.excalibur.fsck.repair.RepairPlanner;
import com.eonmux.excalibur.retrieval.lexical.native_.SegmentManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lexical store's manifest, and the segments it claims exist.
 *
 * <p>The manifest is the store's commit record, so a segment it names and the directory
 * does not hold is not a tidy-up job - it is an index a reader will try to open and
 * cannot. The current format ends with a record count and a checksum over every byte before
 * it; the two older ones carry the count alone, or nothing, and the verdicts on those are
 * deliberately careful to claim no more than what they carry proves.</p>
 */
class LexicalStoreCheckTest {

    @Test
    void aStoreDirectoryWithNoManifestNamesNothing(@TempDir Path dir) throws IOException {
        Path store = Files.createDirectories(dir.resolve("chunk-index"));

        var result = LexicalStoreCheck.check(store, "chunk-index");

        assertThat(result.manifest().status()).isEqualTo(CheckStatus.MISSING);
        assertThat(result.manifest().relative()).isEqualTo("chunk-index/manifest");
        assertThat(result.segments()).isEmpty();
    }

    @Test
    void aManifestWhoseCountDisagreesIsCorrupt(@TempDir Path dir) throws IOException {
        Path store = Files.createDirectories(dir.resolve("chunk-index"));
        Files.writeString(store.resolve("manifest"),
            "excalibur-lexical-store v2\nseg-0\t1\t10\t0\n#end\t5\n");

        var result = LexicalStoreCheck.check(store, "chunk-index");

        assertThat(result.manifest().status()).isEqualTo(CheckStatus.CORRUPT);
    }

    @Test
    void aManifestThatIsNotOneAtAllIsCorrupt(@TempDir Path dir) throws IOException {
        Path store = Files.createDirectories(dir.resolve("chunk-index"));
        Files.writeString(store.resolve("manifest"), "this is not a manifest\n");

        var result = LexicalStoreCheck.check(store, "chunk-index");

        assertThat(result.manifest().status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.manifest().detail()).contains("excalibur-lexical-store");
    }

    @Test
    void aVersionOneManifestCannotBeShownToBeWhole(@TempDir Path dir) throws IOException {
        Path store = Files.createDirectories(dir.resolve("chunk-index"));
        Files.writeString(store.resolve("manifest"), "excalibur-lexical-store v1\n");

        var result = LexicalStoreCheck.check(store, "chunk-index");

        assertThat(result.manifest().status())
            .as("a manifest with no count proves nothing, which is not the same as damage")
            .isEqualTo(CheckStatus.UNCHECKED);
        assertThat(result.manifest().version()).isEqualTo("v1");
    }

    /**
     * Writes a manifest at the current version, through the store's own writer.
     *
     * @param store   The store directory
     * @param entries The segments it names
     */
    private static void writeCurrent(Path store, SegmentManifest.Entry... entries)
            throws IOException {
        SegmentManifest manifest = SegmentManifest.empty();
        for (SegmentManifest.Entry entry : entries) {
            manifest.add(entry);
        }
        manifest.write(store);
    }

    @Test
    void aCurrentManifestWhoseChecksumMatchesIsIntact(@TempDir Path dir) throws IOException {
        Path store = Files.createDirectories(dir.resolve("chunk-index"));
        writeCurrent(store);

        var result = LexicalStoreCheck.check(store, "chunk-index");

        assertThat(result.manifest().status()).isEqualTo(CheckStatus.INTACT);
        assertThat(result.manifest().version()).isEqualTo("v" + SegmentManifest.VERSION);
        assertThat(result.manifest().detail()).contains("checksum");
        assertThat(result.manifest().unverifiedContent()).isFalse();
    }

    @Test
    void aCurrentManifestWithAFlippedByteIsCorruptAndLeftToAnOperator(@TempDir Path tempDir)
            throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 8);
        Path store = Files.createDirectories(dataDir.resolve("chunk-index"));
        writeCurrent(store, new SegmentManifest.Entry("seg-0", 1, 10, 0));
        Path manifest = store.resolve("manifest");
        Files.writeString(manifest, Files.readString(manifest).replace("\t10\t", "\t19\t"));

        var result = LexicalStoreCheck.check(store, "chunk-index");

        assertThat(result.manifest().status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.manifest().detail()).contains("checksum did not match");
        assertThat(result.segments())
            .as("nothing a manifest that failed its check names is taken on its word")
            .isEmpty();

        RepairPlan plan = RepairPlanner.plan(new DataDirectoryScanner(dataDir).scan());
        assertThat(plan.actions())
            .as("the manifest is never moved aside: a store without one sweeps every segment")
            .noneSatisfy(action ->
                assertThat(action.relative()).isEqualTo("chunk-index/manifest"));
        assertThat(plan.needsOperator())
            .anySatisfy(advice -> assertThat(advice).contains("chunk-index/manifest")
                .contains("checksum did not match"));
    }

    @Test
    void aCurrentManifestCutShortIsCorrupt(@TempDir Path dir) throws IOException {
        Path store = Files.createDirectories(dir.resolve("chunk-index"));
        writeCurrent(store, new SegmentManifest.Entry("seg-0", 1, 10, 0),
            new SegmentManifest.Entry("seg-1", 2, 5, 0));
        Path manifest = store.resolve("manifest");
        byte[] bytes = Files.readAllBytes(manifest);
        Files.write(manifest, java.util.Arrays.copyOf(bytes, bytes.length - 12));

        var result = LexicalStoreCheck.check(store, "chunk-index");

        assertThat(result.manifest().status()).isEqualTo(CheckStatus.CORRUPT);
    }

    @Test
    void aManifestFromANewerBuildIsUnreadableRatherThanCorrupt(@TempDir Path dir)
            throws IOException {
        Path store = Files.createDirectories(dir.resolve("chunk-index"));
        int newer = SegmentManifest.VERSION + 1;
        Files.writeString(store.resolve("manifest"),
            "excalibur-lexical-store v" + newer + "\n#end\t0\t00000000\n");

        var result = LexicalStoreCheck.check(store, "chunk-index");

        assertThat(result.manifest().status()).isEqualTo(CheckStatus.UNREADABLE);
        assertThat(result.manifest().version()).isEqualTo("v" + newer);
        assertThat(result.manifest().detail())
            .contains("version " + newer)
            .contains("versions " + SegmentManifest.OLDEST_VERSION + " to "
                + SegmentManifest.VERSION);
    }

    @Test
    void aCountedManifestIsUnprotectedRatherThanIntact(@TempDir Path dir) throws IOException {
        Path store = Files.createDirectories(dir.resolve("chunk-index"));
        Files.writeString(store.resolve("manifest"), "excalibur-lexical-store v2\n#end\t0\n");

        var result = LexicalStoreCheck.check(store, "chunk-index");

        assertThat(result.manifest().status()).isEqualTo(CheckStatus.LEGACY_UNPROTECTED);
        assertThat(result.manifest().version()).isEqualTo("v2");
        assertThat(result.manifest().detail()).contains("no line was lost");
        assertThat(result.segments()).isEmpty();
    }

    @Test
    void aSegmentTheManifestNamesAndTheDirectoryLacksIsReported(@TempDir Path dir)
            throws IOException {
        Path store = Files.createDirectories(dir.resolve("chunk-index"));
        Files.writeString(store.resolve("manifest"),
            "excalibur-lexical-store v2\nseg-0\t1\t10\t0\n#end\t1\n");

        var result = LexicalStoreCheck.check(store, "chunk-index");

        assertThat(result.manifest().status()).isEqualTo(CheckStatus.LEGACY_UNPROTECTED);
        assertThat(result.segments()).hasSize(1);
        assertThat(result.segments().get(0).status())
            .as("a named segment that is not there is not merely unchecked")
            .isIn(CheckStatus.MISSING, CheckStatus.CORRUPT, CheckStatus.UNREADABLE);
        assertThat(result.segments().get(0).relative()).isEqualTo("chunk-index/seg-0");
    }

    @Test
    void aStoreDirectoryIsSurveyedByTheWholeCommand(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        DataDirectoryFixture.createStore(dataDir, 8);
        Path store = Files.createDirectories(dataDir.resolve("chunk-index"));
        Files.writeString(store.resolve("manifest"), "excalibur-lexical-store v2\n#end\t0\n");
        Files.writeString(store.resolve("seg-0.keys"), "doc-1\n");

        var out = new java.io.ByteArrayOutputStream();
        int exitCode;
        try (var stream = new java.io.PrintStream(out, true,
                java.nio.charset.StandardCharsets.UTF_8)) {
            exitCode = ExcaliburFsck.run(new String[] { dataDir.toString() }, stream, stream);
        }
        String report = out.toString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(exitCode).isZero();
        assertThat(report).contains("chunk-index/manifest");
        assertThat(report).contains("chunk-index/seg-0.keys");
        assertThat(report).contains("lexical store sidecar");
    }
}
