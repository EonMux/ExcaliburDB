package com.excalibur.test;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Typed expected result builder for constructing expected query results.
 * 
 * This builder provides a fluent API for specifying:
 * - Query terms and their weights
 * - Expected top-k document IDs
 * - Expected metadata per document
 * - Tolerance configuration for comparisons
 */
public class ExpectedResult {
    
    private final String queryId;
    private final List<String> queryTerms;
    private final Map<String, Double> termWeights;
    private final List<String> expectedDocumentIds;
    private final Map<String, Object> expectedMetadata;
    private final double epsilon;
    private final List<ExpectedMetadata> expectedMetadataPerDoc;
    
    public ExpectedResult(String queryId, double epsilon) {
        this.queryId = queryId;
        this.epsilon = epsilon;
        this.queryTerms = new ArrayList<>();
        this.termWeights = new HashMap<>();
        this.expectedDocumentIds = new ArrayList<>();
        this.expectedMetadata = new HashMap<>();
        this.expectedMetadataPerDoc = new ArrayList<>();
    }
    
    /**
     * Add a query term.
     */
    public ExpectedResult withTerm(String term) {
        queryTerms.add(term);
        return this;
    }
    
    /**
     * Add a query term with weight.
     */
    public ExpectedResult withTerm(String term, double weight) {
        queryTerms.add(term);
        termWeights.put(term, weight);
        return this;
    }
    
    /**
     * Set expected document IDs for top-k results.
     */
    public ExpectedResult withDocuments(List<String> documentIds) {
        this.expectedDocumentIds.addAll(documentIds);
        return this;
    }
    
    /**
     * Set expected metadata for a document.
     */
    public ExpectedResult withMetadata(String docId, Map<String, Object> metadata) {
        expectedMetadata.put(docId, metadata);
        expectedMetadataPerDoc.add(new ExpectedMetadata(docId, metadata));
        return this;
    }
    
    /**
     * Get query ID.
     */
    public String getQueryId() {
        return queryId;
    }
    
    /**
     * Get query terms.
     */
    public List<String> getQueryTerms() {
        return Collections.unmodifiableList(queryTerms);
    }
    
    /**
     * Get term weights.
     */
    public Map<String, Double> getTermWeights() {
        return Collections.unmodifiableMap(termWeights);
    }
    
    /**
     * Get expected document IDs.
     */
    public List<String> getExpectedDocumentIds() {
        return Collections.unmodifiableList(expectedDocumentIds);
    }
    
    /**
     * Get expected metadata.
     */
    public Map<String, Object> getExpectedMetadata() {
        return Collections.unmodifiableMap(expectedMetadata);
    }
    
    /**
     * Get epsilon tolerance.
     */
    public double getEpsilon() {
        return epsilon;
    }
    
