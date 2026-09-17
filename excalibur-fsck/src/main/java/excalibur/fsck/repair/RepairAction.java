package excalibur.fsck.repair;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One repair, stated in full before anything is done about it.
 *
 * <p>{@link #description} is written to be read aloud to whoever is deciding: it says what
 * will happen to which file, and {@link #rationale} says why that is safe. Both are printed
 * before the action runs, in both modes, so the dry run and the applied run describe the
 * same thing.</p>
 *
 * @param kind        What sort of repair this is
 * @param subject     The file it acts on
 * @param relative    That file's path relative to the data directory
 * @param segmentId   The segment the repair is about, or -1 when it is about no segment
 * @param description Exactly what will be done
 * @param rationale   Why doing it cannot lose anything
 */
public record RepairAction(
    RepairKind kind,
    Path subject,
    String relative,
    long segmentId,
    String description,
    String rationale) {

    /**
     * Validates the action's components.
     *
     * @throws NullPointerException if any component is null
     */
    public RepairAction {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(relative, "relative");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(rationale, "rationale");
    }

    /**
     * Builds an action that is about a file rather than about a catalog row.
     *
     * @param kind        What sort of repair this is
     * @param subject     The file it acts on
     * @param relative    That file's path relative to the data directory
     * @param description Exactly what will be done
     * @param rationale   Why doing it cannot lose anything
     * @return The action, carrying no segment id
     */
    public static RepairAction onFile(RepairKind kind, Path subject, String relative,
                                      String description, String rationale) {
        return new RepairAction(kind, subject, relative, -1, description, rationale);
    }
}
