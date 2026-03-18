package com.excalibur.test;

import java.util.List;

/**
 * Base class for query result entries.
 * 
 * Represents a single result in a query result set with:
 * - Document ID (unique identifier)
 * - Rank (position in sorted order)
 * - Optional score (relevance score)
 * - Optional relevance (normalized relevance)
 * - Optional tags (matching tags)
 * - Optional similarity (vector similarity)
 */
public abstract class Result {

    protected final String documentId;
    protected final int rank;
    protected String score;
    protected String relevance;
    protected List<String> tags;
    protected float[] similarity;

    protected Result(String documentId, int rank) {
        this.documentId = documentId;
        this.rank = rank;
    }

    public String getDocumentId() {
        return documentId;
    }

    public int getRank() {
        return rank;
    }

    public String getScore() {
        return score;
    }

    public void setScore(String score) {
        this.score = score;
    }

    public String getRelevance() {
        return relevance;
    }

    public void setRelevance(String relevance) {
        this.relevance = relevance;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public float[] getSimilarity() {
        return similarity;
    }

    public void setSimilarity(float[] similarity) {
        this.similarity = similarity;
    }

    public String toString() {
        return "Result{documentId=\"" + documentId + "\", rank=" + rank + "}";
    }

    public String toJson() {
        return "\"" + documentId + "\"";
    }

    /**
     * Compare this result with another result for sorting.
     * 
     * @param other the result to compare with
     * @return negative if this.id < other.id, zero if equal, positive if this.id > other.id
     */
    protected int compareToDocumentId(Result other) {
        return ResultComparator.compareDocumentIds(this.documentId, other.documentId);
    }

    /**
     * Parse a double string value.
     *
     * @param value the string value
     * @return the parsed double value
     */
    protected static double parseDouble(String value) {
        if (value == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Parse a float string value.
     *
     * @param value the string value
     * @return the parsed float value
     */
    protected static float parseFloat(String value) {
        if (value == null) {
            return 0.0f;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return 0.0f;
        }
    }

    /**
     * Parse an int string value.
     *
     * @param value the string value
     * @return the parsed int value
     */
    protected static int parseInt(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
