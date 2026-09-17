package excalibur.backup.manifest;

import java.io.IOException;
import java.nio.file.Path;

/**
 * A manifest that cannot be believed.
 *
 * <p>Distinct from a plain {@link IOException} because the two call for different
 * responses: a file that cannot be read may be a permissions problem, while a file that
 * reads perfectly well and does not check out is an image nobody should restore. Every
 * case here means the latter.</p>
 *
 * @author ExcaliburDB
 */
public final class ManifestFormatException extends IOException {

    private static final long serialVersionUID = 1L;

    /** The manifest that could not be believed. */
    private final transient Path file;

    /**
     * Creates the exception.
     *
     * @param file   The manifest in question
     * @param detail What is wrong with it
     */
    public ManifestFormatException(Path file, String detail) {
        super(file + ": " + detail);
        this.file = file;
    }

    /**
     * Returns the manifest that could not be believed.
     *
     * @return Its path
     */
    public Path file() {
        return file;
    }
}