    /**
     * Verify expected results against actual results.
     */
    public boolean verify(List<Result> actualResults, TestContext context) {
        // Build computed relevance scores
        Map<String, Double> computedScores = computeRelevanceScores(context);
        
        // Sort actual results by document ID for deterministic comparison
        List<Result> sortedActual = actualResults.stream()
            .sorted(Comparator.comparing(Result::getDocumentId))
            .collect(Collectors.toList());
        
        // Build expected result list using SearchResult
        List<Result> expectedResults = expectedDocumentIds.stream()
            .map(docId -> {
                SearchResult r = new SearchResult(docId, 0);
                r.setRelevance(String.valueOf(computedScores.get(docId)));
                if (expectedMetadata.containsKey(docId)) {
                    r.setTags(new ArrayList<>());
                    r.setSimilarity(new float[0]);
                    Map<String, Object> meta = expectedMetadata.get(docId);
                    if (meta != null) {
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
                }
                return r;
            })
            .sorted(Comparator.comparing(Result::getDocumentId))
            .collect(Collectors.toList());
        
        // Compare results
        ResultComparator comparator = new ResultComparator(epsilon);
        return comparator.compareResults(expectedResults, sortedActual, context);
    }
    
    /**
     * Compute relevance scores for all documents.
     * 
     * Relevance formula: relevance(query, doc) = intersection(tag_count) / max(tag_count)
     */
    Map<String, Double> computeRelevanceScores(TestContext context) {
        // Get all document IDs from results
        Set<String> allDocIds = expectedDocumentIds.stream()
            .collect(Collectors.toSet());
        
        // Get all tags from query terms
        Set<String> queryTags = new HashSet<>();
        for (String term : queryTerms) {
            if (term != null) {
                queryTags.addAll(splitTerm(term));
            }
        }
        
        Map<String, Double> scores = new HashMap<>();
        for (String docId : allDocIds) {
            int docTagCount = getDocTagCount(docId, queryTags);
            int queryTagCount = queryTags.size();
            
            if (queryTagCount == 0) {
                scores.put(docId, 0.0);
            } else {
                double intersection = Math.min(docTagCount, queryTagCount);
                double relevance = intersection / (double) queryTagCount;
                scores.put(docId, relevance);
            }
        }
        
        return scores;
    }
    
    /**
     * Split a term into its constituent tags.
     */
    private Set<String> splitTerm(String term) {
        Set<String> tags = new HashSet<>();
        // Handle common term formats
        if (term.contains("-")) {
            String[] parts = term.split("-");
            for (String part : parts) {
                tags.add(part.trim());
            }
        } else {
            tags.add(term);
        }
        return tags;
    }
    
    /**
     * Count tags in a document.
     */
    private int getDocTagCount(String docId, Set<String> queryTags) {
        // Use stored metadata if available, otherwise return 0
        if (expectedMetadataPerDoc.stream()
            .anyMatch(m -> m.getId().equals(docId))) {
            ExpectedMetadata meta = expectedMetadataPerDoc.stream()
                .filter(m -> m.getId().equals(docId))
                .findFirst()
                .orElse(null);
            
            if (meta != null && meta.getTags() != null) {
                return meta.getTags().stream()
                    .filter(queryTags::contains)
                    .count();
            }
        }
        return 0;
    }
    
    /**
     * Generate JSON representation.
     */
    public String toJson() {
        return "{" +
            "\"queryId\":\"" + queryId.replace("\"", "\\\"") + "\"" +
            ",\"terms\":[" +
            queryTerms.stream()
                .map(t -> t.replace("\"", "\\\"") + (termWeights.getOrDefault(t, 1.0) != 1.0 ? ":" + termWeights.getOrDefault(t, 1.0) : ""))
                .collect(Collectors.joining(", ")) +
            "]" +
            ",\"expectedDocs\":[" +
            expectedDocumentIds.stream()
                .map(d -> d.replace("\"", "\\\""))
                .collect(Collectors.joining(", ")) +
            "]" +
            "}";
    }
    
    /**
     * Inner class for per-document expected metadata.
     */
    public static class ExpectedMetadata {
        private final String id;
        private final Map<String, Object> tags;
        private final Map<String, Object> topics;
        
        public ExpectedMetadata(String id, Map<String, Object> metadata) {
            this.id = id;
            this.tags = metadata != null ? extractMap(metadata, "tags") : new HashMap<>();
            this.topics = metadata != null ? extractMap(metadata, "topics") : new HashMap<>();
        }
        
        private Map<String, Object> extractMap(Map<String, Object> metadata, String key) {
            Object o = metadata.get(key);
            if (o instanceof Map) {
                return (Map<String, Object>) o;
            }
            return new HashMap<>();
        }
        
        public String getId() { return id; }
        public Map<String, Object> getTags() { return Collections.unmodifiableMap(tags); }
        public Map<String, Object> getTopics() { return Collections.unmodifiableMap(topics); }
    }
    
    /**
     * Inner class for expected document with metadata.
     */
    public static class ExpectedDocument {
        private final String id;
        private final String relevance;
        private final List<String> tags;
        private final float[] similarity;
        
        public ExpectedDocument(String id, String relevance, List<String> tags, float[] similarity) {
            this.id = id;
            this.relevance = relevance;
            this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
            this.similarity = similarity != null ? similarity.clone() : new float[0];
        }
        
        public String getId() { return id; }
        public String getRelevance() { return relevance; }
        public List<String> getTags() { return tags; }
        public float[] getSimilarity() { return similarity; }
    }
}
