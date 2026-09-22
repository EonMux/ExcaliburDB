package com.eonmux.excalibur.fsck.report;

import com.eonmux.excalibur.fsck.check.DataDirectoryScan;
import com.eonmux.excalibur.fsck.repair.RepairOutcome;
import com.eonmux.excalibur.fsck.repair.RepairPlan;

import java.util.List;
import java.util.Objects;

/**
 * Everything one run established and everything it did.
 *
 * @param scan     What the survey found
 * @param plan     What could be repaired, and what could not
 * @param outcomes What was actually done, empty when repairs were not asked for
 * @param repaired Whether repairs were applied rather than only stated
 * @param exitCode The code the process will return
 */
public record FsckReport(
    DataDirectoryScan scan,
    RepairPlan plan,
    List<RepairOutcome> outcomes,
    boolean repaired,
    int exitCode) {

    /**
     * Canonical constructor, defensively copying the outcome list.
     *
     * @throws NullPointerException if the scan, the plan or the outcomes are null
     */
    public FsckReport {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(plan, "plan");
        outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
    }
}
