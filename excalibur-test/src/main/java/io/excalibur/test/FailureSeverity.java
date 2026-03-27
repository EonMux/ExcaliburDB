/*
 * excalibur-test - Test Failure Categorization
 *
 * Package: io.excalibur.test
 *
 * Thread Safety:
 *   - FailureSeverity: Thread-safe, immutable enum
 *   - FailureCategory: Thread-safe, immutable categorization result
 *   - FailureCategorizer: Thread-safe, stateless categorization operations
 */

package io.excalibur.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Severity levels for test failures in the ExcaliburDB integration test suite.
 *
 * <p>Failures are categorized into three severity levels based on their impact
 * on system correctness and reliability:</p>
 *
 * <ul>
 *   <li>{@link #CRITICAL} - Correctness issues that violate core system guarantees</li>
 *   <li>{@link #MAJOR} - Reliability issues that affect stability but not correctness</li>
 *   <li>{@link #MINOR} - Cleanup issues that are non-blocking</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 10.0
 */
public enum FailureSeverity {

    /**
     * CRITICAL severity: Correctness issues.
     *
     * <p>These failures indicate the system is not functioning correctly and
     * violates core correctness guarantees. They MUST be fixed before any release.</p>
     *
     * <h3>Examples:</h3>
     * <ul>
     *   <li>Data loss: Ingested documents not appearing in query results</li>
     *   <li>Epoch isolation violations: Torn reads, partial documents visible</li>
     *   <li>Document atomicity failures: Partial documents in results</li>
     *   <li>Deletion correctness: Deleted keys still found in filters</li>
     *   <li>Incorrect query results: Wrong documents returned</li>
     *   <li>LLM fallback failures: No results when LLM unavailable</li>
     *   <li>Coverage=0 when data exists (empty results on populated index)</li>
     * </ul>
     *
     * <h3>Remediation Priority:</h3>
     * <p>Immediate - blocks all other work until fixed.</p>
     */
    CRITICAL("CRITICAL", "Correctness violation - system does not work as designed", 1),

    /**
     * MAJOR severity: Reliability issues.
     *
     * <p>These failures indicate stability or reliability problems that could
     * cause issues in production but don't violate correctness guarantees.</p>
     *
     * <h3>Examples:</h3>
     * <ul>
     *   <li>Circuit breaker not transitioning correctly</li>
     *   <li>Timeout violations: Queries hanging or exceeding time limits</li>
     *   <li>Concurrent modification exceptions under load</li>
     *   <li>Memory/resource leaks</li>
     *   <li>Performance degradation: Queries too slow</li>
     *   <li>Coverage metadata inaccurate</li>
     *   <li>False positive rate above target (but not extreme)</li>
     * </ul>
     *
     * <h3>Remediation Priority:</h3>
     * <p>High - should be fixed before release but doesn't block other testing.</p>
     */
    MAJOR("MAJOR", "Reliability issue - system may be unstable", 2),

    /**
     * MINOR severity: Cleanup issues.
     *
     * <p>These failures are non-blocking and typically related to test infrastructure,
     * cosmetic issues, or non-essential functionality.</p>
     *
     * <h3>Examples:</h3>
     * <ul>
     *   <li>False positive rate slightly above target (within tolerance)</li>
     *   <li>Test infrastructure issues (temp file cleanup, etc.)</li>
     *   <li>Non-critical assertion failures (logging, metrics)</li>
     *   <li>Cosmetic output issues</li>
     *   <li>Result ordering differences (when order doesn't matter)</li>
     * </ul>
     *
     * <h3>Remediation Priority:</h3>
     * <p>Low - fix when convenient, doesn't block release.</p>
     */
    MINOR("MINOR", "Cleanup issue - non-blocking", 3);

    private final String name;
    private final String description;
    private final int priority;

    FailureSeverity(String name, String description, int priority) {
        this.name = name;
        this.description = description;
        this.priority = priority;
    }

    /**
     * Get the display name of this severity level.
     *
     * @return The severity name
     */
    public String getDisplayName() {
        return name;
    }

    /**
     * Get the description of this severity level.
     *
     * @return The severity description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Get the priority for remediation (1 = highest).
     *
     * @return The priority value
     */
    public int getPriority() {
        return priority;
    }
}
