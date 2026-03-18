/*
 * ExcaliburDB - Query Result Verification Service
 * 
 * Sample tests demonstrating the use of the Query Result Verification Service.
 * 
 * Thread Safety: All tests are thread-safe for concurrent execution.
 * 
 * @author ExcaliburDB Verification Service
 * @since 1.0.0
 */
package excalibur.test;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Sample tests for the Query Result Verification Service.
 * 
 * These tests demonstrate:
 * - GoldenTest comparison methods
 * - ResultComparator for deterministic comparison
 * - ExpectedResult fluent builder
 * - VerificationReport generation
 * - VerificationRunner execution
 */
public class QueryVerificationServiceTest {
    
    private GoldenTest goldenTest;
    private ResultComparator resultComparator;
    private ExpectedResult expectedResult;
    
    @BeforeEach
    public void setUp() {
        goldenTest = new GoldenTest();
        resultComparator = new ResultComparator();
        expectedResult = ExpectedResult.builder().build();
    }
    
    /**
     * Test basic string comparison.
     */
    @Test
    public void testStringComparison() {
        // Test equality
        goldenTest.assertStringResults("Hello World", "Hello World");
        
        // Test case sensitivity
        goldenTest.assertStringCaseInsensitive("HELLO WORLD", "hello world");
        
        // Test whitespace insensitivity
        goldenTest.assertWhitespaceCaseInsensitive("  HELLO WORLD  ", "hello world");
        
        // Test substring
        goldenTest.assertContainsString("Hello World", "World");
        goldenTest.assertStartsWith("Hello World", "Hello");
        goldenTest.assertEndsWith("Hello World", "World");
    }
    
    /**
     * Test numeric comparison with tolerance.
     */
    @Test
    public void testNumericComparison() {
        // Test double comparison
        goldenTest.assertDoubleResults(Math.PI, 3.14159265358979);
        
        // Test float comparison
        goldenTest.assertFloatResults(3.14159265358979f, 3.14159265358979f);
        
        // Test integer comparison
        goldenTest.assertIntResults(42, 42);
        
        // Test long comparison
        goldenTest.assertLongResults(9223372036854775807L, 9223372036854775807L);
    }
    
    /**
     * Test list comparison with sorting.
     */
    @Test
    public void testListComparison() {
        // Test list equality
        List<String> actual = List.of("c", "a", "b");
        List<String> expected = List.of("a", "b", "c");
        goldenTest.assertListResults(actual, expected);
        
        // Test list size
        List<String> empty = List.of();
        goldenTest.assertListResults(empty, empty);
        
        // Test list with objects
        List<Integer> intList = List.of(3, 1, 2);
        goldenTest.assertListResults(intList, List.of(1, 2, 3));
    }
    
    /**
     * Test map comparison with sorting.
     */
    @Test
    public void testMapComparison() {
        // Test map equality
        Map<String, Integer> actual = Map.of("b", 2, "a", 1, "c", 3);
        Map<String, Integer> expected = Map.of("a", 1, "b", 2, "c", 3);
        goldenTest.assertMapResults(actual, expected);
        
        // Test map entry
        goldenTest.assertMapEntry(
            () -> Map.of("key", "value"),
            "key", "value");
    }
    
    /**
     * Test set comparison.
     */
    @Test
    public void testSetComparison() {
        // Test set equality
        Set<String> actual = Set.of("b", "a", "c");
        Set<String> expected = Set.of("a", "b", "c");
        goldenTest.assertSetResults(actual, expected);
        
        // Test set containment
        goldenTest.assertContains(
            () -> Set.of("a", "b", "c"), "b");
        goldenTest.assertDoesNotContain(
            () -> Set.of("a", "b", "c"), "d");
    }
    
    /**
     * Test byte array comparison.
     */
    @Test
    public void testByteArrayComparison() {
        byte[] actual = {1, 2, 3, 4, 5};
        byte[] expected = {1, 2, 3, 4, 5};
        goldenTest.assertByteArrayResults(actual, expected);
        goldenTest.assertByteArrayContent(actual, expected);
        goldenTest.assertByteArrayLength(actual, 5);
    }
    
    /**
     * Test boolean comparison.
     */
    @Test
    public void testBooleanComparison() {
        goldenTest.assertBooleanResults(true, true);
        goldenTest.assertBooleanResults(false, false);
    }
    
