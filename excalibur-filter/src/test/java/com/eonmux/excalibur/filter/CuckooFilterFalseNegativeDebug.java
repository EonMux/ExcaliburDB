package com.eonmux.excalibur.filter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Debug test to investigate false negatives in CuckooFilter.
 */
public class CuckooFilterFalseNegativeDebug {

    public static void main(String[] args) {
        System.out.println("=== CuckooFilter False Negative Debug ===\n");

        // Test at scale 1000
        int n = 1000;
        CuckooFilter filter = CuckooFilter.create(n);

        System.out.println("Filter capacity: " + filter.capacity());
        System.out.println("Fingerprint bits: " + filter.fingerprintBits());
        System.out.println("Num buckets: " + filter.numBuckets());

        // Insert items
        List<byte[]> inserted = new ArrayList<>();
        List<String> insertedStr = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String itemStr = "deterministic-item-" + i;
            byte[] item = itemStr.getBytes(StandardCharsets.UTF_8);
            if (filter.insert(item)) {
                inserted.add(item);
                insertedStr.add(itemStr);
            } else {
                System.out.println("Insert failed for item " + i);
            }
        }

        System.out.println("Inserted " + inserted.size() + " items");
        System.out.println("Load factor: " + String.format("%.4f", filter.loadFactor()));

        // Find false negatives
        int falseNegatives = 0;
        for (int i = 0; i < inserted.size(); i++) {
            byte[] item = inserted.get(i);
            if (!filter.contains(item)) {
                falseNegatives++;
                System.out.println("FALSE NEGATIVE [" + falseNegatives + "]: " + insertedStr.get(i));

                // Debug: Try to understand why
                debugItem(filter, item, insertedStr.get(i));

                if (falseNegatives >= 5) {
                    System.out.println("(stopping after 5 false negatives)");
                    break;
                }
            }
        }

        System.out.println("\nTotal false negatives: " + falseNegatives);
        System.exit(falseNegatives > 0 ? 1 : 0);
    }

    private static void debugItem(CuckooFilter filter, byte[] item, String itemStr) {
        System.out.println("  Item: " + itemStr);
        System.out.println("  Bytes length: " + item.length);

        // We can't access private methods, but we can test behavior
        // Try inserting again
        boolean reinsert = filter.insert(item);
        System.out.println("  Re-insert succeeded: " + reinsert);

        boolean containsAfterReinsert = filter.contains(item);
        System.out.println("  Contains after re-insert: " + containsAfterReinsert);

        // Delete and check
        boolean deleted = filter.delete(item);
        System.out.println("  Delete succeeded: " + deleted);

        boolean containsAfterDelete = filter.contains(item);
        System.out.println("  Contains after delete: " + containsAfterDelete);

        System.out.println();
    }
}
