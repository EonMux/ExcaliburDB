package excalibur.query.cache;

import excalibur.query.IndexCoverage;
import excalibur.query.QueryResponse;
import excalibur.query.QueryResultItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the cache may and may not hand back.
 *
 * <p>The tests that matter here are the refusals. A cache that returns the right answer
 * quickly is worth having; one that returns last minute's answer to this minute's question is
 * worse than none at all, because a search engine answering from a stale index looks exactly
 * like one that is working.</p>
 */
@DisplayName("QueryResultCache")
class QueryResultCacheTest {

    private static final CorpusVersion V1 = new CorpusVersion(7L, 0x1111L, 3);
    private static final CorpusVersion V2 = new CorpusVersion(8L, 0x2222L, 3);

    private static QueryCacheKey key(String text) {
        return QueryCacheKey.builder()
            .namespace("default")
            .securityContext("token")
            .retrievalMode("vector")
            .queryText(text)
            .topK(10)
            .offset(0)
            .threshold(0.0f)
            .includePartialSegments(true)
            .enableLlmScoring(false)
            .llmThreshold(0.3f)
            .oversamplingFactor(2)
            .includeText(false)
            .snippet(false)
            .snippetChars(320)
            .explain(false)
            .build();
    }

    private static QueryResponse healthy(String documentId) {
        IndexCoverage coverage = IndexCoverage.builder()
            .totalSegments(3)
            .activeSegments(3)
            .segmentsSearched(3)
            .estimatedCorpusCoverage(1.0)
            .degraded(false)
            .build();
        List<QueryResultItem> results =
            List.of(new QueryResultItem(documentId, 0.9f, null, 1L, 1L));
        return new QueryResponse("original-id", results, coverage, 42L, false, false);
    }

    private static QueryResponse degraded() {
        IndexCoverage coverage = IndexCoverage.builder()
            .totalSegments(3)
            .activeSegments(1)
            .segmentsSearched(1)
            .estimatedCorpusCoverage(0.33)
            .degraded(true)
            .build();
        return new QueryResponse(null, List.of(), coverage, 5L, false, false);
    }

    @Nested
    @DisplayName("Serving")
    class Serving {

        @Test
        @DisplayName("a repeated identical query is served from cache and says so")
        void repeatedQueryIsServedFromCache() {
            QueryResultCache cache = new QueryResultCache(16);
            assertThat(cache.get(key("alpha"), V1))
                .as("nothing has been stored yet")
                .isNull();

            cache.put(key("alpha"), V1, healthy("doc-1"));

            QueryResponse served = cache.get(key("alpha"), V1);
            assertThat(served).isNotNull();
            assertThat(served.fromCache())
                .as("a cached answer must say that is what it is")
                .isTrue();
            assertThat(served.results()).extracting(QueryResultItem::documentIdStr)
                .containsExactly("doc-1");
        }

        @Test
        @DisplayName("the stored response is not itself mutated into a cached one")
        void storedResponseKeepsItsOwnFlag() {
            QueryResultCache cache = new QueryResultCache(16);
            QueryResponse original = healthy("doc-1");
            cache.put(key("alpha"), V1, original);

            assertThat(original.fromCache())
                .as("the caller's own response is not retroactively a cache hit")
                .isFalse();
            assertThat(cache.get(key("alpha"), V1).fromCache()).isTrue();
        }

        @Test
        @DisplayName("a different key is a miss")
        void differentKeyIsAMiss() {
            QueryResultCache cache = new QueryResultCache(16);
            cache.put(key("alpha"), V1, healthy("doc-1"));
            assertThat(cache.get(key("beta"), V1)).isNull();
        }
    }

    @Nested
    @DisplayName("Invalidation")
    class Invalidation {

        @Test
        @DisplayName("a query is not served from cache once the corpus has changed")
        void corpusChangeInvalidates() {
            QueryResultCache cache = new QueryResultCache(16);
            cache.put(key("alpha"), V1, healthy("doc-1"));
            assertThat(cache.get(key("alpha"), V1)).isNotNull();

            assertThat(cache.get(key("alpha"), V2))
                .as("the corpus moved under the entry, so it is not an answer any more")
                .isNull();
        }

