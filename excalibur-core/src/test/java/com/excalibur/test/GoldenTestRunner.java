package com.excalibur.test;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple test runner for golden tests.
 * 
 * Runs a collection of GoldenTest cases and produces a verification report.
 * 
 * Usage:
 *   GoldenTestRunner.run(tests, comparator, context)
 * 
 * Or run individual tests:
 *   GoldenTest test = new GoldenTest("my test", context);
 *   test.assertResults(expected, actual, epsilon);
 */
public class GoldenTestRunner {
    
    private static final AtomicLong CORRELATION_ID = new AtomicLong(0);
    
    /**
     * Run multiple GoldenTest cases.
     * 
     * @param tests list of tests to run
     * @param comparator result comparator with tolerance
     * @param context test context for formatting
     * @return verification report
     */
    public static VerificationReport run(List<GoldenTest> tests, ResultComparator comparator, TestContext context) {
        VerificationReport report = new VerificationReport(tests, comparator, context);
        
        for (GoldenTest test : tests) {
            test.run();
        }
        
        return report;
    }
    
    /**
     * Run a single GoldenTest and return the result.
     */
    public static GoldenTest runTest(GoldenTest test, ResultComparator comparator, TestContext context) {
        test.run();
        return test;
    }
    
    /**
     * Run a GoldenTest with automatic correlation ID generation.
     */
    public static GoldenTest runTestWithCorrelation(GoldenTest test, ResultComparator comparator, TestContext context) {
        String correlationId = String.format("%016X", CORRELATION_ID.getAndIncrement());
        test.run();
        return test;
    }
    
    /**
     * Main method for running golden tests.
     */
    public static void main(String[] args) {
        // Parse arguments
        ResultComparator comparator = new ResultComparator();
        TestContext context = new TestContext("main");
        
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--epsilon")) {
                comparator = new ResultComparator(Double.parseDouble(args[++i]));
            } else if (args[i].equals("--output")) {
                if (i + 1 < args.length) {
                    run(args[++i], comparator, context);
                    return;
                }
            } else if (args[i].equals("--help")) {
                printUsage();
                return;
            }
        }
        
        run("golden_tests", comparator, context);
    }
    
    /**
     * Run tests and output report.
     */
    public static void run(String name, ResultComparator comparator, TestContext context) {
        List<GoldenTest> tests = new ArrayList<>();
        
        // Add verification test
        tests.add(new GoldenTest("result_comparator", context));
        
        // Add epsilon tolerance test
        tests.add(new GoldenTest("epsilon_tolerance", context));
        
        // Add document id comparison test
        tests.add(new GoldenTest("document_id_comparison", context));
        
        // Add collection comparison test
        tests.add(new GoldenTest("collection_comparison", context));
        
        VerificationReport report = run(tests, comparator, context);
        report.printReport();
    }
    
    /**
     * Print usage information.
     */
    private static void printUsage() {
        System.out.println("GoldenTest Runner - Query Result Verification");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java GoldenTestRunner [--epsilon <value>] [--output <file>] [--help]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --epsilon <value>  Numerical tolerance (default: 1e-6)");
        System.out.println("  --output <file>    Write JSON report to file");
        System.out.println("  --help             Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java GoldenTestRunner");
        System.out.println("  java GoldenTestRunner --epsilon 1e-5");
        System.out.println("  java GoldenTestRunner --output results.json");
    }
}
