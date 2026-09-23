package com.eonmux.excalibur.query.pipeline;

import com.eonmux.excalibur.filter.lsh.LshConfig;
import com.eonmux.excalibur.filter.lsh.LshIndex;
import com.eonmux.excalibur.vector.distance.CosineSimilarity;
import com.eonmux.excalibur.vector.distance.DistanceFunction;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.HnswSearchResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a candidate set is worth once something honours it.
 *
 * <p>These drive {@link HnswGraph#search(float[], int, Set)} directly with a set the LSH index
 * produced, which is the mechanism rather than its use: they say that the answer stays inside the
 * set, agrees with an unrestricted search when the set holds everything, and is the nearest of the
 * set rather than whichever members the graph happened to link together - which is what lets
 * stage 2 rank the named nodes in beside its graph search by exact distance. That the query
 * pipeline actually hands stage 2 such a set, and never lets it confine the search, is a separate
 * question, and {@link LshCandidateHandoffTest} is where it is asked.</p>
 *
 * <h2>Test Scenarios</h2>
 * <ul>
 *   <li>Verify filtered search returns only nodes in candidate set</li>
 *   <li>Verify filtered search produces same results as unfiltered when candidates include all nodes</li>
 *   <li>Measure candidate reduction ratio (10x-100x top_k)</li>
 *   <li>Verify recall is maintained with LSH pre-filtering</li>
 * </ul>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Vectors are normalized (unit length) for cosine similarity</li>
 *   <li>Distances are in valid range [-1, 1] for cosine</li>
 *   <li>Results are sorted by distance (closest first)</li>
 * </ul>
 *
 * @author ExcaliburDB
 * @since 6.0
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LshPreFilterIntegrationTest {

    private static final int DIMENSIONS = 128;
    // Reduced from 1000 to 500 for faster tests while maintaining statistical validity
    // Must be large enough for LSH to reliably return candidates
    private static final int NUM_VECTORS = 500;
    private static final int MAX_GRAPH_NODES = 600; // Slightly larger than NUM_VECTORS
    private static final int TOP_K = 10;
    private static final double DELTA = 0.001;

    /** Fixed LSH hyperplane seed, so bucket assignment is reproducible. */
    private static final long LSH_SEED = 20240517L;

    private Path tempDir;
    private HnswGraph graph;
    private LshIndex lshIndex;
    private DistanceFunction distanceFunction;
    private List<float[]> vectors;
    private List<Long> nodeIds;
    private Path graphPath;

    @BeforeAll
    void setUp(@TempDir Path tempDir) throws IOException {
        this.tempDir = tempDir;
        // Create distance function (singleton)
        distanceFunction = CosineSimilarity.getInstance();

        // Create HNSW graph
        HnswConfig hnswConfig = new HnswConfig(
            HnswConfig.DEFAULT_MAX_CONNECTIONS,
            HnswConfig.DEFAULT_MAX_CONNECTIONS_LAYER0,
            HnswConfig.DEFAULT_EF_CONSTRUCTION,
            100, // efSearch
            HnswConfig.DEFAULT_ML,
            DIMENSIONS
        );

        graphPath = tempDir.resolve("test_graph.hnsw");
        graph = new HnswGraph(graphPath, hnswConfig, distanceFunction, MAX_GRAPH_NODES);

        // Create LSH index with good recall characteristics.
        // The seed is pinned: LshConfig.Builder otherwise defaults to System.nanoTime(),
        // which re-randomises the hyperplanes on every run, so whether a given query
        // vector lands in a populated bucket - and therefore whether these assertions
        // hold - would vary from run to run.
        LshConfig lshConfig = LshConfig.builder(DIMENSIONS)
            .numHashTables(8)
            .numHashFunctions(12)
            .numProbes(3)
            .seed(LSH_SEED)
            .build();
        lshIndex = new LshIndex(lshConfig);

        // Generate and insert random normalized vectors
        Random random = new Random(42); // Fixed seed for reproducibility
        vectors = new ArrayList<>();
        nodeIds = new ArrayList<>();

        for (int i = 0; i < NUM_VECTORS; i++) {
            float[] vector = generateRandomNormalizedVector(random, DIMENSIONS);
            vectors.add(vector);

            // Insert into HNSW graph
            long nodeId = graph.insert(vector);
            nodeIds.add(nodeId);

            // Insert into LSH index (use nodeId as entry ID)
            lshIndex.insert(vector, nodeId);
        }
    }

    @AfterAll
    void tearDown() {
        if (graph != null) {
            graph.close();
        }
    }

    @Test
    void testFilteredSearchOnlyReturnsCandidates() throws IOException {
        // Create a query vector
        Random random = new Random(123);
        float[] queryVector = generateRandomNormalizedVector(random, DIMENSIONS);

        // Query LSH to get candidate set
        Set<Long> lshCandidates = lshIndex.query(queryVector);

        // Ensure we have some candidates
        assertFalse(lshCandidates.isEmpty(), "LSH should return some candidates");

        // Perform filtered search
        List<HnswSearchResult> filteredResults = graph.search(queryVector, TOP_K, lshCandidates);

        // Verify all results are in the candidate set
        for (HnswSearchResult result : filteredResults) {
            assertTrue(lshCandidates.contains(result.nodeId()),
                "Result nodeId " + result.nodeId() + " should be in LSH candidate set");
        }
    }

    @Test
    void testFilteredSearchWithEmptyCandidatesReturnsEmpty() throws IOException {
        float[] queryVector = generateRandomNormalizedVector(new Random(456), DIMENSIONS);

        // Create empty candidate set
        Set<Long> emptyCandidates = new HashSet<>();

        // Filtered search with empty candidates should return empty
        List<HnswSearchResult> results = graph.search(queryVector, TOP_K, emptyCandidates);
        assertTrue(results.isEmpty(), "Filtered search with empty candidates should return empty");
    }

    @Test
    void testFilteredSearchWithNullCandidatesFallsBackToFullSearch() throws IOException {
        float[] queryVector = generateRandomNormalizedVector(new Random(789), DIMENSIONS);

        // Both should return same results
        List<HnswSearchResult> unfilteredResults = graph.search(queryVector, TOP_K);
        List<HnswSearchResult> nullFilteredResults = graph.search(queryVector, TOP_K, null);

        assertEquals(unfilteredResults.size(), nullFilteredResults.size(),
            "Null candidates should fall back to full search");

        for (int i = 0; i < unfilteredResults.size(); i++) {
            assertEquals(unfilteredResults.get(i).nodeId(), nullFilteredResults.get(i).nodeId(),
                "Results should match at position " + i);
        }
    }

    @Test
    void testCandidateReductionRatio() throws IOException {
        // Query vector
        Random random = new Random(999);
        float[] queryVector = generateRandomNormalizedVector(random, DIMENSIONS);

        // Get LSH candidates
        Set<Long> lshCandidates = lshIndex.query(queryVector);

        // Log for debugging
        System.out.println("Total vectors: " + NUM_VECTORS);
        System.out.println("LSH candidates: " + lshCandidates.size());
        if (!lshCandidates.isEmpty()) {
            double reductionRatio = (double) NUM_VECTORS / lshCandidates.size();
            System.out.println("Reduction ratio: " + reductionRatio + "x");
        }
        System.out.println("Target top_k: " + TOP_K);
        System.out.println("Candidates vs top_k ratio: " + ((double) lshCandidates.size() / TOP_K) + "x");

        // Verify LSH returns some candidates (non-empty)
        assertFalse(lshCandidates.isEmpty(),
            "LSH should return some candidates");

        // Verify candidate set is smaller than total (demonstrating filtering)
        assertTrue(lshCandidates.size() < NUM_VECTORS,
            "LSH candidate set (" + lshCandidates.size() + ") should be smaller than total (" + NUM_VECTORS + ")");
    }

    @Test
    void testFilteredSearchMaintainsValidDistances() throws IOException {
        Random random = new Random(111);
        float[] queryVector = generateRandomNormalizedVector(random, DIMENSIONS);

        Set<Long> lshCandidates = lshIndex.query(queryVector);
        List<HnswSearchResult> results = graph.search(queryVector, TOP_K, lshCandidates);

        // Verify distances are valid for cosine similarity
        for (HnswSearchResult result : results) {
            // Cosine similarity: 1.0 = identical, -1.0 = opposite
            // Values should be in range [-1, 1]
            assertTrue(result.distance() >= -1.0 - DELTA && result.distance() <= 1.0 + DELTA,
                "Distance " + result.distance() + " should be in range [-1, 1] for cosine similarity");
        }
    }

    @Test
    void testFilteredSearchResultsAreSorted() throws IOException {
        Random random = new Random(222);
        float[] queryVector = generateRandomNormalizedVector(random, DIMENSIONS);

        Set<Long> lshCandidates = lshIndex.query(queryVector);
        List<HnswSearchResult> results = graph.search(queryVector, TOP_K, lshCandidates);

        // Verify results are sorted by distance (for cosine: highest similarity = lowest distance value)
        // Actually for CosineSimilarity, higher values = more similar, so we need to check the sort order
        // Based on HnswGraph implementation, results are sorted by distance (closest first)
        // For cosine, closest means highest similarity value
        for (int i = 1; i < results.size(); i++) {
            // Results should be sorted by distance (ascending for distance, but cosine similarity
            // is handled specially - the graph sorts by distance value)
            assertTrue(results.get(i - 1).distance() <= results.get(i).distance() + DELTA,
                "Results should be sorted by distance at position " + i);
        }
    }

    @Test
    void testFilteredSearchNoDuplicateResults() throws IOException {
        Random random = new Random(333);
        float[] queryVector = generateRandomNormalizedVector(random, DIMENSIONS);

        Set<Long> lshCandidates = lshIndex.query(queryVector);
        List<HnswSearchResult> results = graph.search(queryVector, TOP_K, lshCandidates);

        // Verify no duplicate node IDs
        Set<Long> seenNodeIds = new HashSet<>();
        for (HnswSearchResult result : results) {
            assertTrue(seenNodeIds.add(result.nodeId()),
                "Should not have duplicate nodeId: " + result.nodeId());
        }
    }

    @Test
    void testFilteredSearchWithAllNodesAsCandidates() throws IOException {
        // Use all nodes as candidates
        Set<Long> allNodeIds = new HashSet<>(nodeIds);

        Random random = new Random(444);
        float[] queryVector = generateRandomNormalizedVector(random, DIMENSIONS);

        // Both should return same results
        List<HnswSearchResult> unfilteredResults = graph.search(queryVector, TOP_K);
        List<HnswSearchResult> filteredResults = graph.search(queryVector, TOP_K, allNodeIds);

        assertEquals(unfilteredResults.size(), filteredResults.size(),
            "Filtered search with all nodes should return same count as unfiltered");

        // Results should match (same node IDs)
        Set<Long> unfilteredIds = new HashSet<>();
        for (HnswSearchResult r : unfilteredResults) {
            unfilteredIds.add(r.nodeId());
        }

        Set<Long> filteredIds = new HashSet<>();
        for (HnswSearchResult r : filteredResults) {
            filteredIds.add(r.nodeId());
        }

        assertEquals(unfilteredIds, filteredIds,
            "Filtered search with all nodes should return same nodes as unfiltered");
    }

    @Test
    void testFilteredSearchWithSubsetCandidates() throws IOException {
        Random random = new Random(555);
        float[] queryVector = generateRandomNormalizedVector(random, DIMENSIONS);

        // First get unfiltered results to know which nodes should be in candidates
        List<HnswSearchResult> unfilteredResults = graph.search(queryVector, TOP_K);

        // Create a subset of candidates that includes the actual nearest neighbors
        Set<Long> subsetCandidates = new HashSet<>();
        for (HnswSearchResult r : unfilteredResults) {
            subsetCandidates.add(r.nodeId());
        }
        // Add more nodes to make a larger candidate set
        for (int i = 0; i < 100 && subsetCandidates.size() < 200; i++) {
            subsetCandidates.add(nodeIds.get(random.nextInt(nodeIds.size())));
        }

        // Filtered search should only return nodes from subset
        List<HnswSearchResult> filteredResults = graph.search(queryVector, TOP_K, subsetCandidates);

        // Verify all results are from subset
        for (HnswSearchResult result : filteredResults) {
            assertTrue(subsetCandidates.contains(result.nodeId()),
                "Result should be from subset candidates");
        }

        // Results should be non-empty since we included the nearest neighbors in candidates
        assertFalse(filteredResults.isEmpty(),
            "Filtered search with subset containing nearest neighbors should return results");
    }

    @Test
    void testRecallWithLshPreFiltering() throws IOException {
        // Recall measured against the answer the query actually asks for: the nearest members of
        // the candidate set. Anything less means the pre-filter named a match the search then
        // failed to return, which is a miss no caller can see.
        int numQueries = 10;
        int queriesMeasured = 0;

        Random random = new Random(777);

        for (int q = 0; q < numQueries; q++) {
            float[] queryVector = generateRandomNormalizedVector(random, DIMENSIONS);

            Set<Long> lshCandidates = lshIndex.query(queryVector);
            if (lshCandidates.isEmpty()) {
                continue;
            }

            List<Long> nearestInCandidates = lshCandidates.stream()
                .sorted(Comparator.comparingDouble(
                    id -> distanceFunction.distance(queryVector, vectors.get(nodeIds.indexOf(id)))))
                .limit(TOP_K)
                .toList();

            List<Long> found = graph.search(queryVector, TOP_K, lshCandidates).stream()
                .map(HnswSearchResult::nodeId)
                .toList();

            assertEquals(nearestInCandidates, found,
                "Filtered search must return the nearest members of the candidate set");
            queriesMeasured++;
        }

        assertTrue(queriesMeasured > 0,
            "LSH must name candidates for some query, or nothing is measured");
    }

    private float[] generateRandomNormalizedVector(Random random, int dimensions) {
        float[] vector = new float[dimensions];

        // Generate random values
        double sumSquares = 0.0;
        for (int i = 0; i < dimensions; i++) {
            vector[i] = random.nextFloat() * 2 - 1; // Range [-1, 1]
            sumSquares += vector[i] * vector[i];
        }

        // Normalize to unit length
        double magnitude = Math.sqrt(sumSquares);
        if (magnitude > 0) {
            for (int i = 0; i < dimensions; i++) {
                vector[i] /= magnitude;
            }
        }

        return vector;
    }
}