        @Test
        @DisplayName("a corpus change discards every entry, not only the one looked up")
        void corpusChangeDiscardsEveryEntry() {
            QueryResultCache cache = new QueryResultCache(16);
            cache.put(key("alpha"), V1, healthy("doc-1"));
            cache.put(key("beta"), V1, healthy("doc-2"));
            assertThat(cache.stats().size()).isEqualTo(2);

            cache.get(key("alpha"), V2);

            assertThat(cache.stats().size()).isZero();
            assertThat(cache.stats().invalidations()).isEqualTo(2);
            assertThat(cache.get(key("beta"), V1))
                .as("an entry from a superseded corpus must never come back")
                .isNull();
        }

        @Test
        @DisplayName("an answer that finished after the corpus moved is dropped, not adopted")
        void aLateAnswerDoesNotRollTheCacheBackwards() {
            QueryResultCache cache = new QueryResultCache(16);

            // A slow query fingerprints V1. While it runs, a second query observes V2, which
            // discards the old generation, and stores its own answer.
            cache.get(key("alpha"), V1);
            cache.get(key("beta"), V2);
            cache.put(key("beta"), V2, healthy("doc-2"));

            // The slow query now finishes and offers an answer computed against V1.
            cache.put(key("alpha"), V1, healthy("doc-1"));

            assertThat(cache.get(key("alpha"), V2))
                .as("an answer to a corpus that no longer exists must not be stored")
                .isNull();
            assertThat(cache.get(key("beta"), V2))
                .as("and it must not evict the entries that are still answers")
                .isNotNull();
        }

        @Test
        @DisplayName("an unreadable corpus version is never served from and never stored to")
        void unreadableVersionIsNeverServed() {
            QueryResultCache cache = new QueryResultCache(16);
            cache.put(key("alpha"), CorpusVersion.UNREADABLE, healthy("doc-1"));
            assertThat(cache.stats().size()).isZero();

            cache.put(key("alpha"), V1, healthy("doc-1"));
            assertThat(cache.get(key("alpha"), CorpusVersion.UNREADABLE))
                .as("a corpus that cannot be read cannot be shown to be unchanged")
                .isNull();
        }
    }

    @Nested
    @DisplayName("What is refused")
    class Refusals {

        @Test
        @DisplayName("a degraded answer is not stored")
        void degradedIsNotStored() {
            QueryResultCache cache = new QueryResultCache(16);
            cache.put(key("alpha"), V1, degraded());
            assertThat(cache.stats().size()).isZero();
            assertThat(cache.get(key("alpha"), V1)).isNull();
        }

        @Test
        @DisplayName("a timed-out answer is not stored")
        void timedOutIsNotStored() {
            QueryResultCache cache = new QueryResultCache(16);
            cache.put(key("alpha"), V1, healthy("doc-1").withTimedOut(true));
            assertThat(cache.stats().size()).isZero();
        }

        @Test
        @DisplayName("a cancelled answer is not stored")
        void cancelledIsNotStored() {
            QueryResultCache cache = new QueryResultCache(16);
            cache.put(key("alpha"), V1, healthy("doc-1").withCancelled(true));
            assertThat(cache.stats().size()).isZero();
        }

