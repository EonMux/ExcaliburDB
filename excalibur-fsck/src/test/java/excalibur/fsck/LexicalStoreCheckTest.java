package excalibur.fsck;

import excalibur.fsck.check.CheckStatus;
import excalibur.fsck.check.LexicalStoreCheck;
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
 * cannot. The manifest itself carries no checksum, only a record count, and the verdicts
 * here are deliberately careful to claim no more than that count proves.</p>
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

    @Test
    void aWholeManifestIsUnprotectedRatherThanIntact(@TempDir Path dir) throws IOException {
        Path store = Files.createDirectories(dir.resolve("chunk-index"));
        Files.writeString(store.resolve("manifest"), "excalibur-lexical-store v2\n#end\t0\n");

        var result = LexicalStoreCheck.check(store, "chunk-index");

        assertThat(result.manifest().status()).isEqualTo(CheckStatus.LEGACY_UNPROTECTED);
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
