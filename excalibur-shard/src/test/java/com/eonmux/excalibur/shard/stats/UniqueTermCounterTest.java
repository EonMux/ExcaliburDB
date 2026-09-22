package com.eonmux.excalibur.shard.stats;

import com.eonmux.excalibur.filter.hyperloglog.HyperLogLog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cardinality estimation for the query planner's selectivity estimates.
 *
 * <p>What is checked here is the shape of the estimate, not an exact count - the sketch is
 * probabilistic by construction. Three properties do have to hold exactly, though: the same term
 * added twice counts once (otherwise this measures insertions, not distinct terms), a merge of two
 * counters is the union rather than the sum (segments overlap, and adding their estimates would
 * over-count every shared term), and a serialised counter comes back reading the same as the one
 * that was written.</p>
 */
@DisplayName("Unique term counter")
class UniqueTermCounterTest {

    /** Within what the sketch's own standard error allows at this precision. */
    private static void assertEstimateNear(UniqueTermCounter counter, long expected) {
        double tolerance = Math.max(3.0, expected * counter.standardError() / 100.0 * 4);
        assertThat((double) counter.estimateUniqueTerms())
            .as("estimate of %d within %.1f (standard error %.2f%%)",
                expected, tolerance, counter.standardError())
            .isCloseTo(expected, org.assertj.core.data.Offset.offset(tolerance));
    }

    @Nested
    @DisplayName("counting")
    class Counting {

        @Test
        @DisplayName("an empty counter estimates zero")
        void emptyIsZero() {
            assertThat(new UniqueTermCounter().estimateUniqueTerms()).isZero();
        }

        @Test
        @DisplayName("distinct terms are counted once each")
        void distinctTermsAreCounted() {
            UniqueTermCounter counter = new UniqueTermCounter();
            for (int i = 0; i < 1000; i++) {
                counter.addTerm("term-" + i);
            }

            assertEstimateNear(counter, 1000);
        }

        @Test
        @DisplayName("a repeated term does not raise the count")
        void repeatsDoNotCount() {
            // Otherwise this measures how often terms were inserted, which is the term frequency
            // the planner tracks separately.
            UniqueTermCounter counter = new UniqueTermCounter();
            for (int i = 0; i < 10_000; i++) {
                counter.addTerm("the");
            }

            assertThat(counter.estimateUniqueTerms()).isEqualTo(1L);
        }

        @Test
        @DisplayName("an empty term is skipped rather than counted as a distinct one")
        void emptyTermsAreSkipped() {
            UniqueTermCounter counter = new UniqueTermCounter();
            counter.addTerm("");
            counter.addTermBytes(new byte[0]);

            assertThat(counter.estimateUniqueTerms()).isZero();
        }

        @Test
        @DisplayName("the string and byte entry points agree")
        void stringAndBytesAgree() {
            UniqueTermCounter viaString = new UniqueTermCounter();
            UniqueTermCounter viaBytes = new UniqueTermCounter();
            for (int i = 0; i < 200; i++) {
                viaString.addTerm("term-" + i);
                viaBytes.addTermBytes(("term-" + i).getBytes(StandardCharsets.UTF_8));
            }

            assertThat(viaBytes.estimateUniqueTerms())
                .as("the byte entry point exists to avoid re-encoding, not to count differently")
                .isEqualTo(viaString.estimateUniqueTerms());
        }

        @Test
        @DisplayName("terms differing only past the first bytes are still distinct")
        void longSharedPrefixesAreDistinct() {
            UniqueTermCounter counter = new UniqueTermCounter();
            String prefix = "a".repeat(200);
            counter.addTerm(prefix + "1");
            counter.addTerm(prefix + "2");

            assertThat(counter.estimateUniqueTerms()).isEqualTo(2L);
        }

        @Test
        @DisplayName("non-ASCII terms are counted by their bytes, not mangled")
        void unicodeTermsAreCounted() {
            UniqueTermCounter counter = new UniqueTermCounter();
            counter.addTerm("naïve");
            counter.addTerm("naive");
            counter.addTerm("漢字");

            assertThat(counter.estimateUniqueTerms()).isEqualTo(3L);
        }

