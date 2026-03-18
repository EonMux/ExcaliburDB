package com.excalibur.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Query result with relevance scores.
 * 
 * Represents ranked search results with relevance scores and optional tags.
 */
public class QueryResult extends Result {

    private static final int DEFAULT_RANK = 1;

    /**
     * Create a new query result.
     *
     * @param documentId the document ID
     * @param rank the rank
     */
    public QueryResult(String documentId, int rank) {
        super(documentId, rank);
    }

    /**
     * Create a query result with additional metadata.
     *
     * @param documentId the document ID
     * @param rank the rank
     * @param score the relevance score
     * @param relevance the normalized relevance
     * @param tags the matching tags
     */
    public QueryResult(String documentId, int rank, String score, String relevance, List<String> tags) {
        super(documentId, rank);
        this.setScore(score);
        this.setRelevance(relevance);
        this.setTags(tags);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString());
        if (score != null) {
            sb.append(", score=").append(score);
        }
        if (relevance != null) {
            sb.append(", relevance=").append(relevance);
        }
        if (tags != null && !tags.isEmpty()) {
            sb.append(", tags=").append(tags);
        }
        return sb.toString();
    }
}
