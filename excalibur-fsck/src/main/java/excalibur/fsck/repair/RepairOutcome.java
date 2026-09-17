package excalibur.fsck.repair;

import java.util.Objects;

/**
 * What happened when a repair was applied.
 *
 * @param action  The repair that was attempted
 * @param applied Whether it succeeded
 * @param detail  What was done, or why it could not be
 */
public record RepairOutcome(RepairAction action, boolean applied, String detail) {

    /**
     * Validates the outcome's components.
     *
     * @throws NullPointerException if the action or the detail is null
     */
    public RepairOutcome {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(detail, "detail");
    }
}
