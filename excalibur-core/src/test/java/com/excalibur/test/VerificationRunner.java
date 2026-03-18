package com.excalibur.test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Verification runner for executing golden tests and generating reports.
 * 
 * The runner:
 * 1. Generates synthetic test data
 * 2. Runs verification tests
 * 3. Outputs JSON report to console and file
 * 4. Shows pass/fail summary
 */
public class VerificationRunner {
    
    private static final long SEED = 1;
    private final TestContext context;
    private final ResultComparator comparator;
    private final VerificationReport report;
    
    public VerificationRunner(TestContext context) {
        this.context = context;
        this.comparator = new ResultComparator();
        this.report = new VerificationReport(new ArrayList<>(), comparator, context);
    }
    
    /**
     * Run all verification tests using LegacyQueryWithExpected.
     */
    public void runTests(SyntheticTestGenerator generator) {
        List<GoldenTest> tests = new ArrayList<>();
        
        // Generate expected results for each query
        Map<String, ExpectedResult> expectedResults = generateExpectedResults(generator);
        
        // Run verification tests
        for (SyntheticTestGenerator.LegacyQueryWithExpected query : generator.getQueries()) {
            GoldenTest test = createGoldenTest(query, expectedResults, generator);
            tests.add(test);
        }
        
        // Update report with test results
        report.tests.addAll(tests);
        
        // Print report
        report.printReport();
        
        // Write JSON report
        String jsonPath = "verification-report.json";
        report.writeJsonReport(jsonPath);
    }
    
    /**
     * Generate all expected results from test data.
     */
    private Map<String, ExpectedResult> generateExpectedResults(SyntheticTestGenerator generator) {
        Map<String, ExpectedResult> expectedResults = new HashMap<>();
        
        for (SyntheticTestGenerator.LegacyQueryWithExpected query : generator.getQueries()) {
            ExpectedResult expected = new ExpectedResult(query.getQueryId(), comparator.getEpsilon());
            
            // Add query terms as tags
            for (String tag : query.getQueryTags()) {
                expected.withTerm(tag);
            }
            
            // Add expected documents
            expected.withDocuments(query.getExpectedResults().stream()
                .map(r -> r.getDocumentId())
                .collect(Collectors.toList()));
            
            // Add expected metadata per document
            for (String docId : query.getExpectedResults().stream()
                    .map(r -> r.getDocumentId())
                    .collect(Collectors.toList())) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("id", docId);
                metadata.put("tags", new ArrayList<>());
                metadata.put("topics", new ArrayList<>());
                
                // Populate metadata from document
                for (SyntheticTestGenerator.Document doc : generator.getDocuments()) {
                    if (doc.id.equals(docId)) {
                        metadata.put("tags", new ArrayList<>(doc.tags));
                        metadata.put("topics", new ArrayList<>(doc.topics));
                        break;
                    }
                }
                // Convert metadata to Map<String, Object>
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = expected.getExpectedMetadata().get(docId);
                if (meta != null) {
                    r.setTags(new ArrayList<>(meta.get("tags") instanceof List ? 
                        (List) meta.get("tags") : new ArrayList<>()));
                }
                
                expected.withMetadata(docId, metadata);
            }
            
            expectedResults.put(query.getQueryId(), expected);
        }
        
