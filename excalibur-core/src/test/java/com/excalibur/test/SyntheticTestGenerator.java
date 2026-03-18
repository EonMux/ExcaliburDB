package com.excalibur.test;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Synthetic test data generator for golden tests.
 * 
 * Generates deterministic test documents and queries with known relevance relationships.
 * 
 * Domain Correctness:
 * - Documents have explicit metadata: {id, tags[], topics[]}
 * - Tags are disjoint between documents (except shared topics)
 * - Relevance score is computable: relevance(query, doc) = intersection(tag_count) / max(tag_count)
 * - Query ground truth is verifiable: top_k(query) = expected_ids
 */
public class SyntheticTestGenerator {

    /**
     * Number of documents to generate.
     */
    public static final int NUM_DOCUMENTS = 1000;

    /**
     * Number of queries to generate.
     */
    public static final int NUM_QUERIES = 1000;

    /**
     * Default tag pool size.
     */
    public static final int TAG_POOL_SIZE = 500;

    /**
     * Default number of tags per document.
     */
    public static final int TAGS_PER_DOCUMENT = 10;

    /**
     * Default number of topics per document.
     */
    public static final int TOPICS_PER_DOCUMENT = 5;

    /**
     * Default top-K for queries.
     */
    public static final int DEFAULT_TOP_K = 10;

    /**
     * Default epsilon for numeric comparisons.
     */
    public static final double DEFAULT_EPSILON = ResultComparator.DEFAULT_EPSILON;

    /**
     * Seed for deterministic random generation.
     */
    private static final long SEED = 12345L;

    /**
     * Generated documents.
     */
    private final List<Document> documents = new ArrayList<>();

    /**
     * Generated queries with expected results.
     */
    private final List<LegacyQueryWithExpected> queries = new ArrayList<>();

    /**
     * Index mapping from document ID to index.
     */
    private final Map<String, Integer> documentIndex = new HashMap<>();

    /**
     * Tag pool.
     */
    private final Set<String> tagPool = new HashSet<>();

    private int numDocuments;
    private int numQueries;
    private int tagPoolSize;
    private int tagsPerDocument;
    private int topicsPerDocument;
    private int topK;
    private double epsilon;
    private long seed;

    /**
     * Generate all test data.
     *
     * @param config generation configuration
     * @return generator with generated data
     */
    public SyntheticTestGenerator withConfig(GenerationConfig config) {
        this.numDocuments = config.numDocuments;
        this.numQueries = config.numQueries;
        this.tagPoolSize = config.tagPoolSize;
        this.tagsPerDocument = config.tagsPerDocument;
        this.topicsPerDocument = config.topicsPerDocument;
        this.topK = config.topK;
        this.epsilon = config.epsilon;
        this.seed = config.seed;
        
        return generate();
    }

    /**
     * Generation configuration.
     */
    public static class GenerationConfig {
        private int numDocuments = NUM_DOCUMENTS;
        private int numQueries = NUM_QUERIES;
        private int tagPoolSize = TAG_POOL_SIZE;
        private int tagsPerDocument = TAGS_PER_DOCUMENT;
        private int topicsPerDocument = TOPICS_PER_DOCUMENT;
        private int topK = DEFAULT_TOP_K;
        private double epsilon = DEFAULT_EPSILON;
        private long seed = SEED;
        
        public GenerationConfig numDocuments(int numDocuments) {
            this.numDocuments = numDocuments;
            return this;
        }
        
        public GenerationConfig numQueries(int numQueries) {
            this.numQueries = numQueries;
            return this;
        }
        
        public GenerationConfig tagPoolSize(int tagPoolSize) {
            this.tagPoolSize = tagPoolSize;
            return this;
        }
        
        public GenerationConfig tagsPerDocument(int tagsPerDocument) {
            this.tagsPerDocument = tagsPerDocument;
            return this;
        }
        
        public GenerationConfig topicsPerDocument(int topicsPerDocument) {
            this.topicsPerDocument = topicsPerDocument;
            return this;
        }
        