        @Test
        @DisplayName("cacheable names the same rule the cache applies")
        void cacheablePredicateMatchesTheRule() {
            assertThat(QueryResultCache.cacheable(healthy("doc-1"))).isTrue();
            assertThat(QueryResultCache.cacheable(degraded())).isFalse();
            assertThat(QueryResultCache.cacheable(healthy("d").withTimedOut(true))).isFalse();
            assertThat(QueryResultCache.cacheable(healthy("d").withCancelled(true))).isFalse();
            assertThat(QueryResultCache.cacheable(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("Bounded memory")
    class BoundedMemory {

        @Test
        @DisplayName("eviction holds the cache at its capacity however many queries arrive")
        void evictionBoundsTheCache() {
            QueryResultCache cache = new QueryResultCache(8);

            for (int i = 0; i < 1000; i++) {
                cache.put(key("query-" + i), V1, healthy("doc-" + i));
                assertThat(cache.stats().size())
                    .as("the cache must never hold more than its capacity")
                    .isLessThanOrEqualTo(8);
            }

            QueryCacheStats stats = cache.stats();
            assertThat(stats.size()).isEqualTo(8);
            assertThat(stats.evictions())
                .as("every entry past the capacity is evicted, not dropped silently")
                .isEqualTo(1000 - 8);
        }

        @Test
        @DisplayName("the least recently used entry is the one evicted")
        void evictsTheLeastRecentlyUsed() {
            QueryResultCache cache = new QueryResultCache(2);
            cache.put(key("alpha"), V1, healthy("doc-1"));
            cache.put(key("beta"), V1, healthy("doc-2"));

            // Touch alpha so beta becomes the least recently used of the two.
            assertThat(cache.get(key("alpha"), V1)).isNotNull();
            cache.put(key("gamma"), V1, healthy("doc-3"));

            assertThat(cache.get(key("alpha"), V1)).isNotNull();
            assertThat(cache.get(key("beta"), V1)).isNull();
            assertThat(cache.get(key("gamma"), V1)).isNotNull();
        }

        @Test
        @DisplayName("a capacity below one is refused rather than silently corrected")
        void capacityIsValidated() {
            assertThatThrownBy(() -> new QueryResultCache(0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new QueryResultCache(-1))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Statistics")
    class Statistics {

        @Test
        @DisplayName("hits, misses, evictions and size are all counted")
        void countsEverythingAnOperatorNeeds() {
            QueryResultCache cache = new QueryResultCache(2);
            cache.get(key("alpha"), V1);                  // miss
            cache.put(key("alpha"), V1, healthy("doc-1"));
            cache.get(key("alpha"), V1);                  // hit
            cache.get(key("alpha"), V1);                  // hit
            cache.put(key("beta"), V1, healthy("doc-2"));
            cache.put(key("gamma"), V1, healthy("doc-3")); // evicts one

            QueryCacheStats stats = cache.stats();
            assertThat(stats.hits()).isEqualTo(2);
            assertThat(stats.misses()).isEqualTo(1);
            assertThat(stats.evictions()).isEqualTo(1);
            assertThat(stats.size()).isEqualTo(2);
            assertThat(stats.capacity()).isEqualTo(2);
            assertThat(stats.hitRatio()).isEqualTo(2.0 / 3.0);
        }

        @Test
        @DisplayName("a cache nobody has asked anything reports a zero hit ratio, not a NaN")
        void emptyCacheReportsZeroRatio() {
            assertThat(new QueryResultCache(4).stats().hitRatio()).isZero();
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        @Test
        @DisplayName("concurrent readers and writers never exceed the bound or lose a count")
        void survivesConcurrentUse() throws Exception {
            QueryResultCache cache = new QueryResultCache(32);
            int threads = 8;
            int perThread = 500;
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger failures = new AtomicInteger();

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                for (int t = 0; t < threads; t++) {
                    final int id = t;
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < perThread; i++) {
                                QueryCacheKey k = key("q-" + ((id * 7 + i) % 64));
                                cache.put(k, V1, healthy("doc-" + i));
                                cache.get(k, V1);
                                if (cache.stats().size() > 32) {
                                    failures.incrementAndGet();
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            failures.incrementAndGet();
                        }
                    });
                }
                start.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            assertThat(failures.get()).isZero();
            QueryCacheStats stats = cache.stats();
            assertThat(stats.size()).isLessThanOrEqualTo(32);
            assertThat(stats.hits() + stats.misses()).isEqualTo((long) threads * perThread);
        }
    }
}
