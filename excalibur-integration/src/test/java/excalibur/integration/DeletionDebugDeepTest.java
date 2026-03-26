package excalibur.integration;

import excalibur.filter.CuckooFilter;
import io.excalibur.core.hash.XxHash64;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Deep debug test to trace the exact cause of false positives after deletion.
 */
class DeletionDebugDeepTest {

    @Test
    void traceHighLoadFalsePositive() {
        // Use a fixed seed for reproducibility
        CuckooFilter filter = CuckooFilter.create(1000);

        // Insert to high load factor (same as failing test)
        Set<String> insertedKeys = new LinkedHashSet<>();
        int failedInserts = 0;
        for (int i = 0; i < 2000 && failedInserts < 10; i++) {
            String key = "highload-key-" + i;
            if (filter.insert(key.getBytes(StandardCharsets.UTF_8))) {
                insertedKeys.add(key);
            } else {
                failedInserts++;
            }
        }

        System.out.println("Inserted " + insertedKeys.size() + " keys at high load");
        System.out.println("Filter load factor: " + filter.loadFactor());
        System.out.println("Filter count: " + filter.count());

        // Delete half
        Set<String> toDelete = new LinkedHashSet<>();
        int count = 0;
        for (String key : insertedKeys) {
            if (count >= insertedKeys.size() / 2) break;
            toDelete.add(key);
            count++;
        }

        // Track deletions and false positives
        Map<String, String> falsePositiveDetails = new LinkedHashMap<>();
        
        for (String key : toDelete) {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            boolean deleteResult = filter.delete(keyBytes);
            boolean containsAfter = filter.contains(keyBytes);
            
            if (containsAfter) {
                falsePositiveDetails.put(key, "delete=" + deleteResult);
            }
        }

        if (!falsePositiveDetails.isEmpty()) {
            System.out.println("\n=== FALSE POSITIVES DETECTED ===");
            
            // Get filter parameters for analysis
            long numBuckets = filter.numBuckets();
            int fingerprintBits = filter.fingerprintBits();
            long fingerprintMask = (1L << fingerprintBits) - 1;
            long bucketMask = numBuckets - 1;
            
            System.out.println("Filter params: numBuckets=" + numBuckets + ", fpBits=" + fingerprintBits);
            
            for (String fpKey : falsePositiveDetails.keySet()) {
                System.out.println("\nAnalyzing: " + fpKey);
                
                // Find keys with same fingerprint
                byte[] fpKeyBytes = fpKey.getBytes(StandardCharsets.UTF_8);
                long hash = XxHash64.hash(fpKeyBytes, 0); // Approximate with seed=0
                int fp = (int) ((hash >>> 32) & fingerprintMask);
                if (fp == 0) fp = 1;
                long bucket1 = hash & bucketMask;
                
                System.out.println("  Approx fingerprint: " + fp);
                System.out.println("  Approx bucket1: " + bucket1);
                
                // Search for other keys with same fingerprint in inserted set
                List<String> sameFingerprintKeys = new ArrayList<>();
                for (String otherKey : insertedKeys) {
                    if (otherKey.equals(fpKey)) continue;
                    
                    byte[] otherBytes = otherKey.getBytes(StandardCharsets.UTF_8);
                    long otherHash = XxHash64.hash(otherBytes, 0);
                    int otherFp = (int) ((otherHash >>> 32) & fingerprintMask);
                    if (otherFp == 0) otherFp = 1;
                    
                    if (otherFp == fp) {
                        sameFingerprintKeys.add(otherKey);
                    }
                }
                
                System.out.println("  Keys with same fingerprint (approx): " + sameFingerprintKeys.size());
                if (!sameFingerprintKeys.isEmpty() && sameFingerprintKeys.size() <= 10) {
                    System.out.println("    " + sameFingerprintKeys);
                }
            }
            
            // This indicates a potential bug - the test should document this
            System.out.println("\n=== CONCLUSION ===");
            System.out.println("False positives after deletion can occur due to:");
            System.out.println("1. Fingerprint collision with another key in same bucket (inherent to Cuckoo filters)");
            System.out.println("2. Bug in delete() not removing fingerprint");
            System.out.println("3. Bug in contains() reading wrong fingerprint");
            
            // If this is due to fingerprint collision, it's expected behavior
            // If it's a bug, it needs to be fixed
        }
    }
}