    /**
     * Test supplier assertions.
     */
    @Test
    public void testSupplierAssertions() {
        // Test supplier returning value
        Supplier<Integer> supplier = () -> 42;
        goldenTest.assertSupplier(42, supplier);
        goldenTest.assertSupplier(
            () -> Math.PI, 
            () -> Math.PI);
        
        // Test supplier returning null
        Supplier<String> nullSupplier = () -> null;
        goldenTest.assertSupplier(null, nullSupplier);
        goldenTest.assertNull(nullSupplier);
        
        // Test supplier returning collection
        Supplier<List<Integer>> collectionSupplier = () -> List.of(1, 2, 3);
        goldenTest.assertCollectionSize(collectionSupplier, 3);
        goldenTest.assertContains(collectionSupplier, 2);
    }
    
    /**
     * Test predicate assertions.
     */
    @Test
    public void testPredicateAssertions() {
        Predicate<String> isEven = s -> Long.parseLong(s) % 2 == 0;
        
        // Test predicate returning true
        goldenTest.assertTruePredicate(isEven, "4");
        goldenTest.assertFalsePredicate(isEven, "5");
        
        // Test stream predicates
        goldenTest.assertStreamAllMatch(
            () -> Stream.of("1", "2", "3", "4", "5"),
            item -> Long.parseLong(item) % 2 == 0);
    }
    
    /**
     * Test stream assertions.
     */
    @Test
    public void testStreamAssertions() {
        // Test stream results
        Supplier<Stream<Integer>> stream = () -> Stream.of(1, 2, 3, 4, 5);
        goldenTest.assertStreamSize(stream, 5);
        goldenTest.assertStreamAtLeast(stream, 3);
        goldenTest.assertStreamAtMost(stream, 7);
        goldenTest.assertStreamNotEmpty(stream);
        goldenTest.assertStreamSingle(() -> Stream.of("single"));
        goldenTest.assertStreamEmpty(() -> Stream.of((Object) null));
        
        // Test stream predicates
        goldenTest.assertStreamAllMatch(
            () -> Stream.of("1", "2", "3", "4", "5"),
            item -> item != null);
    }
    
    /**
     * Test ResultComparator.
     */
    @Test
    public void testResultComparator() {
        // Test list comparison
        ComparisonResult result = resultComparator.compareLists(
            List.of(3, 1, 2),
            List.of(1, 2, 3),
            1e-6);
        assertTrue(result.pass());
        
        // Test map comparison
        ComparisonResult mapResult = resultComparator.compareMaps(
            Map.of("c", 3, "a", 1, "b", 2),
            Map.of("a", 1, "b", 2, "c", 3),
            1e-6);
        assertTrue(mapResult.pass());
        
        // Test string comparison
        ComparisonResult stringResult = resultComparator.compareStrings(
            "Hello World",
            "Hello World");
        assertTrue(stringResult.pass());
        
        // Test double comparison
        ComparisonResult doubleResult = resultComparator.compareDoubles(
            Math.PI,
            3.14159265358979,
            1e-6);
        assertTrue(doubleResult.pass());
        
        // Test failed comparison
        ComparisonResult failedResult = resultComparator.compareLists(
            List.of(1, 2, 3),
            List.of(1, 2, 4),
            1e-6);
        assertFalse(failedResult.pass());
        
        // Test printDiff
        System.out.println(resultComparator.printDiff(
            List.of(3, 1, 2),
            List.of(1, 2, 3),
            1e-6,
            "Test List"));
    }
    
    /**
     * Test ExpectedResult builder.
     */
    @Test
    public void testExpectedResultBuilder() {
        // Test simple value
        ExpectedResult<Integer> intResult = ExpectedResult.intValue(42);
        assertEquals(42, intResult.value());
        
        // Test list
        ExpectedResult<List<String>> listResult = ExpectedResult.list("a", "b", "c");
        assertEquals(3, listResult.value().size());
        
        // Test map
        ExpectedResult<Map<String, Integer>> mapResult = 
            ExpectedResult.map("a", 1, "b", 2, "c", 3);
        assertEquals(3, mapResult.value().size());
        
        // Test set
        ExpectedResult<Set<Integer>> setResult = ExpectedResult.set(1, 2, 3);
        assertEquals(3, setResult.value().size());
        
        // Test with epsilon
        ExpectedResult<Double> doubleResult = ExpectedResult.doubleValue(3.14159265358979, 1e-6);
        assertEquals(1e-6, doubleResult.epsilon(), 1e-12);
        
        // Test comparison
        ComparisonResult compareResult = expectedResult.compare(3.14159265358979);
        assertTrue(compareResult.pass());
    }
    
