import excalibur.filter.CuckooFilter;

public class DiagnoseDeleteBug {
    public static void main(String[] args) {
        System.out.println("Diagnosing CuckooFilter delete bug...");
        
        CuckooFilter filter = CuckooFilter.create(100_000, 13);
        int numEntries = 95_000;
        byte[][] keys = new byte[numEntries][];
        
        // Insert keys
        for (int i = 0; i < numEntries; i++) {
            byte[] key = new byte[16];
            key[0] = 'H';
            key[1] = 'I';
            key[2] = (byte) (i >> 24);
            key[3] = (byte) (i >> 16);
            key[4] = (byte) (i >> 8);
            key[5] = (byte) i;
            keys[i] = key;
            filter.insert(key);
        }
        
        System.out.println("Filter state: count=" + filter.count() + ", loadFactor=" + filter.loadFactor());
        
        // Delete keys one by one and check for false negatives
        int falseNegativeCount = 0;
        for (int i = 0; i < numEntries / 2; i++) {
            boolean deleted = filter.delete(keys[i]);
            if (!deleted) {
                System.out.println("Delete failed for key " + i);
            }
            
            // Check if deleted key is still "found" (false negative violation)
            if (filter.contains(keys[i])) {
                falseNegativeCount++;
                if (falseNegativeCount <= 5) {
                    System.out.println("FALSE NEGATIVE: Key " + i + " still found after delete!");
                }
            }
        }
        
        System.out.println("\nTotal false negatives: " + falseNegativeCount + " out of " + (numEntries/2) + " deletions");
        
        // Now check if there's a fingerprint collision issue
        // Test: re-insert a deleted key and see if it works
        byte[] testKey = keys[8868];
        System.out.println("\nTesting key 8868:");
        System.out.println("  contains after delete: " + filter.contains(testKey));
        
        boolean reinserted = filter.insert(testKey);
        System.out.println("  re-insert result: " + reinserted);
        System.out.println("  contains after re-insert: " + filter.contains(testKey));
        
        // Check count
        System.out.println("\nFilter count: " + filter.count());
    }
}
