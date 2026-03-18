package com.excalibur.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Vector result for similarity search.
 * 
 * Represents results from vector similarity search.
 */
public class VectorResult extends Result {

    String queryId;
    int topK;
    double epsilon;
    List<ExpectedResult.ExpectedDocument> documents;

    public VectorResult() {
        super(null, 0);
    }

    /**
     * Create a new vector result.
     *
     * @param documentId the document ID
     * @param rank the rank
     */
    public VectorResult(String documentId, int rank) {
        super(documentId, rank);
    }

    /**
     * Create a vector result with similarity.
     *
     * @param documentId the document ID
     * @param rank the rank
     * @param similarity the similarity score
     */
    public VectorResult(String documentId, int rank, float similarity) {
        super(documentId, rank);
    }

    /**
     * Create a vector result with similarity array.
     *
     * @param documentId the document ID
     * @param rank the rank
     * @param similarity the similarity array
     */
    public VectorResult(String documentId, int rank, float[] similarity) {
        super(documentId, rank);
    }

    String getQueryId() { return queryId; }
    void setQueryId(String queryId) { this.queryId = queryId; }
    int getTopK() { return topK; }
    void setTopK(int topK) { this.topK = topK; }
    double getEpsilon() { return epsilon; }
    void setEpsilon(double epsilon) { this.epsilon = epsilon; }
    List<ExpectedResult.ExpectedDocument> getDocuments() { return documents; }
    void setDocuments(List<ExpectedResult.ExpectedDocument> documents) { this.documents = documents; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString());
        if (similarity != null && similarity.length > 0) {
            sb.append(", similarity=").append(java.util.Arrays.toString(similarity));
        }
        return sb.toString();
    }
}