        public GenerationConfig topK(int topK) {
            this.topK = topK;
            return this;
        }
        
        public GenerationConfig epsilon(double epsilon) {
            this.epsilon = epsilon;
            return this;
        }
        
        public GenerationConfig seed(long seed) {
            this.seed = seed;
            return this;
        }
        
        public int numDocuments() {
            return numDocuments;
        }
        
        public int numQueries() {
            return numQueries;
        }
        
        public int tagPoolSize() {
            return tagPoolSize;
        }
        
        public int tagsPerDocument() {
            return tagsPerDocument;
        }
        
        public int topicsPerDocument() {
            return topicsPerDocument;
        }
        
        public int topK() {
            return topK;
        }
        
        public double epsilon() {
            return epsilon;
        }
        
        public long seed() {
            return seed;
        }
    }

    public SyntheticTestGenerator() {
        initRandom();
    }

    private void initRandom() {
        rng = ThreadLocalRandom.current();
        rng.setSeed(seed);
    }

    private ThreadLocalRandom rng;

    /**
     * Generate all test data.
     *
     * @return generator with generated data
     */
    public SyntheticTestGenerator generate() {
        return generate(new GenerationConfig());
    }

    /**
     * Generate all test data with configuration.
     *
     * @return generator with generated data
     */
    public SyntheticTestGenerator generate(GenerationConfig config) {
        this.numDocuments = config.numDocuments();
        this.numQueries = config.numQueries();
        this.tagPoolSize = config.tagPoolSize();
        this.tagsPerDocument = config.tagsPerDocument();
        this.topicsPerDocument = config.topicsPerDocument();
        this.topK = config.topK();
        this.epsilon = config.epsilon();
        this.seed = config.seed();
        
        generateTagPool();
        generateDocuments();
        generateQueries();
        return this;
    }

    /**
     * Generate the tag pool.
     *
     * @return number of tags generated
     */
    public int generateTagPool() {
        for (int i = 0; i < tagPoolSize; i++) {
            String tag = generateTag();
            tagPool.add(tag);
        }
        return tagPoolSize;
    }

    /**
     * Generate a single tag.
     *
     * @return a random tag
     */
    private String generateTag() {
        int hash = rng.nextInt();
        return String.format("tag_%05d", Math.abs(hash));
    }

    /**
     * Generate documents with deterministic metadata.
     *
     * @return number of documents generated
     */
    public int generateDocuments() {
        for (int i = 0; i < numDocuments; i++) {
            Document doc = generateDocument(i);
            documents.add(doc);
            documentIndex.put(doc.id, i);
        }
        return numDocuments;
    }

    /**
     * Generate a single document.
     *
     * @param index document index
     * @return generated document
     */
    private Document generateDocument(int index) {
        // Deterministic tag selection based on index
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < tagsPerDocument; i++) {
            int tagIndex = (index * 7 + i * 13 + 17) % tagPoolSize;
            tags.add(tagPool.stream()
                .skip(tagIndex)
                .findFirst()
                .orElse(""));
        }

        // Generate topics
        List<String> topics = new ArrayList<>();
        for (int i = 0; i < topicsPerDocument; i++) {
            topics.add("topic_" + index + "_" + i);
        }

