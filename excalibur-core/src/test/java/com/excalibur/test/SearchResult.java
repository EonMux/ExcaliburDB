package com.excalibur.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Search result with relevance information.
 * 
 * Represents search results focused on relevance ranking.
 */
public class SearchResult extends Result {

    /**
     * Create a new search result.
     *
     * @param documentId the document ID
     * @param rank the rank
     */
    public SearchResult(String documentId, int rank) {
        super(documentId, rank);
    }

    /**
     * Create a search result with relevance.
     *
     * @param documentId the document ID
     * @param rank the rank
     * @param relevance the relevance score
     */
    public SearchResult(String documentId, int rank, String relevance) {
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
