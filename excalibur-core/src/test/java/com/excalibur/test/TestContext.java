package com.excalibur.test;

import java.util.*;

/**
 * Test context for formatting output during tests.
 * 
 * Provides a consistent way to format values for test reports.
 */
public class TestContext {
    
    private final String name;
    
    public TestContext(String name) {
        this.name = name;
    }
    
    /**
     * Get context name.
     */
    public String getName() {
        return name;
    }
    
    /**
     * Format a value for display.
     */
    public String format(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "\"" + value + "\"";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<?> list = (List<?>) value;
            return Arrays.toString(list.toArray());
        }
        if (value instanceof Map) {
            return value.toString();
        }
        return value.toString();
    }
}
