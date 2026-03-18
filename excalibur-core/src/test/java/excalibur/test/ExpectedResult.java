package excalibur.test;

import excalibur.core.schema.QueryResult;
import excalibur.core.schema.QueryResultEntry;

import java.util.*;

/**
 * Builder for typed expected results.
 * 
 * Provides fluent API for constructing expected query results
 * with proper type safety and epsilon handling.
 */
public class ExpectedResult {

    /** Epsilon for double comparison (scores) */
    public static final double DOUBLE_EPSILON = 1e-6;

    /** Epsilon for string comparison */
    public static final double STRING_EPSILON = 1e-12;

    /**
     * Builder for query results with IDs and scores.
     * 
     * Use this when comparing query results where each entry
     * has a unique document ID and a score.
     */
    public static class ExpectedResultBuilder<Long, Double> {
        private final Map<Long, Double> entries;
        private String queryId;
        private String query;

        public ExpectedResultBuilder() {
            this.entries = new LinkedHashMap<>();
        }

        public ExpectedResultBuilder(Long id, Double score) {
            this.entries = new LinkedHashMap<>();
            this.entries.put(id, score);
        }

        public ExpectedResultBuilder withId(Long id) {
            this.entries.put(id, null);
            return this;
        }

        public ExpectedResultBuilder withScore(Double score) {
            if (entries.isEmpty()) {
                throw new IllegalStateException("Cannot set score without first setting ID");
            }
            long id = entries.keySet().iterator().next();
            this.entries.put(id, score);
            return this;
        }

        public ExpectedResultBuilder withIdScore(Long id, Double score) {
            this.entries.put(id, score);
            return this;
        }

        public ExpectedResultBuilder withIdAndMetadata(Long id, Double score, String metadata) {
            this.entries.put(id, score);
            this.queryId = id.toString();
            this.query = metadata;
            return this;
        }

        public ExpectedResultBuilder queryId(String queryId) {
            this.queryId = queryId;
            return this;
        }

        public ExpectedResultBuilder query(String query) {
            this.query = query;
            return this;
        }

        public ExpectedResult build() {
            return new ExpectedResult(queryId, query, entries, DOUBLE_EPSILON, STRING_EPSILON);
        }

        public List<QueryResultEntry> toEntries() {
            List<QueryResultEntry> entries = new ArrayList<>();
            for (Map.Entry<Long, Double> entry : entries.entrySet()) {
                entries.add(new QueryResultEntry(
                        entry.getKey().toString(),
                        entry.getValue(),
                        null
                ));
            }
            return entries;
        }

        @Override
        public String toString() {
            return "ExpectedResultBuilder{" +
                    "entries=" + entries +
                    ", queryId='" + queryId + "', query='" + query + "'" +
                    '}';
        }
    }

    /**
     * Builder for string-based results.
     * 
     * Use this when comparing results where each entry has
     * a unique identifier and a text value.
     */
    public static class ExpectedResultBuilder<String, Double> {
        private final Map<String, Double> entries;
        private String queryId;
        private String query;

        public ExpectedResultBuilder() {
            this.entries = new LinkedHashMap<>();
        }

        public ExpectedResultBuilder(String id, Double score) {
            this.entries = new LinkedHashMap<>();
            this.entries.put(id, score);
        }

        public ExpectedResultBuilder withId(String id) {
            this.entries.put(id, null);
            return this;
        }

        public ExpectedResultBuilder withScore(Double score) {
            if (entries.isEmpty()) {
                throw new IllegalStateException("Cannot set score without first setting ID");
            }
            String id = entries.keySet().iterator().next();
            this.entries.put(id, score);
            return this;
        }

        public ExpectedResultBuilder withIdScore(String id, Double score) {
            this.entries.put(id, score);
            return this;
        }

        public ExpectedResultBuilder withIdAndMetadata(String id, Double score, String metadata) {
            this.entries.put(id, score);
            this.queryId = id;
            this.query = metadata;
            return this;
        }

        public ExpectedResultBuilder queryId(String queryId) {
            this.queryId = queryId;
            return this;
        }

        public ExpectedResultBuilder query(String query) {
            this.query = query;
            return this;
        }

        public ExpectedResult build() {
            return new ExpectedResult(queryId, query, entries, DOUBLE_EPSILON, STRING_EPSILON);
        }

        public List<QueryResultEntry> toEntries() {
            List<QueryResultEntry> entries = new ArrayList<>();
            for (Map.Entry<String, Double> entry : entries.entrySet()) {
                entries.add(new QueryResultEntry(
                        entry.getKey(),
                        entry.getValue(),
                        null
                ));
            }
            return entries;
        }

        @Override
        public String toString() {
            return "ExpectedResultBuilder{" +
                    "entries=" + entries +
                    ", queryId='" + queryId + "', query='" + query + "'" +
                    '}';
        }
    }

    private final String queryId;
    private final String query;
    private final Map<?, Double> entries;
    private final double doubleEpsilon;
    private final double stringEpsilon;

    public ExpectedResult(String queryId, String query, Map<?, Double> entries) {
        this(queryId, query, entries, DOUBLE_EPSILON, STRING_EPSILON);
    }

    public ExpectedResult(String queryId, String query, Map<?, Double> entries, double doubleEpsilon, double stringEpsilon) {
        this.queryId = queryId;
        this.query = query;
        this.entries = entries;
        this.doubleEpsilon = doubleEpsilon;
        this.stringEpsilon = stringEpsilon;
    }

    public String queryId() {
        return queryId;
    }

    public String query() {
        return query;
    }

    public Map<?, Double> entries() {
        return Collections.unmodifiableMap(entries);
    }

    public double size() {
        return entries.size();
    }

    public double doubleEpsilon() {
        return doubleEpsilon;
    }

    public double stringEpsilon() {
        return stringEpsilon;
    }

    @Override
    public String toString() {
        return "ExpectedResult{queryId='" + queryId + "', query='" + query + "', entries=" +
                entries + ", doubleEpsilon=" + doubleEpsilon + ", stringEpsilon=" + stringEpsilon + "}";
    }
}
