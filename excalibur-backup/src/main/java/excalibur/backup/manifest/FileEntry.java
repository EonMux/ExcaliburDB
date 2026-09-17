package excalibur.backup.manifest;

import java.util.Objects;

/**
 * One file in a snapshot, as the manifest describes it.
 *
 * <p>The path is relative to the payload directory and always uses forward slashes, so a
 * snapshot taken on one platform can be verified and restored on another.</p>
 *
 * @param path   Path relative to the payload root, forward-slashed, never absolute
 * @param size   The file's length in bytes
 * @param crc32c CRC32C of every byte, lowercase hex
 * @param sha256 SHA-256 of every byte, lowercase hex
 * @author ExcaliburDB
 */
public record FileEntry(String path, long size, String crc32c, String sha256) {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if the path is empty, absolute, backslashed or
     *                                  contains a {@code ..} segment, or the size is
     *                                  negative
     */
    public FileEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(crc32c, "crc32c");
        Objects.requireNonNull(sha256, "sha256");
        if (path.isBlank()) {
            throw new IllegalArgumentException("A manifest entry must name a file");
        }
        if (path.startsWith("/") || path.contains("\\")) {
            throw new IllegalArgumentException("A manifest path is relative and "
                + "forward-slashed, so it cannot be " + path);
        }
        if (path.equals("..") || path.startsWith("../") || path.contains("/../")
            || path.endsWith("/..")) {
            throw new IllegalArgumentException("A manifest path may not climb out of the "
                + "payload directory: " + path);
        }
        if (path.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A manifest is one line per file, so a path "
                + "cannot contain a control character: " + path);
        }
        if (size < 0) {
            throw new IllegalArgumentException("A file cannot be " + size + " bytes long");
        }
    }
}
