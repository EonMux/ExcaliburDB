package com.eonmux.excalibur.vector.hnsw;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HNSW visited-marker pool")
class HnswVisitedPoolTest {

    @Test
    @DisplayName("a node is new on its first visit in a walk and seen after it")
    void shouldReportOnlyTheFirstVisit() {
        HnswVisitedPool.Marker marker = new HnswVisitedPool().borrow(10);

        assertThat(marker.visit(4)).isTrue();
        assertThat(marker.visit(4)).isFalse();
        assertThat(marker.visit(5)).isTrue();
    }

    @Test
    @DisplayName("a marker borrowed again remembers nothing from the walk before")
    void shouldForgetEveryVisitWhenLentAgain() {
        HnswVisitedPool pool = new HnswVisitedPool();
        HnswVisitedPool.Marker first = pool.borrow(10);
        first.visit(3);
        pool.release(first);

        HnswVisitedPool.Marker second = pool.borrow(10);

        assertThat(second).isSameAs(first);
        assertThat(second.visit(3)).isTrue();
    }

    @Test
    @DisplayName("wrapping the epoch clears stamps an old walk left, which would otherwise read as visited")
    void shouldClearStaleStampsWhenTheEpochWraps() {
        HnswVisitedPool.Marker marker = new HnswVisitedPool.Marker(10, 3);
        marker.reset(10);                 // epoch 1
        marker.visit(7);                  // stamped 1
        marker.reset(10);                 // epoch 2
        marker.reset(10);                 // epoch 3, the last
        marker.reset(10);                 // wraps back to epoch 1

        assertThat(marker.visit(7)).as("a stamp from the previous epoch 1").isTrue();
    }

    @Test
    @DisplayName("a marker grows for a larger graph and keeps working")
    void shouldGrowToTheNodeLimit() {
        HnswVisitedPool pool = new HnswVisitedPool();
        pool.release(pool.borrow(4));

        HnswVisitedPool.Marker marker = pool.borrow(1000);

        assertThat(marker.capacity()).isGreaterThanOrEqualTo(1000);
        assertThat(marker.visit(999)).isTrue();
        assertThat(marker.visit(999)).isFalse();
    }

    @Test
    @DisplayName("a node limit outside what an int indexes is rejected")
    void shouldRejectAnUnindexableLimit() {
        HnswVisitedPool pool = new HnswVisitedPool();

        assertThatThrownBy(() -> pool.borrow(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pool.borrow(Integer.MAX_VALUE + 1L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("markers on loan at once are distinct")
    void shouldNeverLendOneMarkerTwice() {
        HnswVisitedPool pool = new HnswVisitedPool();

        HnswVisitedPool.Marker a = pool.borrow(10);
        HnswVisitedPool.Marker b = pool.borrow(10);

        assertThat(a).isNotSameAs(b);
        a.visit(1);
        assertThat(b.visit(1)).isTrue();
    }

    @Test
    @DisplayName("concurrent walks each see every node as new exactly once")
    void shouldIsolateConcurrentWalks() throws Exception {
        HnswVisitedPool pool = new HnswVisitedPool();
        int threads = 8;
        int nodes = 2_000;
        int walksPerThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                results.add(executor.submit(() -> {
                    start.await();
                    int failures = 0;
                    for (int walk = 0; walk < walksPerThread; walk++) {
                        HnswVisitedPool.Marker marker = pool.borrow(nodes);
                        try {
                            for (int id = 0; id < nodes; id++) {
                                if (!marker.visit(id) || marker.visit(id)) {
                                    failures++;
                                }
                            }
                        } finally {
                            pool.release(marker);
                        }
                    }
                    return failures;
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(60, TimeUnit.SECONDS)).isZero();
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
