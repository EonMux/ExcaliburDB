package com.excalibur.test;

import java.util.List;

/**
 * Query with expected results for golden testing.
 * 
 * Represents a query along with its expected top-k results for verification.
 */
class QueryWithExpected {
    private final String queryId;
    private final List<String> queryTags;
    private final List<Result> expectedResults;
    private final double epsilon;
    private final int queryIndex;
    private final int topK;
    private final long hash;

    public QueryWithExpected(String queryId, List<String> queryTags,
                             List<Result> expectedResults, double epsilon,
                             int queryIndex, int topK, long hash) {
        this.queryId = queryId;
        this.queryTags = queryTags;
        this.expectedResults = expectedResults;
        this.epsilon = epsilon;
        this.queryIndex = queryIndex;
        this.topK = topK;
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

    public int getQueryIndex() {
        return queryIndex;
    }

    public int getTopK() {
        return topK;
    }

    @Override
    public String toString() {
        return "QueryWithExpected{queryId=\"" + queryId + "\", topK=" + getTopK() + "}";
    }
}
