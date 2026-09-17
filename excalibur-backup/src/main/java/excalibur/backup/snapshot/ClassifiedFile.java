package excalibur.backup.snapshot;

import java.util.Objects;

/**
 * One file in a data directory, with the decision made about it and the reason for it.
 *
 * <p>The reason is carried rather than derived at the point of printing, because the
 * question an operator asks of a backup tool is not "what did you copy" but "why is that
 * not in there", and an answer assembled after the fact is an answer that can disagree
 * with what was done.</p>
 *
 * @param path      Path relative to the data directory, forward-slashed
 * @param inclusion What was decided
 * @param reason    Why, in a sentence
 * @author ExcaliburDB
 */
public record ClassifiedFile(String path, Inclusion inclusion, String reason) {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException if any argument is null
     */
    public ClassifiedFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(inclusion, "inclusion");
        Objects.requireNonNull(reason, "reason");
    }
}
