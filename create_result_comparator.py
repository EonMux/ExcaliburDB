import os

file_path = '/home/aeslampanah/Desktop/Projects/ExcaliburDB/excalibur-test/src/main/java/excalibur/test/ResultComparator.java'

content = '''package excalibur.test;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Deterministic result comparator for golden test assertions.
 * Thread-safe for concurrent test execution.
 */
public class ResultComparator<T, U> {
    private final List<T> actual;
    private final List<U> expected;
    private final float epsilon;
    private final String sortKey;
    private final ResultDiagnostics diagnostics = new ResultDiagnostics(actual, expected);
    
    public ResultComparator(T actual, U expected, float epsilon, String sortKey) {
        this.actual = (List<T>) actual;
        this.expected = (List<U>) expected;
        this.epsilon = epsilon;
        this.sortKey = sortKey;
    }
    
    public boolean compare() {
        compareInternal();
        return diagnostics.passed();
    }
    
    private void compareInternal() {
        if (actual == null) {
            if (expected == null) {
                diagnostics.passed();
                return;
            }
            diagnostics.failed("Expected results, but got null");
            return;
        }
        if (expected == null) {
            diagnostics.failed("Got results, but expected null");
            return;
        }
        int actualSize = actual.size();
        int expectedSize = expected.size();
        if (actualSize != expectedSize) {
            diagnostics.failed(String.format("Size mismatch: expected %d results, but got %d results",
                    expectedSize, actualSize));
            return;
        }
        sortResults(actual);
        sortResults(expected);
        int mismatchCount = 0;
        for (int i = 0; i < actualSize; i++) {
            Object actualItem = actual.get(i);
            Object expectedItem = expected.get(i);
            if (!compareItems(actualItem, expectedItem)) {
                mismatchCount++;
            }
        }
        if (mismatchCount > 0) {
            diagnostics.failed(String.format("%d of %d results mismatched (%d total differences)",
                    mismatchCount, actualSize, mismatchCount));
            diagnostics.addDiffDetails(actualSize);
        } else {
            diagnostics.passed();
        }
    }
    
    private void sortResults(List<?> results) {
        if (results == null || results.isEmpty()) return;
        String sortKeyField = this.sortKey;
        if (sortKeyField != null) {
            results.sort((a, b) -> {
                String actualKey = extractSortKey(a, sortKeyField);
                String expectedKey = extractSortKey(b, sortKeyField);
                int result = actualKey.compareTo(expectedKey);
                return result == 0 ? String.valueOf(a).compareTo(String.valueOf(b)) : result;
            });
        } else {
            results.sort(Comparator.comparingInt(i -> Objects.requireNonNull(i)));
            results.sort(Comparator.comparing(Objects::toString));
        }
    }
    
    private String extractSortKey(Object item, String fieldName) {
        if (item == null) return null;
        String key = null;
        if (item instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) item;
            key = extractKeyFromMap(map, fieldName);
        } else if (item instanceof List<?>) {
            List<?> list = (List<?>) item;
            key = list.get(0) != null ? extractSortKey(list.get(0), fieldName) : null;
        } else if (item instanceof String) {
            key = (String) item;
        }
        return key != null ? key : item.toString();
    }
    
    private String extractKeyFromMap(Map<?, ?> map, String fieldName) {
        Object value = map.get(fieldName);
        if (value == null) return "<null>";
        if (value instanceof List<?>) {
            List<?> list = (List<?>) value;
            return list.isEmpty() ? "<empty list>" : extractSortKey(list.get(0), fieldName);
        }
        return value.toString();
    }
    
    private boolean compareItems(Object actual, Object expected) {
        if (actual == null && expected == null) return true;
        if (actual == null || expected == null) return false;
        if (actual == expected) return true;
        if (actual.getClass() == expected.getClass()) return actual.equals(expected);
        return false;
    }
    
    public String getFailureMessage() { return diagnostics.getFailureMessage(); }
    public ResultDiagnostics getDiagnostics() { return diagnostics; }
    public boolean passed() { return diagnostics.passed(); }
    
    private static class ResultDiagnostics {
        private final List<?> actual;
        private final List<?> expected;
        private final float epsilon;
        private final StringBuilder builder = new StringBuilder();
        private boolean passed;
        
        ResultDiagnostics(List<?> actual, List<?> expected) {
            this.actual = actual;
            this.expected = expected;
            this.epsilon = 0.0001f;
        }
        
        void passed() {
            passed = true;
            builder.append("PASS: Results matched successfully\n");
        }
        
        void failed(String message) {
            passed = false;
            builder.append("FAIL: ").append(message).append("\n");
        }
        
        boolean passed() {
            return passed;
        }
        
        String getFailureMessage() {
            return builder.toString();
        }
        
        void addDiffDetails(int totalCount) {
            builder.append("\n--- Detailed Diff ---\n");
            builder.append("Comparing ").append(totalCount).append(" results\n\n");
            int errors = 0;
            for (int i = 0; i < totalCount; i++) {
                String actualStr = formatValue(actual.get(i));
                String expectedStr = formatValue(expected.get(i));
                if (!actualStr.equals(expectedStr)) {
                    errors++;
                    builder.append("Index ").append(i).append(":\n");
                    builder.append("  Expected: ").append(expectedStr).append("\n");
                    builder.append("  Actual:   ").append(actualStr).append("\n");
                }
            }
            builder.append("\n--- Summary ---\n");
            builder.append("Total errors: ").append(errors).append("\n");
        }
        
        String formatValue(Object value) {
            if (value == null) return "null";
            if (value instanceof Number) {
                Number num = (Number) value;
                if (num instanceof Integer) return num.intValue();
                if (num instanceof Long) return num.longValue();
                if (num instanceof Float) return String.format("%.6f", num.floatValue());
                if (num instanceof Double) return String.format("%.6f", num.doubleValue());
            }
            if (value instanceof String) {
                return String.format("\"%s\"", escapeString((String) value));
            }
            String str = value.toString();
            return str.length() > 50 ? str.substring(0, 47) + "..." : str;
        }
        
        String escapeString(String str) {
            return str.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
'''

os.makedirs(os.path.dirname(file_path), exist_ok=True)
with open(file_path, 'w') as f:
    f.write(content)

print(f"Written {len(content)} bytes to {file_path}")