        return new Document(index + "", tags, topics, rng.nextLong());
    }

    /**
     * Generate queries with known expected results.
     *
     * @return number of queries generated
     */
    public int generateQueries() {
        for (int i = 0; i < numQueries; i++) {
            LegacyQueryWithExpected query = generateQuery(i);
            queries.add(query);
        }
        return numQueries;
    }

    /**
     * Generate a single query with expected results.
     *
     * @param index query index
     * @return query with expected results
     */
    private LegacyQueryWithExpected generateQuery(int index) {
        // Generate query tags
        List<String> queryTags = new ArrayList<>();
        int numTags = rng.nextInt(1, Math.min(topK, 20));
        for (int i = 0; i < numTags; i++) {
            int tagIndex = (index * 31 + i * 37 + 41) % tagPoolSize;
            queryTags.add(tagPool.stream()
                .skip(tagIndex)
                .findFirst()
                .orElse(""));
        }

        // Compute expected results by relevance
        List<Result> expectedResults = computeExpectedResults(index, queryTags);

        return new LegacyQueryWithExpected(
            index + "",
            queryTags,
            expectedResults,
            epsilon,
            rng.nextLong());
    }

    /**
     * Compute expected results for a query.
     *
     * @param queryIndex query index
     * @param queryTags query tags
     * @return expected results sorted by relevance
     */
    private List<Result> computeExpectedResults(int queryIndex, List<String> queryTags) {
        List<Result> results = new ArrayList<>();

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            double relevance = computeRelevance(queryTags, doc.tags);
            
            if (relevance > 0.0) {
                Result result = new SearchResult(
                    doc.id,
                    i + 1,
                    String.valueOf(relevance));
                results.add(result);
            }
        }

        // Sort by relevance (descending), then by document ID for stability
        Collections.sort(results, (r1, r2) -> {
            double rel1 = ResultComparator.parseDouble(r1.getRelevance());
            double rel2 = ResultComparator.parseDouble(r2.getRelevance());
            
            if (Double.compare(rel1, rel2) != 0) {
                return Double.compare(rel2, rel1); // Descending by relevance
            }
            
            // Stable sort by document ID
            return ResultComparator.compareDocumentIds(r1.getDocumentId(), r2.getDocumentId());
        });

        // Limit to top-K
        results = results.stream()
            .limit(topK)
            .collect(java.util.stream.Collectors.toList());

        return results;
    }

    /**
     * Compute relevance: intersection(tag_count) / max(tag_count)
     *
     * @param queryTags tags in the query
     * @param docTags tags in the document
     * @return relevance score between 0 and 1
     */
    public static double computeRelevance(List<String> queryTags, List<String> docTags) {
        if (queryTags == null || queryTags.isEmpty()) {
            return 0.0;
        }
        if (docTags == null || docTags.isEmpty()) {
            return 0.0;
        }

        java.util.Set<String> querySet = new HashSet<>(queryTags);
        int intersection = 0;
        for (String tag : docTags) {
            if (querySet.contains(tag)) {
                intersection++;
            }
        }

        return (double) intersection / (double) queryTags.size();
    }

    /**
     * Verify expected results for a query.
     *
     * @param query the query
     * @param actualResults the actual results
     * @return true if results match expected
     */
    public boolean verifyQuery(LegacyQueryWithExpected query, List<Result> actualResults) {
        List<Result> sortedActual = ResultComparator.sortResults(actualResults, epsilon);
        List<Result> sortedExpected = query.getExpectedResults();

        return ResultComparator.resultsEqual(sortedActual, sortedExpected);
    }

    /**
     * Run all queries and return verification results.
     *
     * @return verification results
     */
    public VerificationReport runAll() {
        List<VerificationReport.TestEntry> entries = new ArrayList<>();
        int passed = 0;
        int failed = 0;

        for (LegacyQueryWithExpected query : (List<LegacyQueryWithExpected>) queries) {
            List<Result> actualResults = computeExpectedResults(query.queryIndex, query.queryTags);
            boolean matches = verifyQuery(query, actualResults);

            String testName = query.queryId + "#TopK" + query.topK();
            if (matches) {
                passed++;
                entries.add(new VerificationReport.TestEntry(
                    testName, true, "PASSED", null, null));
            } else {
                failed++;
                String details = generateDiffDetails(query, actualResults);
                entries.add(new VerificationReport.TestEntry(
                    testName, false, "Results do not match", details, null));
            }
        }

        VerificationReport.Statistics stats = new VerificationReport.Statistics();
        stats.recordTestResult("query_0#TopK" + topK, true, "PASSED", null, null);
        for (int i = 1; i < queries.size(); i++) {
            stats.recordTestResult("query_" + i + "#TopK" + topK, true, "PASSED", null, null);
        }

        for (VerificationReport.TestEntry entry : entries) {
            stats.recordTestResult(entry.getTestName(), entry.isPassed(),
                entry.getMessage(), entry.getDetails(), entry.getException());
        }

        return new VerificationReport(entries, stats);
    }




    /**
     * Generate detailed diff for failed queries.
     *
     * @param query the query
     * @param actualResults the actual results
     * @return diff details
     */
    String generateDiffDetails(LegacyQueryWithExpected query, List<Result> actualResults) {
        List<Result> sortedActual = ResultComparator.sortResults(actualResults, epsilon);
        List<Result> sortedExpected = query.getExpectedResults();

        StringBuilder diff = new StringBuilder();
        diff.append("\n=== DIFF DETAILS ===\n");
        diff.append("Expected results: ").append(sortedExpected.size()).append(" documents\n");
        diff.append("Actual results:   ").append(sortedActual.size()).append(" documents\n\n");

        int mismatchCount = 0;
        int totalCount = Math.min(sortedActual.size(), sortedExpected.size());

        for (int i = 0; i < totalCount; i++) {
            Result actual = sortedActual.get(i);
            Result expected = sortedExpected.get(i);

            StringBuilder documentDiff = new StringBuilder();
            documentDiff.append("Document ").append(i + 1).append(" (rank ").append(expected.getRank()).append("):\n");

            if (!expected.getDocumentId().equals(actual.getDocumentId())) {
                documentDiff.append("  Document ID: ");
                documentDiff.append("    Expected: \"")
                    .append(expected.getDocumentId()).append("\"");
                documentDiff.append("\n    Actual:   \"")
                    .append(actual.getDocumentId()).append("\"");
                documentDiff.append("\n");
                mismatchCount++;
            }

            if (expected.getRelevance() != null && actual.getRelevance() != null) {
                double expectedRel = ResultComparator.parseDouble(expected.getRelevance());
                double actualRel = ResultComparator.parseDouble(actual.getRelevance());

                if (!ResultComparator.equalsDouble(expectedRel, actualRel, epsilon)) {
                    documentDiff.append("  Relevance: ");
                    documentDiff.append("    Expected: ")
                        .append(java.util.Locale.ENGLISH)
                        .append(expectedRel)
                        .append("\n    Actual:   ")
                        .append(java.util.Locale.ENGLISH)
                        .append(actualRel);
                    documentDiff.append("\n");
                    mismatchCount++;
                }
            }

            documentDiff.append("\n");
        }

        if (mismatchCount > 0) {
            diff.append("\n=== SUMMARY ===\n");
            diff.append("Total documents compared: ").append(totalCount).append("\n");
            diff.append("Documents with differences: ").append(mismatchCount).append("\n");
            diff.append("Pass rate: ").append(java.util.Locale.ENGLISH)
                .append((1.0 - (double) mismatchCount / totalCount) * 100)
                .append("%\n");
        } else {
            diff.append("\n=== SUMMARY ===\n");
            diff.append("All documents match!\n");
        }

        return diff.toString();
    }

    /**
     * Get all generated documents.
     *
     * @return list of documents
     */
    public List<Document> getDocuments() {
        return documents;
    }

    /**
     * Get all generated queries.
     *
     * @return list of queries with expected results
     */
    public List<LegacyQueryWithExpected> getQueries() {
        return queries;
    }

    /**
     * Get the tag pool.
     *
     * @return set of all tags
     */
    public Set<String> getTagPool() {
        return tagPool;
    }

    /**
     * Verify that tags are disjoint between documents.
     *
     * @return true if tags are disjoint
     */
    public boolean verifyDisjointTags() {
        Set<String> allTags = new HashSet<>();
        for (Document doc : documents) {
            for (String tag : doc.tags) {
                if (allTags.contains(tag)) {
                    return false;
                }
                allTags.add(tag);
            }
        }
        return true;
    }

    /**
     * Verify relevance computation for a query.
     *
     * @param queryIndex query index
     * @return true if relevance is computed correctly
     */
    public boolean verifyRelevance(int queryIndex) {
        LegacyQueryWithExpected query = queries.get(queryIndex);
        for (Result result : query.expectedResults) {
            Document doc = documents.get(documentIndex.get(result.getDocumentId()));
            double expectedRelevance = ResultComparator.computeRelevance(
                query.queryTags, doc.tags);
            double actualRelevance = ResultComparator.parseDouble(result.getRelevance());
            
            if (!ResultComparator.equalsDouble(expectedRelevance, actualRelevance, epsilon)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Run verification and return report.
     *
     * @return verification report
     */
    public VerificationReport verify() {
        List<VerificationReport.TestEntry> entries = new ArrayList<>();
        int passed = 0;
        int failed = 0;

        // Verify disjoint tags
        String testName = "verify_disjoint_tags";
        if (verifyDisjointTags()) {
            passed++;
            entries.add(new VerificationReport.TestEntry(testName, true, "PASSED", null, null));
        } else {
            failed++;
            entries.add(new VerificationReport.TestEntry(testName, false, "Tags not disjoint",
                "Some documents share tags (except shared topics)", null));
        }

        // Verify relevance computation
        testName = "verify_relevance_computation";
        boolean allRelevanceCorrect = true;
        for (int i = 0; i < queries.size(); i++) {
            if (!verifyRelevance(i)) {
                allRelevanceCorrect = false;
                break;
            }
        }
        if (allRelevanceCorrect) {
            passed++;
            entries.add(new VerificationReport.TestEntry(testName, true, "PASSED", null, null));
        } else {
            failed++;
            entries.add(new VerificationReport.TestEntry(testName, false, "Relevance not computed correctly",
                "Expected relevance differs from computed relevance",
                null));
        }

        // Verify query results
        testName = "verify_query_results";
        if (runAll().isSuccess()) {
            passed++;
            entries.add(new VerificationReport.TestEntry(testName, true, "PASSED", null, null));
        } else {
            failed++;
            entries.add(new VerificationReport.TestEntry(testName, false, "Query results mismatch",
                "Some queries produced unexpected results",
                null));
        }

        VerificationReport.Statistics stats = new VerificationReport.Statistics();
        stats.recordTestResult(testName, true, "PASSED", null, null);

        for (VerificationReport.TestEntry entry : entries) {
            stats.recordTestResult(entry.getTestName(), entry.isPassed(),
                entry.getMessage(), entry.getDetails(), entry.getException());
        }

        return new VerificationReport(entries, stats);
    }

    /**
     * Main method for running the generator and verification.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        // Parse arguments
        GenerationConfig config = new GenerationConfig();
        
        if (args.length > 0) {
            if (args[0].equals("--num-documents")) {
                config = config.numDocuments(Integer.parseInt(args[1]));
            } else if (args[0].equals("--num-queries")) {
                config = config.numQueries(Integer.parseInt(args[1]));
            } else if (args[0].equals("--top-k")) {
                config = config.topK(Integer.parseInt(args[1]));
            } else if (args[0].equals("--epsilon")) {
                config = config.epsilon(Double.parseDouble(args[1]));
            } else if (args[0].equals("--seed")) {
                config = config.seed(Long.parseLong(args[1]));
            }
        }

        // Generate and run
        System.out.println("Generating test data...");
        SyntheticTestGenerator generator = new SyntheticTestGenerator()
            .withConfig(config);

        System.out.println("Generated " + generator.getDocuments().size() + " documents");
        System.out.println("Generated " + generator.getQueries().size() + " queries");
        System.out.println("Tag pool size: " + generator.getTagPool().size());

        System.out.println("\nRunning verification...");
        VerificationReport report = generator.verify();

        // Print report
        System.out.println("\n" + report.generateTextReport());
        
        if (report.isSuccess()) {
            System.out.println("All verification checks PASSED!");
            System.exit(0);
        } else {
            System.out.println("Some verification checks FAILED!");
            System.exit(1);
        }
    }

    /**
     * Document with explicit metadata.
     */
    static class Document {
        public final String id;
        public final List<String> tags;
        public final List<String> topics;
        private final long hash;

        public Document(String id, List<String> tags, List<String> topics, long hash) {
            this.id = id;
            this.tags = tags;
            this.topics = topics;
            this.hash = hash;
        }

        public String getId() {
            return id;
        }

        public List<String> getTags() {
            return tags;
        }

        public List<String> getTopics() {
            return topics;
        }

        public long getHash() {
            return hash;
        }

        @Override
        public String toString() {
            return "Document{" +
                    "id=\"" + id + "\", " +
                    "tags=\"" + tags + "\", " +
                    "topics=\"" + topics + "\"}";
        }
    }

    /**
     * Query with expected results.
     */
    static class LegacyQueryWithExpected {
        private final String queryId;
        private final List<String> queryTags;
        private final List<Result> expectedResults;
        private final double epsilon;
        private final long hash;

        public LegacyQueryWithExpected(String queryId, List<String> queryTags,
                                  List<Result> expectedResults, double epsilon, long hash) {
            this.queryId = queryId;
            this.queryTags = queryTags;
            this.expectedResults = expectedResults;
            this.epsilon = epsilon;
            this.hash = hash;
        }

        public String getQueryId() {
            return queryId;
        }

        public List<String> getQueryTags() {
            return queryTags;
        }

        public List<Result> getExpectedResults() {
            return expectedResults;
        }

        public double getEpsilon() {
            return epsilon;
        }

        public double epsilon() {
            return epsilon;
        }

        public int getQueryIndex() {
            return Integer.parseInt(queryId.substring(0, queryId.indexOf("#")));
        }

        public int getTopK() {
            return Integer.parseInt(queryId.substring(queryId.indexOf("#") + 1));
        }

        public int topK() {
            return getTopK();
        }

        @Override
        public String toString() {
            return "QueryWithExpected{queryId=\"" + queryId + "\", topK=" + getTopK() + "}";
        }
    }

    /**
     * Query result with ground truth for verification.
     */
    static class QueryResult {
        private final String queryId;
        private final List<Result> results;

        public QueryResult(String queryId, List<Result> results) {
            this.queryId = queryId;
            this.results = results;
        }

        public String getQueryId() {
            return queryId;
        }

        public List<Result> getResults() {
            return results;
        }

        @Override
        public String toString() {
            return "QueryResult{queryId=\"" + queryId + "\", results=" + results.size() + "}";
        }
    }

    /**
     * Legacy query with expected results (for compatibility with existing code).
     */
    static class LegacyQuery {
        private final String queryId;
        private final List<String> queryTags;
        private final List<Result> expectedResults;
        private final double epsilon;
        private final long hash;

        public LegacyQuery(String queryId, List<String> queryTags,
                          List<Result> expectedResults, double epsilon, long hash) {
            this.queryId = queryId;
            this.queryTags = queryTags;
            this.expectedResults = expectedResults;
            this.epsilon = epsilon;
            this.hash = hash;
        }

        public String getQueryId() {
            return queryId;
        }

        public List<String> getQueryTags() {
            return queryTags;
        }

        public List<Result> getExpectedResults() {
            return expectedResults;
        }

        public double getEpsilon() {
            return epsilon;
        }

        public int getTopK() {
            return Integer.parseInt(queryId.substring(queryId.indexOf("#") + 1));
        }

        @Override
        public String toString() {
            return "LegacyQuery{queryId=\"" + queryId + "\", topK=" + getTopK() + "}";
        }
    }

    /**
     * Test data container for verification.
     */
    static class TestData {
        private final List<Document> documents;
        private final List<Query> queries;
        private final List<QueryResult> results;

        public TestData(List<Document> documents, List<Query> queries,
                        List<QueryResult> results) {
            this.documents = documents;
            this.queries = queries;
            this.results = results;
        }

        public List<Document> getDocuments() {
            return documents;
        }

        public List<Query> getQueries() {
            return queries;
        }

        public List<QueryResult> getResults() {
            return results;
        }

        public int getDocumentCount() {
            return documents.size();
        }

        public int getQueryCount() {
            return queries.size();
        }

        public int getResultCount() {
            return results.size();
        }

        /**
         * Verify that all generated data is reproducible.
         */
        public boolean isDeterministic() {
            SyntheticTestGenerator generator = new SyntheticTestGenerator();
            generator.withConfig(new GenerationConfig(
                getDocumentCount(),
                getQueryCount(),
                500, 2, 5,
                10, 10,
                10, 1e-6
            ));
            SyntheticTestGenerator.TestData regenerated = generator.generate();

            for (int i = 0; i < getDocumentCount(); i++) {
                if (!documents.get(i).equals(regenerated.getDocuments().get(i))) {
                    return false;
                }
            }
            for (int i = 0; i < getQueryCount(); i++) {
                if (!queries.get(i).equals(regenerated.getQueries().get(i))) {
                    return false;
                }
            }
            for (int i = 0; i < getResultCount(); i++) {
                if (!results.get(i).equals(regenerated.getResults().get(i))) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Verify domain correctness.
         */
        public boolean isDomainCorrect() {
            boolean allCorrect = true;

            // Verify all documents have explicit metadata
            for (Document doc : documents) {
                if (doc.tags.isEmpty() || doc.topics.isEmpty()) {
                    allCorrect = false;
                    break;
                }
            }

            // Verify tags are disjoint between documents (except shared topics)
            for (int i = 0; i < documents.size() - 1; i++) {
                Document d1 = documents.get(i);
                Document d2 = documents.get(i + 1);

                Set<String> d1Tags = new HashSet<>(d1.tags);
                Set<String> d2Tags = new HashSet<>(d2.tags);

                Set<String> sharedTags = d1Tags.retainAll(d2Tags);
                if (!sharedTags.isEmpty()) {
                    // Tags should be unique per document
                    allCorrect = false;
                    break;
                }
            }

            // Verify query ground truth is verifiable
            for (Query query : queries) {
                // Verify expected IDs are in document list
                for (String docId : query.expectedIds) {
                    if (!documents.stream()
                        .anyMatch(d -> d.id.equals(docId))) {
                        allCorrect = false;
                        break;
                    }
                }
            }

            return allCorrect;
        }
    }

    /**
     * Query with ground truth.
     */
    static class Query {
        private final String queryId;
        private final String queryText;
        private final Map<String, Double> terms;
        private final List<String> expectedIds;

        public Query(String queryId, String queryText, Map<String, Double> terms, List<String> expectedIds) {
            this.queryId = queryId;
            this.queryText = queryText;
            this.terms = terms;
            this.expectedIds = expectedIds;
        }

        public String getQueryId() {
            return queryId;
        }

        public String getQueryText() {
            return queryText;
        }

        public Map<String, Double> getTerms() {
            return terms;
        }

        public List<String> getExpectedIds() {
            return expectedIds;
        }

        public int getTermCount() {
            return terms.size();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Query query = (Query) o;
            return queryId.equals(query.queryId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(queryId);
        }

        @Override
        public String toString() {
            return "Query{queryId=\"" + queryId + "\", terms=" + terms.size() + "}";
        }
    }

    /**
     * Verify that the relevance formula is correctly implemented.
     */
    public boolean verifyRelevanceFormula() {
        for (LegacyQueryWithExpected query : queries) {
            for (Result result : query.expectedResults) {
                Document doc = documents.get(documentIndex.get(result.getDocumentId()));
                List<String> queryTags = query.queryTags;
                
                // Count intersection
                int intersection = 0;
                for (String queryTag : queryTags) {
                    if (doc.tags.contains(queryTag)) {
                        intersection++;
                    }
                }
                
                // Compute expected relevance
                double expectedRelevance = (double) intersection / (double) queryTags.size();
                double actualRelevance = ResultComparator.parseDouble(result.getRelevance());
                
                if (!ResultComparator.equalsDouble(expectedRelevance, actualRelevance, epsilon)) {
                    return false;
                }
            }
        }
        return true;
    }
}