        return expectedResults;
    }
    
    /**
     * Create a golden test for a query result.
     */
    private GoldenTest createGoldenTest(SyntheticTestGenerator.LegacyQueryWithExpected query,
                                         Map<String, ExpectedResult> expectedResults,
                                         SyntheticTestGenerator generator) {
        GoldenTest test = new GoldenTest("query_result_verification", context);
        
        String queryId = query.getQueryId();
        ExpectedResult expected = expectedResults.get(queryId);
        
        if (expected == null) {
            test.fail("No expected result found", queryId);
            return test;
        }
        
        // Verify results
        boolean verified = expected.verify(query.getExpectedResults(), context);
        
        if (!verified) {
            String diff = comparator.generateDiff(query.getExpectedResults(),
                generateExpectedResultList(expected), context);
            test.fail("Verification failed", diff);
            
            // Generate detailed failure report
            test.warn("Generated diff for debugging");
        } else {
            test.pass();
        }
        
        return test;
    }
    
    /**
     * Generate expected result list for comparison.
     */
    private List<Result> generateExpectedResultList(ExpectedResult expected) {
        Map<String, Double> computedScores = expected.computeRelevanceScores(context);
        
        List<Result> results = expected.getExpectedDocumentIds().stream()
            .map(docId -> {
                SearchResult r = new SearchResult(docId, 0);
                r.setRelevance(String.valueOf(computedScores.get(docId)));
                r.setTags(new ArrayList<>());
                r.setSimilarity(new float[0]);
                if (expected.getExpectedMetadata().containsKey(docId)) {
                    Map<String, Object> meta = expected.getExpectedMetadata().get(docId);
                    Object tagsObj = meta.get("tags");
                    if (tagsObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List tagsList = (List) tagsObj;
                        r.setTags(new ArrayList<>(tagsList.stream()
                            .map(t -> (String) t).collect(Collectors.toList())));
                    }
                    Object similarityObj = meta.get("similarity");
                    if (similarityObj instanceof float[]) {
                        float[] sim = (float[]) similarityObj;
                        r.setSimilarity(sim.clone());
                    }
                }
                return r;
            })
            .sorted(Comparator.comparing(Result::getDocumentId))
            .collect(Collectors.toList());
        
        return results;
    }
    
    /**
     * Generate test statistics.
     */
    public TestStatistics generateTestStatistics(SyntheticTestGenerator generator) {
        int docCount = generator.getDocuments().size();
        int queryCount = generator.getQueries().size();
        
        double avgTags = 0.0;
        double avgTopics = 0.0;
        for (SyntheticTestGenerator.Document doc : generator.getDocuments()) {
            avgTags += doc.tags.size();
            avgTopics += doc.topics.size();
        }
        avgTags /= docCount;
        avgTopics /= docCount;
        
        double avgQueryTags = 0.0;
        for (SyntheticTestGenerator.LegacyQueryWithExpected query : generator.getQueries()) {
            avgQueryTags += query.getQueryTags().size();
        }
        avgQueryTags /= queryCount;
        
        return new TestStatistics(
            docCount, queryCount,
            avgTags, avgTopics, avgQueryTags
        );
    }
    
    /**
     * Inner class for test statistics.
     */
    public static class TestStatistics {
        private final int documentCount;
        private final int queryCount;
        private final double avgTagsPerDoc;
        private final double avgTopicsPerDoc;
        private final double avgQueryTags;
        
        public TestStatistics(int documentCount, int queryCount,
                             double avgTagsPerDoc, double avgTopicsPerDoc,
                             double avgQueryTags) {
            this.documentCount = documentCount;
            this.queryCount = queryCount;
            this.avgTagsPerDoc = avgTagsPerDoc;
            this.avgTopicsPerDoc = avgTopicsPerDoc;
            this.avgQueryTags = avgQueryTags;
        }
        
        public int getDocumentCount() { return documentCount; }
        public int getQueryCount() { return queryCount; }
        public double getAvgTagsPerDoc() { return avgTagsPerDoc; }
        public double getAvgTopicsPerDoc() { return avgTopicsPerDoc; }
        public double getAvgQueryTags() { return avgQueryTags; }
        
        public String toString() {
            return "TestStatistics{" +
                "documents=" + documentCount + ", " +
                "queries=" + queryCount + ", " +
                "avgTags=" + String.format("%.2f", avgTagsPerDoc) + ", " +
                "avgTopics=" + String.format("%.2f", avgTopicsPerDoc) +
                ", avgQueryTags=" + String.format("%.2f", avgQueryTags) +
                '}';
        }
    }
    
    /**
     * Main method for running verification tests.
     */
    public static void main(String[] args) {
        TestContext context = new TestContext("VerificationRunner");
        
        // Generate synthetic test data
        SyntheticTestGenerator generator = new SyntheticTestGenerator();
        generator.withConfig(new SyntheticTestGenerator.GenerationConfig(
            1000, 1000, 500, 2, 5, 10, 1e-6, 12345L));
        
        System.out.println("Generated test data:");
        System.out.println("  Documents: " + generator.getDocuments().size());
        System.out.println("  Queries: " + generator.getQueries().size());
        System.out.println("  Tag pool: " + generator.getTagPool().size());
        System.out.println();
        
        // Verify generated data
        System.out.println("Data verification:");
        System.out.println("  Relevance Formula: " + generator.verifyRelevanceFormula());
        System.out.println("  Disjoint Tags: " + generator.verifyDisjointTags());
        System.out.println();
        
        // Run verification
        VerificationRunner runner = new VerificationRunner(context);
        runner.runTests(generator);
        
        // Print detailed statistics
        TestStatistics testStats = runner.generateTestStatistics(generator);
        System.out.println("\nTest Statistics:");
        System.out.println(testStats.toString());
        System.out.println();
        
        // Print JSON report
        System.out.println("\n--- JSON REPORT ---");
        System.out.println(runner.report.generateJsonReport());
    }
}
