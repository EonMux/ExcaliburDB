package excalibur.integration;

import excalibur.filter.CuckooFilter;
import io.excalibur.core.hash.XxHash64;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Debug test to analyze fingerprint collisions at high load factor.
 */
class CollisionDebugTest {

    @Test
    void analyzeFingerprintCollisions() {
        int capacity = 1000;
        
        // Calculate filter parameters (matching CuckooFilter.create)
        int fingerprintsPerBucket = 4;
        int fingerprintBits = 13;
        int fingerprintMask = (1 << fingerprintBits) - 1;
        
        long minBuckets = (capacity + fingerprintsPerBucket - 1) / fingerprintsPerBucket;
        long numBuckets = Long.highestOneBit(Math.max(minBuckets * 2, 4));
        long bucketMask = numBuckets - 1;
        
        System.out.println("Filter parameters:");
        System.out.println("  numBuckets: " + numBuckets);
        System.out.println("  fingerprintBits: " + fingerprintBits);
        System.out.println("  fingerprintMask: " + fingerprintMask);
        
        // Generate keys
        Set<String> insertedKeys = new LinkedHashSet<>();
        for (int i = 0; i < 2000; i++) {
            String key = "highload-key-" + i;
            insertedKeys.add(key);
        }
        
        // Analyze fingerprint collisions (using seed=0 as approximation)
        Map<Integer, List<String>> fingerprintToKeys = new HashMap<>();
        Map<String, Integer> keyToFingerprint = new HashMap<>();
        Map<String, Long> keyToBucket1 = new HashMap<>();
        Map<String, Long> keyToBucket2 = new HashMap<>();
        
        long seed = 0;
        
        for (String key : insertedKeys) {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            long hash = XxHash64.hash(keyBytes, seed);
            int fingerprint = (int) ((hash >>> 32) & fingerprintMask);
            if (fingerprint == 0) fingerprint = 1;
            long bucket1 = hash & bucketMask;
            
            // Compute bucket2 using same formula as CuckooFilter
            long fpHash = XxHash64.hash((long) fingerprint, seed);
            long bucket2 = (bucket1 ^ (fpHash & bucketMask)) & bucketMask;
            
            fingerprintToKeys.computeIfAbsent(fingerprint, k -> new ArrayList<>()).add(key);
            keyToFingerprint.put(key, fingerprint);
            keyToBucket1.put(key, bucket1);
            keyToBucket2.put(key, bucket2);
        }
        
        // Count collision pairs
        int collisionPairs = 0;
        for (Map.Entry<Integer, List<String>> entry : fingerprintToKeys.entrySet()) {
            List<String> keys = entry.getValue();
            if (keys.size() > 1) {
                collisionPairs += keys.size() * (keys.size() - 1) / 2;
            }
        }
        System.out.println("\nFingerprint collision pairs: " + collisionPairs);
        
        // Find keys that share BOTH fingerprint AND at least one bucket
        int bucketSharingPairs = 0;
        List<String[]> problematicPairs = new ArrayList<>();
        
        for (Map.Entry<Integer, List<String>> entry : fingerprintToKeys.entrySet()) {
            List<String> keys = entry.getValue();
            if (keys.size() > 1) {
                for (int i = 0; i < keys.size(); i++) {
                    for (int j = i + 1; j < keys.size(); j++) {
                        String key1 = keys.get(i);
                        String key2 = keys.get(j);
                        
                        long b1_1 = keyToBucket1.get(key1);
                        long b2_1 = keyToBucket2.get(key1);
                        long b1_2 = keyToBucket1.get(key2);
                        long b2_2 = keyToBucket2.get(key2);
                        
                        // Check if they share at least one bucket
                        boolean shareBucket = (b1_1 == b1_2) || (b1_1 == b2_2) || 
                                              (b2_1 == b1_2) || (b2_1 == b2_2);
                        
                        if (shareBucket) {
                            bucketSharingPairs++;
                            if (problematicPairs.size() < 5) {
                                problematicPairs.add(new String[]{key1, key2});
                            }
                        }
                    }
                }
            }
        }
        
        System.out.println("Pairs sharing fingerprint AND bucket: " + bucketSharingPairs);
        System.out.println("\nSample problematic pairs:");
        for (String[] pair : problematicPairs) {
            System.out.println("  " + pair[0] + " <-> " + pair[1]);
            System.out.println("    FP: " + keyToFingerprint.get(pair[0]));
            System.out.println("    " + pair[0] + " buckets: " + keyToBucket1.get(pair[0]) + ", " + keyToBucket2.get(pair[0]));
            System.out.println("    " + pair[1] + " buckets: " + keyToBucket1.get(pair[1]) + ", " + keyToBucket2.get(pair[1]));
        }
        
        // Check highload-key-128 specifically
        String key128 = "highload-key-128";
        System.out.println("\n" + key128 + " analysis:");
        System.out.println("  Fingerprint: " + keyToFingerprint.get(key128));
        System.out.println("  Buckets: " + keyToBucket1.get(key128) + ", " + keyToBucket2.get(key128));
        
        int fp128 = keyToFingerprint.get(key128);
        List<String> sameFp = fingerprintToKeys.get(fp128);
        System.out.println("  Keys with same fingerprint: " + sameFp.size());
        
        for (String other : sameFp) {
            if (!other.equals(key128)) {
                long b1_other = keyToBucket1.get(other);
                long b2_other = keyToBucket2.get(other);
                long b1_128 = keyToBucket1.get(key128);
                long b2_128 = keyToBucket2.get(key128);
                
                if (b1_other == b1_128 || b1_other == b2_128 || b2_other == b1_128 || b2_other == b2_128) {
                    System.out.println("  COLLISION WITH: " + other);
                    System.out.println("    Buckets: " + b1_other + ", " + b2_other);
                }
            }
        }
    }
}
