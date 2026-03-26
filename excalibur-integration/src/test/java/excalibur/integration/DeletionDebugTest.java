package excalibur.integration;

import excalibur.filter.CuckooFilter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Debug test to understand the deletion behavior at high load factor.
 */
class DeletionDebugTest {

    @Test
    void debugHighLoadDeletion() {
        CuckooFilter filter = CuckooFilter.create(1000);

        // Insert to high load factor
        Set<String> insertedKeys = new HashSet<>();
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
        System.out.println("Filter fingerprint bits: " + filter.fingerprintBits());

        // Delete half
        Set<String> toDelete = new HashSet<>();
        int count = 0;
        for (String key : insertedKeys) {
            if (count >= insertedKeys.size() / 2) break;
            toDelete.add(key);
            count++;
        }

        int deleteSuccess = 0;
        int deleteFailed = 0;
        Set<String> deleteFailedKeys = new HashSet<>();
        for (String key : toDelete) {
            boolean deleted = filter.delete(key.getBytes(StandardCharsets.UTF_8));
            if (deleted) {
                deleteSuccess++;
            } else {
                deleteFailed++;
                deleteFailedKeys.add(key);
            }
        }
        System.out.println("Delete success: " + deleteSuccess + ", failed: " + deleteFailed);

        // Check for false positives - keys where delete succeeded but contains still true
        int falsePositives = 0;
        Set<String> falsePositiveKeys = new HashSet<>();
        for (String key : toDelete) {
            boolean contains = filter.contains(key.getBytes(StandardCharsets.UTF_8));
            if (contains) {
                falsePositives++;
                falsePositiveKeys.add(key);
                System.out.println("FALSE POSITIVE: " + key + 
                    " (delete had " + (deleteFailedKeys.contains(key) ? "FAILED" : "SUCCEEDED") + ")");
            }
        }
        System.out.println("Total false positives: " + falsePositives);
        
        // This test documents the actual behavior
        // With 13-bit fingerprints and ~993 items, fingerprint collisions are expected
        // The Cuckoo filter guarantee is: delete removes the fingerprint from the bucket
        // If two items have the same fingerprint and share a bucket, deleting one 
        // may leave the fingerprint visible to the other (inherent limitation)
        
        // Verify the false positive count is within expected bounds
        // For ~500 deletions at 13-bit fingerprint with 993 items:
        // Expected ~60 fingerprint collisions, ~0.5 bucket-sharing collisions
        assertThat(falsePositives)
            .as("False positives should be minimal (fingerprint collision bound)")
            .isLessThan(5);  // Allow small number due to fingerprint collisions
    }
}
