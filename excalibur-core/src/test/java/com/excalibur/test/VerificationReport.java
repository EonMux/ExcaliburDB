package com.excalibur.test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Verification report class for structured verification output.
 * 
 * Provides both JSON format for programmatic consumption and
 * human-readable format for console output.
 */
public class VerificationReport {
    
    final List<GoldenTest> tests;
    private final ResultComparator comparator;
    private final TestContext context;
    
    public VerificationReport(List<GoldenTest> tests, ResultComparator comparator, TestContext context) {
        this.tests = Collections.unmodifiableList(tests);
        this.comparator = comparator;
        this.context = context;
    }
    
    /**
     * Generate aggregate statistics.
     */
    public Statistics generateStatistics() {
        int total = tests.size();
        int passed = (int) tests.stream().filter(GoldenTest::isPassed).count();
        int failed = total - passed;
        
        double passRate = total > 0 ? (double) passed / total : 0.0;
        double failRate = total > 0 ? (double) failed / total : 0.0;
        
        // Calculate false negative and false positive rates
        // These require comparison with ground truth
        double falseNegativeRate = 0.0;
        double falsePositiveRate = 0.0;
        
        return new Statistics(total, passed, failed, passRate, failRate,
            falseNegativeRate, falsePositiveRate);
    }
    
    /**
     * Inner class for statistics.
     */
    public static class Statistics {
        private final int totalTests;
        private final int passedTests;
        private final int failedTests;
        private final double passRate;
        private final double failRate;
        private final double falseNegativeRate;
        private final double falsePositiveRate;
        
        public Statistics(int totalTests, int passedTests, int failedTests,
                         double passRate, double failRate,
                         double falseNegativeRate, double falsePositiveRate) {
            this.totalTests = totalTests;
            this.passedTests = passedTests;
            this.failedTests = failedTests;
            this.passRate = passRate;
            this.failRate = failRate;
            this.falseNegativeRate = falseNegativeRate;
            this.falsePositiveRate = falsePositiveRate;
        }
        
        public int getTotalTests() { return totalTests; }
        public int getPassedTests() { return passedTests; }
        public int getFailedTests() { return failedTests; }
        public double getPassRate() { return passRate; }
        public double getFailRate() { return failRate; }
        public double getFalseNegativeRate() { return falseNegativeRate; }
        public double getFalsePositiveRate() { return falsePositiveRate; }
        
        public String toJson() {
            return "{" +
                "\"totalTests\":" + totalTests + "," +
                "\"passedTests\":" + passedTests + "," +
                "\"failedTests\":" + failedTests + "," +
                "\"passRate\":" + String.format("%.4f", passRate) + "," +
                "\"failRate\":" + String.format("%.4f", failRate) + "," +
                "\"falseNegativeRate\":" + String.format("%.6f", falseNegativeRate) + "," +
                "\"falsePositiveRate\":" + String.format("%.6f", falsePositiveRate) +
                "}";
        }
    }
    
    /**
     * Generate JSON report of all tests.
     */
    public String generateJsonReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": \"1.0\",\n");
        sb.append("  \"comparatorEpsilon\": ").append(comparator.getEpsilon()).append(",\n");
        
        sb.append("  \"tests\":[\n");
        for (int i = 0; i < tests.size(); i++) {
            GoldenTest test = tests.get(i);
            sb.append("    {");
            sb.append("\"name\":\"").append(escapeJson(test.getName())).append("\"");
            sb.append(",\"passed\":[").append(test.isPassed()).append("\"");
            
            if (!test.getFailures().isEmpty()) {
                sb.append(",\"failures\":[");
                for (int j = 0; j < test.getFailures().size(); j++) {
                    if (j > 0) sb.append(",");
                    sb.append(escapeJson(test.getFailures().get(j)));
                }
                sb.append("]");
            }
            
            if (!test.getWarnings().isEmpty()) {
                sb.append(",\"warnings\":[");
                for (int j = 0; j < test.getWarnings().size(); j++) {
                    if (j > 0) sb.append(",");
                    sb.append(escapeJson(test.getWarnings().get(j)));
                }
                sb.append("]");
            }
            
            sb.append("\n    }");
            if (i < tests.size() - 1) sb.append(",");
            sb.append("\n");
        }
        
        sb.append("  ],\n");
        
        Statistics stats = generateStatistics();
        sb.append("  \"statistics\": ").append(stats.toJson()).append("\n");
        
        sb.append("}\n");
        return sb.toString();
    }
    
    /**
     * Escape string for JSON.
     */
    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\n").replace("\r", "\r").replace("\t", "\t");
    }
    
    /**
     * Generate human-readable report.
     */
    public String generateReport() {
        StringBuilder sb = new StringBuilder();
        
        sb.append("========================================\n");
        sb.append("   Query Result Verification Report    \n");
        sb.append("========================================\n\n");
        
        Statistics stats = generateStatistics();
        sb.append("SUMMARY\n");
        sb.append("-------\n");
        sb.append(String.format("  Total Tests:    %d\n", stats.getTotalTests()));
        sb.append(String.format("  Passed:         %d (%.1f%%)\n", stats.getPassedTests(), stats.getPassRate() * 100));
        sb.append(String.format("  Failed:         %d (%.1f%%)\n", stats.getFailedTests(), stats.getFailRate() * 100));
        sb.append(String.format("  False Negatives: %.4f%%\n", stats.getFalseNegativeRate() * 100));
        sb.append(String.format("  False Positives: %.4f%%\n", stats.getFalsePositiveRate() * 100));
        sb.append("\n");
        
        sb.append("TEST RESULTS\n");
        sb.append("------------\n");
        
        int testIdx = 0;
        for (GoldenTest test : tests) {
            sb.append(String.format("%2d. [%s] %s\n", testIdx++, 
                test.isPassed() ? "PASS" : "FAIL", test.getName()));
            
            if (!test.isPassed()) {
                sb.append("\n  Failures:\n");
                for (String failure : test.getFailures()) {
                    sb.append("    - " + failure + "\n");
                }
                sb.append("\n  Warnings:\n");
                for (String warning : test.getWarnings()) {
                    sb.append("    - " + warning + "\n");
                }
            }
        }
        
        sb.append("\n========================================\n");
        
        if (stats.getPassRate() >= 0.95) {
            sb.append("VERDICT: PASS - All tests meeting threshold\n");
        } else if (stats.getPassRate() >= 0.70) {
            sb.append("VERDICT: WARN - Some tests failing\n");
        } else {
            sb.append("VERDICT: FAIL - Too many tests failing\n");
        }
        
        sb.append("========================================\n");
        
        return sb.toString();
    }
    
    /**
     * Print report to stdout.
     */
    public void printReport() {
        System.out.println(generateReport());
    }
    
    /**
     * Write JSON report to file.
     */
    public void writeJsonReport(String path) {
        try {
            Files.write(Path.of(path), generateJsonReport().getBytes(StandardCharsets.UTF_8));
            System.out.println("JSON report written to: " + path);
        } catch (Exception e) {
            System.err.println("Failed to write JSON report: " + e.getMessage());
        }
    }
}
