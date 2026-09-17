package excalibur.fsck.repair;

import java.util.List;
import java.util.Objects;

/**
 * Everything that could be repaired, and everything that could not.
 *
 * <p>The second list is the more important one. A repair tool that silently does what it
 * can and says nothing about the rest leaves an operator believing the directory is now
 * sound, and it is the things no tool can decide - a catalog with no readable header, a log
 * damaged behind acknowledged writes, a routing directory pointing at a segment that is
 * gone - that actually decide whether the node's data is recoverable.</p>
 *
 * @param actions        Repairs that are safe and will be made when asked
 * @param needsOperator  Problems a human has to decide about, each phrased as what to weigh
 */
public record RepairPlan(List<RepairAction> actions, List<String> needsOperator) {

    /**
     * Canonical constructor, defensively copying both lists.
     *
     * @throws NullPointerException if either list is null
     */
    public RepairPlan {
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        needsOperator = List.copyOf(Objects.requireNonNull(needsOperator, "needsOperator"));
    }

    /**
     * Reports whether there is nothing to do and nothing to decide.
     *
     * @return true if both lists are empty
     */
    public boolean isEmpty() {
        return actions.isEmpty() && needsOperator.isEmpty();
    }
}
