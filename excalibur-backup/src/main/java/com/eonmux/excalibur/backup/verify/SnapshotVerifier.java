package com.eonmux.excalibur.backup.verify;

import com.eonmux.excalibur.backup.io.FileDigest;
import com.eonmux.excalibur.backup.io.FileDigests;
import com.eonmux.excalibur.backup.manifest.FileEntry;
import com.eonmux.excalibur.backup.manifest.ManifestCodec;
import com.eonmux.excalibur.backup.manifest.SnapshotManifest;
import com.eonmux.excalibur.backup.snapshot.SnapshotLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks a snapshot against its manifest without restoring it.
 *
 * <h2>Both directions</h2>
 * <p>Every file the manifest names is looked for, measured and checksummed; and every file
 * present is looked for in the manifest. The second direction is the one that is easy to
 * leave out and the one that catches a half-finished copy being mistaken for a whole one,
 * or two images written over each other in the same directory.</p>
 *
 * <h2>What deep adds</h2>
 * <p>A checksum proves a file is the one that was copied, not that it was sound when it was
 * copied: a graph whose seal was already broken on the source is copied faithfully and
 * verifies faithfully. A deep verification also compares every file's SHA-256, and hands
 * every file to the checker for its format through {@link FormatChecks}, so an image that
 * holds damage the node itself would refuse to load is failed here rather than at restore.
 * Each format's verdict is kept in the report whether or not it found anything, because a
 * file checked and unproven is something an operator should be able to see.</p>
 *
 * <h2>Size before checksum</h2>
 * <p>A file whose length is wrong is reported as such rather than as a checksum mismatch.
 * They are the same verdict and different diagnoses: a truncated file is a copy that was
 * interrupted or a filesystem that ran out of room, and a full-length file with a bad
 * checksum is rot. Telling an operator which one they have is most of the value of saying
 * anything at all.</p>
 *
 * @author ExcaliburDB
 */
public final class SnapshotVerifier {

    /**
     * Verifies a snapshot.
     *
     * @param snapshotDir The image
     * @param depth       How hard to look
     * @return What was found; the image is fit to restore if the report has no problems
     * @throws IOException          if the manifest cannot be read or believed, or the
     *                              payload cannot be walked
     * @throws NullPointerException if either argument is null
     */
    public VerificationReport verify(Path snapshotDir, VerifyDepth depth) throws IOException {
        Objects.requireNonNull(snapshotDir, "snapshotDir");
        Objects.requireNonNull(depth, "depth");
        SnapshotManifest manifest =
            ManifestCodec.read(snapshotDir.resolve(ManifestCodec.FILE_NAME));
        Path payload = snapshotDir.resolve(SnapshotLayout.PAYLOAD_DIRECTORY);

        List<Problem> problems = new ArrayList<>();
        List<ContentCheck> contentChecks = new ArrayList<>();
        long bytesChecked = 0L;
        int filesChecked = 0;
        for (FileEntry entry : manifest.files()) {
            bytesChecked += checkOne(payload, entry, depth, problems, contentChecks);
            filesChecked++;
        }
        problems.addAll(unexpectedFiles(payload, manifest));
        if (depth == VerifyDepth.DEEP) {
            List<ContentCheck> folded = FormatChecks.withGraphStores(payload,
                FormatChecks.withLuceneChunkIndex(payload,
                    FormatChecks.withLexicalStore(payload, contentChecks)));
            contentChecks.clear();
            contentChecks.addAll(folded);
            contentChecks.sort(Comparator.comparing(ContentCheck::path));
        }
        contentChecks.forEach(check -> {
            if (!check.healthy()) {
                problems.add(new Problem(check.path(),
                    check.format() + " integrity: " + check.detail()));
            }
        });
        problems.sort(Comparator.comparing(Problem::path));
        return new VerificationReport(snapshotDir, manifest, filesChecked, bytesChecked,
            problems, contentChecks);
    }

    /**
     * Checks one file the manifest names.
     *
     * @param payload       The payload root
     * @param entry         What the manifest says about it
     * @param depth         How hard to look
     * @param problems      Receives anything wrong
     * @param contentChecks Receives the format's own verdict, when the check is deep
     * @return How many bytes were read
     * @throws IOException if scratch space for a deep check cannot be created
     */
    private long checkOne(Path payload, FileEntry entry, VerifyDepth depth,
                          List<Problem> problems, List<ContentCheck> contentChecks)
        throws IOException {
        Path file = payload.resolve(entry.path());
        if (!Files.isRegularFile(file)) {
            problems.add(new Problem(entry.path(), "the manifest names this file and it is "
                + "missing from the image"));
            return 0L;
        }
        FileDigest actual;
        try {
            actual = FileDigests.of(file);
        } catch (IOException e) {
            problems.add(new Problem(entry.path(), "this file cannot be read: " + e));
            return 0L;
        }
        if (actual.size() != entry.size()) {
            problems.add(new Problem(entry.path(), "the manifest gives its size as "
                + entry.size() + " bytes and it is " + actual.size()
                + "; the copy was truncated or extended"));
            return actual.size();
        }
        checkChecksums(entry, actual, depth, problems);
        if (depth == VerifyDepth.DEEP) {
            contentChecks.add(FormatChecks.check(file, entry.path()));
        }
        return actual.size();
    }

    /**
     * Compares the checksums the manifest recorded against the bytes that are there.
     *
     * @param entry    What the manifest says
     * @param actual   What was read
     * @param depth    How hard to look
     * @param problems Receives a mismatch
     */
    private static void checkChecksums(FileEntry entry, FileDigest actual, VerifyDepth depth,
                                       List<Problem> problems) {
        if (!actual.crc32c().equals(entry.crc32c())) {
            problems.add(new Problem(entry.path(), "the manifest records its checksum as "
                + entry.crc32c() + " and its bytes compute to " + actual.crc32c()
                + "; this file has been damaged since the snapshot was taken"));
            return;
        }
        if (depth == VerifyDepth.DEEP && !actual.sha256().equals(entry.sha256())) {
            problems.add(new Problem(entry.path(), "the manifest records its SHA-256 as "
                + entry.sha256() + " and its bytes compute to " + actual.sha256()
                + "; the checksum matched, so this file has been deliberately altered"));
        }
    }

    /**
     * Finds files in the payload that the manifest does not account for.
     *
     * @param payload  The payload root
     * @param manifest What the image claims to contain
     * @return One problem per unaccounted file, in path order
     * @throws IOException if the payload cannot be walked
     */
    private static List<Problem> unexpectedFiles(Path payload, SnapshotManifest manifest)
        throws IOException {
        if (!Files.isDirectory(payload)) {
            return List.of(new Problem("", "the snapshot has no "
                + SnapshotLayout.PAYLOAD_DIRECTORY + " directory, so it holds no data at all"));
        }
        Set<String> named = new HashSet<>();
        manifest.files().forEach(entry -> named.add(entry.path()));
        Set<String> present = new TreeSet<>();
        try (var walk = Files.walk(payload)) {
            walk.filter(Files::isRegularFile)
                .forEach(file -> present.add(
                    payload.relativize(file).toString().replace('\\', '/')));
        }
        present.removeAll(named);
        return present.stream()
            .map(path -> new Problem(path, "this file is in the image and is not named by "
                + "the manifest, so the image is not the one the manifest describes"))
            .toList();
    }
}
