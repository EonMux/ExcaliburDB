package excalibur.fsck;

import java.nio.file.Path;
import java.util.Objects;

/**
 * What one invocation was asked to do.
 *
 * @param dataDirectory      The directory to survey
 * @param repair             Whether the safe repairs are to be applied, not merely stated
 * @param quarantineDirectory Where moved-aside files go
 * @param json               Whether to emit machine-readable output instead of a report
 * @param strict             Whether a file that could not be verified should fail the run
 */
public record FsckOptions(
    Path dataDirectory,
    boolean repair,
    Path quarantineDirectory,
    boolean json,
    boolean strict) {

    /**
     * Validates the options.
     *
     * @throws NullPointerException if the data or quarantine directory is null
     */
    public FsckOptions {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(quarantineDirectory, "quarantineDirectory");
    }
}