        @Test
        @DisplayName("clearing returns the counter to empty")
        void clearingEmpties() {
            UniqueTermCounter counter = new UniqueTermCounter();
            for (int i = 0; i < 100; i++) {
                counter.addTerm("t" + i);
            }

            counter.clear();

            assertThat(counter.estimateUniqueTerms()).isZero();
        }

        @Test
        @DisplayName("a null term is refused rather than counted as the text \"null\"")
        void nullTermsAreRefused() {
            UniqueTermCounter counter = new UniqueTermCounter();

            assertThatThrownBy(() -> counter.addTerm(null))
                .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> counter.addTermBytes(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("precision")
    class Precision {

        @Test
        @DisplayName("the default is the documented precision")
        void defaultPrecision() {
            UniqueTermCounter counter = new UniqueTermCounter();

            assertThat(counter.precision()).isEqualTo(UniqueTermCounter.DEFAULT_PRECISION);
            assertThat(counter.numRegisters()).isEqualTo(1 << UniqueTermCounter.DEFAULT_PRECISION);
        }

        @Test
        @DisplayName("higher precision means lower error and more memory")
        void precisionTradesMemoryForError() {
            UniqueTermCounter coarse = new UniqueTermCounter(UniqueTermCounter.MIN_PRECISION);
            UniqueTermCounter fine = new UniqueTermCounter(UniqueTermCounter.MAX_PRECISION);

            assertThat(fine.standardError()).isLessThan(coarse.standardError());
            assertThat(fine.estimatedMemoryUsage()).isGreaterThan(coarse.estimatedMemoryUsage());
            assertThat(fine.numRegisters()).isGreaterThan(coarse.numRegisters());
        }

        @Test
        @DisplayName("a precision outside the supported range is refused")
        void outOfRangePrecisionIsRefused() {
            assertThatThrownBy(
                () -> new UniqueTermCounter(UniqueTermCounter.MIN_PRECISION - 1))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                () -> new UniqueTermCounter(UniqueTermCounter.MAX_PRECISION + 1))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a counter can be built around an existing sketch")
        void wrapsAnExistingSketch() {
            HyperLogLog sketch = new HyperLogLog(12);
            sketch.insert("hello".getBytes(StandardCharsets.UTF_8));

            UniqueTermCounter counter = new UniqueTermCounter(sketch);

            assertThat(counter.precision()).isEqualTo(12);
            assertThat(counter.estimateUniqueTerms()).isEqualTo(1L);
            assertThat(counter.getHyperLogLog()).isSameAs(sketch);
        }

        @Test
        @DisplayName("a null sketch is refused")
        void nullSketchIsRefused() {
            assertThatThrownBy(() -> new UniqueTermCounter((HyperLogLog) null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("merging")
    class Merging {

        @Test
        @DisplayName("merging overlapping counters is a union, not a sum")
        void mergeIsAUnion() {
            // Segments share terms. Adding their estimates would over-count every shared term,
            // and the planner would size a scan against a corpus that does not exist.
            UniqueTermCounter left = new UniqueTermCounter();
            UniqueTermCounter right = new UniqueTermCounter();
            for (int i = 0; i < 600; i++) {
                left.addTerm("term-" + i);
            }
            for (int i = 300; i < 900; i++) {
                right.addTerm("term-" + i);
            }

            left.merge(right);

            assertEstimateNear(left, 900);
        }

        @Test
        @DisplayName("merging disjoint counters totals them")
        void disjointMergeTotals() {
            UniqueTermCounter left = new UniqueTermCounter();
            UniqueTermCounter right = new UniqueTermCounter();
            for (int i = 0; i < 500; i++) {
                left.addTerm("left-" + i);
                right.addTerm("right-" + i);
            }

            left.merge(right);

            assertEstimateNear(left, 1000);
        }

        @Test
        @DisplayName("merging leaves the other counter alone")
        void mergeDoesNotDisturbTheSource() {
            UniqueTermCounter left = new UniqueTermCounter();
            UniqueTermCounter right = new UniqueTermCounter();
            left.addTerm("a");
            right.addTerm("b");

            left.merge(right);

            assertThat(right.estimateUniqueTerms())
                .as("a counter merged into another is still its segment's own count")
                .isEqualTo(1L);
        }

        @Test
        @DisplayName("merging an empty counter changes nothing")
        void mergingEmptyIsANoOp() {
            UniqueTermCounter counter = new UniqueTermCounter();
            for (int i = 0; i < 100; i++) {
                counter.addTerm("t" + i);
            }
            long before = counter.estimateUniqueTerms();

            counter.merge(new UniqueTermCounter());

            assertThat(counter.estimateUniqueTerms()).isEqualTo(before);
        }

        @Test
        @DisplayName("counters of different precision cannot be merged")
        void mismatchedPrecisionIsRefused() {
            UniqueTermCounter coarse = new UniqueTermCounter(10);
            UniqueTermCounter fine = new UniqueTermCounter(14);

            assertThatThrownBy(() -> coarse.merge(fine))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a null counter is refused")
        void nullMergeIsRefused() {
            assertThatThrownBy(() -> new UniqueTermCounter().merge(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("serialisation")
    class Serialisation {

        @Test
        @DisplayName("a counter round-trips through bytes")
        void roundTrip() {
            UniqueTermCounter original = new UniqueTermCounter();
            for (int i = 0; i < 500; i++) {
                original.addTerm("term-" + i);
            }

            UniqueTermCounter restored = UniqueTermCounter.deserialize(original.serialize());

            assertThat(restored.precision()).isEqualTo(original.precision());
            assertThat(restored.estimateUniqueTerms()).isEqualTo(original.estimateUniqueTerms());
        }

        @Test
        @DisplayName("a restored counter is usable, not backed by freed memory")
        void restoredCounterIsUsable() {
            // deserialize copies through a confined arena that is closed before it returns. If
            // the sketch retained that segment rather than copying out of it, every later read
            // would touch memory that has been released.
            UniqueTermCounter original = new UniqueTermCounter();
            original.addTerm("a");

            UniqueTermCounter restored = UniqueTermCounter.deserialize(original.serialize());
            restored.addTerm("b");
            restored.addTerm("c");

            assertThat(restored.estimateUniqueTerms()).isEqualTo(3L);
            assertThat(restored.toString()).contains("UniqueTermCounter");
        }

        @Test
        @DisplayName("an empty counter round-trips")
        void emptyRoundTrips() {
            UniqueTermCounter restored =
                UniqueTermCounter.deserialize(new UniqueTermCounter().serialize());

            assertThat(restored.estimateUniqueTerms()).isZero();
        }

        @Test
        @DisplayName("every supported precision round-trips")
        void everyPrecisionRoundTrips() {
            for (int precision = UniqueTermCounter.MIN_PRECISION;
                 precision <= UniqueTermCounter.MAX_PRECISION; precision++) {
                UniqueTermCounter original = new UniqueTermCounter(precision);
                original.addTerm("x");

                UniqueTermCounter restored = UniqueTermCounter.deserialize(original.serialize());

                assertThat(restored.precision()).as("precision %d", precision).isEqualTo(precision);
                assertThat(restored.estimateUniqueTerms()).isEqualTo(1L);
            }
        }

        @Test
        @DisplayName("a restored counter merges with one that was never written out")
        void restoredCountersMerge() {
            UniqueTermCounter first = new UniqueTermCounter();
            first.addTerm("a");
            UniqueTermCounter second = new UniqueTermCounter();
            second.addTerm("b");

            UniqueTermCounter restored = UniqueTermCounter.deserialize(first.serialize());
            restored.merge(second);

            assertThat(restored.estimateUniqueTerms()).isEqualTo(2L);
        }

        @Test
        @DisplayName("bytes that are not a counter are refused rather than read as one")
        void garbageIsRefused() {
            assertThatThrownBy(() -> UniqueTermCounter.deserialize(new byte[8]))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> UniqueTermCounter.deserialize(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> UniqueTermCounter.deserialize(null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("a truncated counter is refused rather than read short")
        void truncatedInputIsRefused() {
            byte[] full = new UniqueTermCounter().serialize();
            byte[] truncated = java.util.Arrays.copyOf(full, full.length / 2);

            assertThatThrownBy(() -> UniqueTermCounter.deserialize(truncated))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("terms added from several threads are all counted")
        void concurrentAddsAreSafe() throws InterruptedException {
            UniqueTermCounter counter = new UniqueTermCounter();
            int threads = 4;
            int perThread = 1000;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger failures = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                final int id = t;
                Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            counter.addTerm("t" + id + "-" + i);
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(failures.get()).isZero();
            assertEstimateNear(counter, (long) threads * perThread);
        }
    }
}