    /**
     * Test VerificationReport generation.
     */
    @Test
    public void testVerificationReport() {
        VerificationReport report = new VerificationReport();
        report.setCorrelationId("test-correlation-id");
        
        // Add passed tests
        report.addTestPassed("Test 1 - String comparison");
        report.addTestPassed("Test 2 - Numeric comparison");
        report.addTestPassed("Test 3 - List comparison");
        
        // Add failed tests
        report.addTestFailed("Test 4 - Map comparison", "Key mismatch");
        report.addTestFailed("Test 5 - Set comparison", "Size mismatch");
        
        // Add partial tests
        report.addTestPartial("Test 6 - Stream comparison", 2, 1, false, "Partial match");
        report.addTestPartial("Test 7 - Byte array", 3, 1, true, "Error");
        
        // Verify statistics
        assertEquals(7, report.getTotalTests());
        assertEquals(4, report.getPassedTests());
        assertEquals(2, report.getFailedTests());
        assertEquals(1, report.getErrorCount());
        assertEquals(0.57, report.getPassRate(), 0.01);
    }
    
    /**
     * Test SyntheticTestGenerator.
     */
    @Test
    public void testSyntheticTestGenerator() {
        // Generate documents
        SyntheticTestGenerator generator = new SyntheticTestGenerator(42);
        Map<String, SyntheticTestGenerator.Document> documents = generator.generateDocuments();
        
        assertTrue(documents.size() > 0);
        
        // Verify document structure
        SyntheticTestGenerator.Document doc = documents.values().iterator().next();
        assertNotNull(doc.id());
        assertNotNull(doc.content());
        assertNotNull(doc.tags());
        assertNotNull(doc.topics());
        assertNotNull(doc.vector());
        
        // Generate queries
        Map<String, SyntheticTestGenerator.Query> queries = generator.generateQueries();
        
        assertTrue(queries.size() > 0);
        
        // Verify relevance computation
        SyntheticTestGenerator.Document testDoc = documents.values().iterator().next();
        SyntheticTestGenerator.Query testQuery = queries.values().iterator().next();
        
        double relevance = testQuery.computeRelevance(testDoc);
        assertTrue(relevance >= 0.0 && relevance <= 1.0);
        
        // Verify threshold filtering
        assertTrue(testQuery.shouldScore(testDoc));
    }
    
    /**
     * Test VerificationRunner.
     */
    @Test
    public void testVerificationRunner() {
        VerificationRunner runner = new VerificationRunner(30000, 4, 1e-6);
        
        // Run simple tests
        List<TestResult> results = runner.runTests(new GoldenTest() {
            @Override
            public void run() {
                goldenTest.assertStringResults("test", "test");
            }
        });
        
        assertTrue(results.get(0).passed());
        
        // Create report
        VerificationReport report = runner.createReport(results);
        assertEquals(1, report.getTotalTests());
        assertEquals(1, report.getPassedTests());
        
        // Print report
        runner.printReport(report);
    }
    
    /**
     * Test numeric range assertions.
     */
    @Test
    public void testNumericRangeAssertions() {
        goldenTest.assertInRange(5.0, 1.0, 10.0);
        goldenTest.assertGreaterOrEqual(10.0, 5.0);
        goldenTest.assertLessOrEqual(5.0, 10.0);
        goldenTest.assertPositive(5.0);
    }
    
    /**
     * Test exception handling.
     */
    @Test
    public void testExceptionHandling() {
        Supplier<String> throwingSupplier = () -> {
            throw new RuntimeException("Test exception");
        };
        
        goldenTest.assertThrows(RuntimeException.class, throwingSupplier);
        
        // Test assertion
        goldenTest.assertTrue(false, "This should not pass");
    }
    
    /**
     * Test stream size assertions.
     */
    @Test
    public void testStreamSizeAssertions() {
        Supplier<Stream<Integer>> intStream = () -> Stream.range(0, 100);
        
        goldenTest.assertStreamSize(intStream, 100);
        goldenTest.assertStreamAtLeast(intStream, 50);
        goldenTest.assertStreamAtMost(intStream, 200);
        goldenTest.assertStreamNotEmpty(intStream);
    }
    
