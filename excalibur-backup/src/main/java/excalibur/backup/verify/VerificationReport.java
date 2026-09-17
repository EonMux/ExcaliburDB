package excalibur.backup.verify;

import excalibur.backup.manifest.SnapshotManifest;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What a verification found.
 *
 * <p>It runs to the end rather than stopping at the first fault, because the question an
 * operator has is how much of the image is wrong rather than whether any of it is - the
 * same reason the storage engine's own segment walk reports a list instead of throwing.</p>
 *
 * @param snapshotDir   The image that was checked
 * @param manifest      What it claimed to contain
 * @param filesChecked  How many files were examined
 * @param bytesChecked  How many bytes were read
 * @param problems      Everything found wrong, in path order; empty means the image is good
 * @param contentChecks What each format said about itself, empty unless the check was deep
 * @author ExcaliburDB
 */
public record VerificationReport(
    Path snapshotDir,
    SnapshotManifest manifest,
    int filesChecked,
    long bytesChecked,
    List<Problem> problems,
    List<ContentCheck> contentChecks) {

    /**
     * Canonical constructor, copying both lists.
     *
     * @throws NullPointerException if any argument is null
     */
    public VerificationReport {
        Objects.requireNonNull(snapshotDir, "snapshotDir");
        Objects.requireNonNull(manifest, "manifest");
        problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
        contentChecks = List.copyOf(Objects.requireNonNull(contentChecks, "contentChecks"));
    }

    /**
     * Reports whether the image is fit to restore.
     *
     * @return true if nothing was found wrong
     */
    public boolean isValid() {
        return problems.isEmpty();
    }
}
