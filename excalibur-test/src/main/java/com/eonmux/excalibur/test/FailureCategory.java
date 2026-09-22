/*
 * excalibur-test - Test Failure Categorization
 *
 * Package: com.eonmux.excalibur.test
 *
 * Thread Safety:
 *   - FailureCategory: Thread-safe, immutable categorization result
 */

package com.eonmux.excalibur.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a categorized test failure with severity, description, and remediation guidance.
 *
 * <p>Each FailureCategory captures:</p>
 * <ul>
 *   <li>The test name that failed</li>
 *   <li>The severity level (CRITICAL, MAJOR, MINOR)</li>
 *   <li>The failure category type</li>
 *   <li>The specific error message</li>
 *   <li>Remediation suggestions</li>
 *   <li>Related domain correctness criteria</li>
 * </ul>
 *
 * <p>This class is immutable and thread-safe.</p>
 *
 * @author ExcaliburDB
 * @since 10.0
 */
public final class FailureCategory {

    private final String testName;
    private final FailureSeverity severity;
    private final String category;
    private final String errorMessage;
    private final List<String> remediationSteps;
    private final String domainCorrectnessViolation;

    /**
     * Private constructor - use {@link Builder} to create instances.
     */
    private FailureCategory(Builder builder) {
        this.testName = Objects.requireNonNull(builder.testName, "testName is required");
        this.severity = Objects.requireNonNull(builder.severity, "severity is required");
        this.category = Objects.requireNonNull(builder.category, "category is required");
        this.errorMessage = builder.errorMessage != null ? builder.errorMessage : "";
        this.remediationSteps = Collections.unmodifiableList(
            new ArrayList<>(builder.remediationSteps)
        );
        this.domainCorrectnessViolation = builder.domainCorrectnessViolation;
    }

    /**
     * Get the name of the test that failed.
     *
     * @return The test name
     */
    public String getTestName() {
        return testName;
    }

    /**
     * Get the severity level of this failure.
     *
     * @return The severity
     */
    public FailureSeverity getSeverity() {
        return severity;
    }

    /**
     * Get the failure category type.
     *
     * @return The category name
     */
    public String getCategory() {
        return category;
    }

    /**
     * Get the error message from the failure.
     *
     * @return The error message (may be empty but never null)
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Get the list of remediation steps to fix this failure.
     *
     * @return An unmodifiable list of remediation steps
     */
    public List<String> getRemediationSteps() {
        return remediationSteps;
    }

    /**
     * Get the domain correctness criterion that was violated.
     *
     * @return The domain correctness violation description, or null if not applicable
     */
    public String getDomainCorrectnessViolation() {
        return domainCorrectnessViolation;
    }

    /**
     * Check if this failure is critical severity.
     *
     * @return true if severity is CRITICAL
     */
    public boolean isCritical() {
        return severity == FailureSeverity.CRITICAL;
    }

    /**
     * Check if this failure is major severity.
     *
     * @return true if severity is MAJOR
     */
    public boolean isMajor() {
        return severity == FailureSeverity.MAJOR;
    }

    /**
     * Check if this failure is minor severity.
     *
     * @return true if severity is MINOR
     */
    public boolean isMinor() {
        return severity == FailureSeverity.MINOR;
    }

    /**
     * Get a formatted summary of this failure for logging/reporting.
     *
     * @return A formatted summary string
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(severity.getDisplayName()).append("] ");
        sb.append(testName);
        sb.append("\n  Category: ").append(category);
        sb.append("\n  Error: ").append(errorMessage);

        if (domainCorrectnessViolation != null) {
            sb.append("\n  Domain Violation: ").append(domainCorrectnessViolation);
        }

        if (!remediationSteps.isEmpty()) {
            sb.append("\n  Remediation:");
            for (int i = 0; i < remediationSteps.size(); i++) {
                sb.append("\n    ").append(i + 1).append(". ").append(remediationSteps.get(i));
            }
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return "FailureCategory{" +
            "testName='" + testName + '\'' +
            ", severity=" + severity +
            ", category='" + category + '\'' +
            '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FailureCategory that = (FailureCategory) o;
        return Objects.equals(testName, that.testName) &&
            severity == that.severity &&
            Objects.equals(category, that.category) &&
            Objects.equals(errorMessage, that.errorMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(testName, severity, category, errorMessage);
    }

    /**
     * Create a new builder for constructing FailureCategory instances.
     *
     * @return A new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing FailureCategory instances.
     */
    public static final class Builder {
        private String testName;
        private FailureSeverity severity;
        private String category;
        private String errorMessage;
        private List<String> remediationSteps = new ArrayList<>();
        private String domainCorrectnessViolation;

        /**
         * Set the test name.
         *
         * @param testName The name of the failed test
         * @return This builder
         */
        public Builder testName(String testName) {
            this.testName = testName;
            return this;
        }

        /**
         * Set the severity level.
         *
         * @param severity The severity of the failure
         * @return This builder
         */
        public Builder severity(FailureSeverity severity) {
            this.severity = severity;
            return this;
        }

        /**
         * Set the failure category type.
         *
         * @param category The category name
         * @return This builder
         */
        public Builder category(String category) {
            this.category = category;
            return this;
        }

        /**
         * Set the error message.
         *
         * @param errorMessage The error message from the failure
         * @return This builder
         */
        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        /**
         * Add a remediation step.
         *
         * @param step A remediation step
         * @return This builder
         */
        public Builder addRemediationStep(String step) {
            this.remediationSteps.add(step);
            return this;
        }

        /**
         * Set all remediation steps.
         *
         * @param steps The list of remediation steps
         * @return This builder
         */
        public Builder remediationSteps(List<String> steps) {
            this.remediationSteps = new ArrayList<>(steps);
            return this;
        }

        /**
         * Set the domain correctness violation description.
         *
         * @param violation The domain correctness criterion that was violated
         * @return This builder
         */
        public Builder domainCorrectnessViolation(String violation) {
            this.domainCorrectnessViolation = violation;
            return this;
        }

        /**
         * Build the FailureCategory instance.
         *
         * @return A new FailureCategory
         * @throws NullPointerException if required fields are not set
         */
        public FailureCategory build() {
            return new FailureCategory(this);
        }
    }
}
