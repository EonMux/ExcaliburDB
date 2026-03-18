package com.excalibur.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple in-memory storage for golden test verification.
 * 
 * This storage is used for testing the verification framework
 * without requiring the full Phase 2 storage engine implementation.
 * 
 * Thread-safe for read-heavy workloads.
 */
public class InMemoryStorage {
    
    private final Map<String, DocumentEntry> store = new HashMap<>();
    private final Map<String, List<String>> docTagMap = new HashMap<>();
    private final Map<String, List<String>> docTopicMap = new HashMap<>();
    
    /**
     * Insert a document with explicit metadata.
     * 
     * @param docId unique document identifier
     * @param tags  list of tags for this document
     * @param topics list of topics for this document
     * @return the inserted document
     */
    public DocumentEntry insert(String docId, List<String> tags, List<String> topics) {
        DocumentEntry entry = new DocumentEntry(docId, tags, topics, System.currentTimeMillis());
        store.put(docId, entry);
        docTagMap.put(docId, tags);
        docTopicMap.put(docId, topics);
        return entry;
    }
    
    /**
     * Delete a document.
     * 
     * @param docId document identifier to delete
     */
    public void delete(String docId) {
        store.remove(docId);
        docTagMap.remove(docId);
        docTopicMap.remove(docId);
    }
    
    /**
     * Get a document by ID.
     * 
     * @param docId document identifier
     * @return the document or null if not found
     */
    public DocumentEntry get(String docId) {
        return store.get(docId);
    }
    
    /**
     * Search documents by tags.
     * Returns documents that contain at least one matching tag.
     * 
     * @param queryTags tags to search for
     * @return sorted list of matching documents
     */
    public List<String> searchByTags(List<String> queryTags) {
        TreeSet<String> results = new TreeSet<>();
        
        for (String queryTag : queryTags) {
            for (Map.Entry<String, List<String>> entry : docTagMap.entrySet()) {
                if (queryTag != null && entry.getValue().contains(queryTag)) {
                    results.add(entry.getKey());
                    break;
                }
            }
        }
        
        return new ArrayList<>(results);
    }
    
    /**
     * Search documents with relevance scoring.
     * Relevance = intersection(query_tags, doc_tags) / query_tags.size()
     * 
     * @param queryTags tags to search for
     * @param topK maximum results to return
     * @return sorted list of (docId, relevance) pairs
     */
    public List<Result> searchWithRelevance(List<String> queryTags, int topK) {
        List<Result> results = new ArrayList<>();
        
        for (Map.Entry<String, List<String>> entry : docTagMap.entrySet()) {
            String docId = entry.getKey();
            List<String> docTags = entry.getValue();
            double relevance = computeRelevance(queryTags, docTags);
            
            if (relevance > 0.0) {
                results.add(new SearchResult(docId, relevance));
            }
        }
        
        // Sort by relevance (descending), then by document ID for stability
        results.sort((r1, r2) -> {
            double rel1 = ResultComparator.parseDouble(r1.getRelevance());
            double rel2 = ResultComparator.parseDouble(r2.getRelevance());
            
            if (Double.compare(rel1, rel2) != 0) {
                return Double.compare(rel2, rel1); // Descending by relevance
            }
            return ResultComparator.compareDocumentIds(r1.getDocumentId(), r2.getDocumentId());
        });
        
        return results.stream().limit(topK).collect(java.util.stream.Collectors.toList());
    }
    
    /**
     * Compute relevance: intersection(tag_count) / max(tag_count)
     */
    private static double computeRelevance(List<String> queryTags, List<String> docTags) {
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
     * Get all documents.
     */
    public List<DocumentEntry> getAll() {
        return new ArrayList<>(store.values());
    }
    
    /**
     * Get total document count.
     */
    public int size() {
        return store.size();
    }
    
    /**
     * Clear all documents.
     */
    public void clear() {
        store.clear();
        docTagMap.clear();
        docTopicMap.clear();
    }
    
    /**
     * Document entry with metadata.
     */
    static class DocumentEntry {
        public final String id;
        public final List<String> tags;
        public final List<String> topics;
        private final long timestamp;
        
        public DocumentEntry(String id, List<String> tags, List<String> topics, long timestamp) {
            this.id = id;
            this.tags = tags;
            this.topics = topics;
            this.timestamp = timestamp;
        }
        
        public String getId() { return id; }
        public List<String> getTags() { return tags; }
        public List<String> getTopics() { return topics; }
        public long getTimestamp() { return timestamp; }
    }
}
