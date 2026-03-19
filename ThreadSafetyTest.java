import io.excalibur.store.catalog.SegmentCatalog;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadSafetyTest {
    public static void main(String[] args) {
        try {
            System.out.println("=== SegmentCatalog Thread Safety Tests ===\n");
            
            SegmentCatalog catalog = new SegmentCatalog();
            Path tempDir = java.nio.file.Files.createTempDirectory("test");
            
            // Test 1: Concurrent Registration
            System.out.println("Test 1: Concurrent Registration (50 threads x 100 segments)");
            int numThreads = 50, segmentsPerThread = 100;
            final CountDownLatch startLatch = new CountDownLatch(1);
            final CountDownLatch completionLatch = new CountDownLatch(numThreads);
            final AtomicInteger errorCount = new AtomicInteger(0);
            
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            for (int i = 0; i < numThreads; i++) {
                final int tid = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < segmentsPerThread; j++) {
                            catalog.registerSegment("test-seg-" + tid + "-" + j, tempDir.resolve("vol-" + tid));
                        }
                        completionLatch.countDown();
                    } catch (Exception e) { errorCount.incrementAndGet(); }
                });
            }
            startLatch.countDown();
            completionLatch.await();
            executor.shutdown();
            
            int totalSegments = catalog.getStats().getTotalSegments();
            System.out.println("  Registered: " + totalSegments + ", Expected: " + (numThreads * segmentsPerThread));
            System.out.println("  Errors: " + errorCount.get());
            System.out.println("  Result: " + (totalSegments == numThreads * segmentsPerThread && errorCount.get() == 0 ? "PASS" : "FAIL"));
            
            // Test 2: Concurrent State Updates
            System.out.println("\nTest 2: Concurrent State Updates (10,000 updates)");
            catalog.clear();
            catalog.registerSegment("state-test", tempDir.resolve("vol2"));
            int numUpdates = 10000;
            final CountDownLatch startLatch2 = new CountDownLatch(1);
            final CountDownLatch completionLatch2 = new CountDownLatch(1);
            final AtomicInteger errorCount2 = new AtomicInteger(0);
            
            executor = Executors.newVirtualThreadPerTaskExecutor();
            executor.submit(() -> {
                try {
                    startLatch2.await();
                    for (int i = 0; i < numUpdates; i++) {
                        catalog.updateLifecycleState("state-test", 
                            SegmentCatalog.LifecycleState.values()[(i % (SegmentCatalog.LifecycleState.values().length - 1)) + 1]);
                    }
                    completionLatch2.countDown();
                } catch (Exception e) { errorCount2.incrementAndGet(); }
            });
            startLatch2.countDown();
            completionLatch2.await();
            executor.shutdown();
            
            System.out.println("  Updates: " + numUpdates + ", Errors: " + errorCount2.get());
            System.out.println("  Result: " + (errorCount2.get() == 0 ? "PASS" : "FAIL"));
            
            // Test 3: Mixed Read/Write Operations
            System.out.println("\nTest 3: Mixed Read/Write Operations (500 iterations)");
            catalog.clear();
            for (int i = 0; i < 50; i++) catalog.registerSegment("mixed-" + i, tempDir.resolve("vol3"));
            final AtomicInteger mixedErrors = new AtomicInteger(0);
            final CountDownLatch startLatch3 = new CountDownLatch(1);
            final CountDownLatch completionLatch3 = new CountDownLatch(1);
            
            executor = Executors.newVirtualThreadPerTaskExecutor();
            executor.submit(() -> {
                try {
                    startLatch3.await();
                    for (int i = 0; i < 500; i++) {
                        if (i % 3 == 0) catalog.updateLifecycleState("mixed-" + (i % 50), SegmentCatalog.LifecycleState.ACTIVE);
                        else if (i % 3 == 1) catalog.incrementEntryCount("mixed-" + (i % 50), 1);
                        else catalog.getSegment("mixed-" + (i % 50));
                    }
                    completionLatch3.countDown();
                } catch (Exception e) { mixedErrors.incrementAndGet(); }
            });
            startLatch3.countDown();
            completionLatch3.await();
            executor.shutdown();
            
            System.out.println("  Operations: 500, Errors: " + mixedErrors.get());
            System.out.println("  Result: " + (mixedErrors.get() == 0 ? "PASS" : "FAIL"));
            
            // Test 4: High Contention Stress Test
            System.out.println("\nTest 4: High Contention Stress Test (10,000 ops)");
            catalog.clear();
            for (int i = 0; i < 100; i++) catalog.registerSegment("stress-" + i, tempDir.resolve("vol4"));
            final AtomicInteger stressErrors = new AtomicInteger(0);
            final CountDownLatch startLatch4 = new CountDownLatch(1);
            final CountDownLatch completionLatch4 = new CountDownLatch(1);
            
            executor = Executors.newVirtualThreadPerTaskExecutor();
            executor.submit(() -> {
                try {
                    startLatch4.await();
                    for (int i = 0; i < 10000; i++) {
                        int op = i % 5;
                        if (op == 0) catalog.getSegment("stress-" + (i % 100));
                        else if (op == 1) catalog.updateLifecycleState("stress-" + (i % 100), SegmentCatalog.LifecycleState.ACTIVE);
                        else if (op == 2) catalog.incrementEntryCount("stress-" + (i % 100), 1);
                        else if (op == 3) catalog.getStats();
                        else if (op == 4) catalog.getAllSegmentIds();
                    }
                    completionLatch4.countDown();
                } catch (Exception e) { stressErrors.incrementAndGet(); }
            });
            startLatch4.countDown();
            completionLatch4.await();
            executor.shutdown();
            
            System.out.println("  Operations: 10,000, Errors: " + stressErrors.get());
            System.out.println("  Result: " + (stressErrors.get() == 0 ? "PASS" : "FAIL"));
            
            // Summary
            System.out.println("\n========================================");
            System.out.println("Thread Safety Test Summary:");
            System.out.println("  Test 1 (Concurrent Registration): " + (totalSegments == numThreads * segmentsPerThread && errorCount.get() == 0 ? "PASS" : "FAIL"));
            System.out.println("  Test 2 (Concurrent Updates): " + (errorCount2.get() == 0 ? "PASS" : "FAIL"));
            System.out.println("  Test 3 (Mixed Operations): " + (mixedErrors.get() == 0 ? "PASS" : "FAIL"));
            System.out.println("  Test 4 (Stress Test): " + (stressErrors.get() == 0 ? "PASS" : "FAIL"));
            System.out.println("========================================");
            
            catalog.clear();
            System.out.println("\nAll tests completed!");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
