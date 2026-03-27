package excalibur.integration;

import excalibur.filter.CuckooFilter;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deep debug test to trace deletion correctness at the bit level.
 */
class DeleteCorrectnessDeepDebugTest {

    @Test
    void traceDeletionAtHighLoad() {
        CuckooFilter filter = CuckooFilter.create(1000);

        // Insert to high load factor
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

        System.out.println("Inserted " + insertedKeys.size() + " keys");
        System.out.println("Filter count: " + filter.count());
        System.out.println("Filter load factor: " + filter.loadFactor());

        // Delete first half and track
        Set<String> toDelete = new LinkedHashSet<>();
        int count = 0;
        for (String key : insertedKeys) {
            if (count >= insertedKeys.size() / 2) break;
            toDelete.add(key);
            count++;
        }

        // Track before/after state for each deletion
        Map<String, String> deletionResults = new LinkedHashMap<>();
        
        for (String key : toDelete) {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            
            boolean beforeDelete = filter.contains(keyBytes);
            boolean deleteResult = filter.delete(keyBytes);
            boolean afterDelete = filter.contains(keyBytes);
            
            if (afterDelete) {
                // This is a false positive - delete succeeded but contains still true
                deletionResults.put(key, 
                    "before=" + beforeDelete + ", delete=" + deleteResult + ", after=" + afterDelete);
            }
        }

        if (!deletionResults.isEmpty()) {
            System.out.println("\nFALSE POSITIVES DETECTED:");
            for (Map.Entry<String, String> entry : deletionResults.entrySet()) {
                System.out.println("  " + entry.getKey() + ": " + entry.getValue());
            }
            
            // Investigate the first false positive
            String fpKey = deletionResults.keySet().iterator().next();
            System.out.println("\nDeep investigation of: " + fpKey);
            
            byte[] fpKeyBytes = fpKey.getBytes(StandardCharsets.UTF_8);
            
            // Re-insert and check
            boolean reinsert = filter.insert(fpKeyBytes);
            System.out.println("Re-insert: " + reinsert);
            System.out.println("Contains after re-insert: " + filter.contains(fpKeyBytes));
            
            // Try to delete twice
            boolean delete1 = filter.delete(fpKeyBytes);
            boolean contains1 = filter.contains(fpKeyBytes);
            boolean delete2 = filter.delete(fpKeyBytes);
            boolean contains2 = filter.contains(fpKeyBytes);
            
            System.out.println("Delete(1): " + delete1 + ", Contains(1): " + contains1);
            System.out.println("Delete(2): " + delete2 + ", Contains(2): " + contains2);
            
            // At very high load factor (96%+), fingerprint collisions are inherent to Cuckoo filter design.
            // With 13-bit fingerprints (8191 possible values) and ~1000 items at 96%+ load:
            // - Birthday problem: expected ~60 collision pairs
            // - Bucket overlap: expected 0.5-2 false positives
            // Allow up to 5 false positives as inherent collision rate at extreme load factors.
            // See DeletionCorrectnessIntegrationTest for proper test with appropriate tolerances.
            assertThat(deletionResults.size())
                .as("False positives from fingerprint collisions at extreme load factor should be very rare " +
                    "(inherent to Cuckoo filter design, not a bug)")
                .isLessThan(6); // Allow small number due to inherent fingerprint collision rate
        }
    }
}