    /**
     * Test function assertions.
     */
    @Test
    public void testFunctionAssertions() {
        Function<String, Integer> stringToInt = s -> Integer.parseInt(s);
        
        goldenTest.assertFunction(stringToInt, "42", 42);
        goldenTest.assertFunction(stringToInt, "123", 123);
    }
    
    /**
     * Test batch test execution.
     */
    @Test
    public void testBatchExecution() {
        VerificationRunner runner = new VerificationRunner();
        
        List<GoldenTest> tests = Arrays.asList(
            new GoldenTest() {
                @Override public void run() {
                    goldenTest.assertStringResults("a", "a");
                }
            },
            new GoldenTest() {
                @Override public void run() {
                    goldenTest.assertStringResults("b", "b");
                }
            },
            new GoldenTest() {
                @Override public void run() {
                    goldenTest.assertStringResults("c", "c");
                }
            }
        );
        
        VerificationReport report = runner.runAndReport(tests);
        
        assertEquals(3, report.getTotalTests());
        assertEquals(3, report.getPassedTests());
        assertTrue(report.allPassed());
    }
    
    /**
     * Test parallel execution.
     */
    @Test
    public void testParallelExecution() {
        VerificationRunner runner = new VerificationRunner(30000, 4);
        
        List<GoldenTest> tests = Arrays.stream(java.util.Arrays.asList(
            new GoldenTest() {
                @Override public void run() {
                    goldenTest.assertStringResults("1", "1");
                }
            },
            new GoldenTest() {
                @Override public void run() {
                    goldenTest.assertStringResults("2", "2");
                }
            },
            new GoldenTest() {
                @Override public void run() {
                    goldenTest.assertStringResults("3", "3");
                }
            },
            new GoldenTest() {
                @Override public void run() {
                    goldenTest.assertStringResults("4", "4");
                }
            }
        ).toArray(GoldenTest[]::new));
        
        VerificationReport report = runner.runAndReport(tests);
        
        assertEquals(4, report.getTotalTests());
        assertEquals(4, report.getPassedTests());
    }
    
    /**
     * Test JSON report generation.
     */
    @Test
    public void testJsonReport() {
        VerificationReport report = new VerificationReport();
        report.setCorrelationId("test-corr-id");
        
        report.addTestPassed("Test 1");
        report.addTestFailed("Test 2", "Failed");
        report.addTestPartial("Test 3", 1, 1, false, "Partial");
        
        String json = report.getJsonReport();
        
        // Verify JSON structure
        assertTrue(json.contains("\"total\": 3"));
        assertTrue(json.contains("\"passed\": 1"));
        assertTrue(json.contains("\"failed\": 1"));
        assertTrue(json.contains("\"errors\": 1"));
    }
    
    /**
     * Test Markdown report generation.
     */
    @Test
    public void testMarkdownReport() {
        VerificationReport report = new VerificationReport();
        report.addTestPassed("Test 1");
        report.addTestFailed("Test 2", "Failed");
        
        String markdown = report.getMarkdownReport();
        
        // Verify Markdown structure
        assertTrue(markdown.contains("# Verification Report"));
        assertTrue(markdown.contains("| Metric | Value |"));
    }
    
    /**
     * Test console report generation.
     */
    @Test
    public void testConsoleReport() {
        VerificationReport report = new VerificationReport();
        report.addTestPassed("Test 1");
        report.addTestFailed("Test 2", "Failed");
        report.addTestPassed("Test 3");
        
        // Report is generated even without console output
        String consoleReport = report.getConsoleReport();
        
        assertTrue(consoleReport.contains("PASS"));
        assertTrue(consoleReport.contains("FAIL"));
    }
    
    /**
     * Test Statistics class.
     */
    @Test
    public void testStatistics() {
        VerificationRunner.Statistics stats = 
            new VerificationRunner.Statistics(100, 80, 15, 5);
        
        assertEquals(100, stats.total());
        assertEquals(80, stats.passed());
        assertEquals(15, stats.failed());
        assertEquals(5, stats.errors());
        
        assertEquals(0.80, stats.passRate(), 0.001);
        assertEquals(0.15, stats.failRate(), 0.001);
        assertEquals(0.05, stats.errorRate(), 0.001);
    }
}
