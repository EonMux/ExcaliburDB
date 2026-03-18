/*
 * excalibur-test - Query Result Verification Service
 * 
 * Package: io.excalibur.test
 * 
 * Thread Safety: 
 *   - Query: Thread-safe, immutable
 */

package io.excalibur.test;

import java.util.*;

/**
 * Immutable representation of a query being tested.
 * 
 * Each query has a unique ID, the query text, and a map of terms with their weights.
 * The query is used to construct expected results for golden tests.
 */
public class Query {
    
    private final String id;
    private final String text;
    private final Map<String, Double> terms;
    
    /**
     * Create a new Query.
     * 
     * @param id Unique query identifier
     * @param text The query text
     * @param terms Map of terms to their weights
     */
    public Query(String id, String text, Map<String, Double> terms) {
        this.id = id;
        this.text = text;
        this.terms = (terms == null) ? Collections.emptyMap() : new HashMap<>(terms);
    }
    
    /**
     * Create a Query with default empty terms map.
     * 
     * @param id Query identifier
     * @param text Query text
     */
    public Query(String id, String text) {
        this(id, text, Collections.emptyMap());
    }
    
    /**
     * Get the query identifier.
     * 
     * @return The query ID
     */
    public String getId() {
        return id;
    }
    
    /**
     * Get the query text.
     * 
     * @return The query text
     */
    public String getText() {
        return text;
    }
    
    /**
     * Get the terms map.
     * 
     * @return The terms map
     */
    public Map<String, Double> getTerms() {
        return Collections.unmodifiableMap(terms);
    }
    
    /**
     * Get the number of terms.
     * 
     * @return The number of terms
     */
    public int getTermCount() {
        return terms.size();
    }
    
    @Override
    public String toString() {
        return "Query{id=\"" + id + "\", text=\"" + text + "\", terms=" + terms.size() + "}";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Query)) return false;
        Query that = (Query) o;
        return Objects.equals(id, that.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
