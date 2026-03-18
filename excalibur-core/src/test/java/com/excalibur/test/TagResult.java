package com.excalibur.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tag result for tag-based search.
 * 
 * Represents results from tag-based query matching.
 */
public class TagResult extends Result {

    /**
     * Create a new tag result.
     *
     * @param documentId the document ID
     * @param rank the rank
     */
    public TagResult(String documentId, int rank) {
        super(documentId, rank);
    }

    /**
     * Create a tag result with relevance.
     *
     * @param documentId the document ID
     * @param rank the rank
     * @param relevance the relevance score
     */
    public TagResult(String documentId, int rank, String relevance) {
        super(documentId, rank);
        this.setRelevance(relevance);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString());
        if (relevance != null) {
            sb.append(", relevance=").append(relevance);
        }
        return sb.toString();
    }
}
