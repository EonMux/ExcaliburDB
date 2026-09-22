package com.eonmux.excalibur.tenancy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

/**
 * The budget that decides when a namespace is evicted.
 *
 * <p>Where it comes from matters as much as what it does with it. The limit is read out of
 * {@code /proc}, which reports no size and may hand back one digit at a time; a leading digit of
 * a real limit parses cleanly and is wrong by orders of magnitude, and a limit of 1 read out of a
 * file holding 16777216 puts every namespace over budget the moment it opens.</p>
 */
@DisplayName("Mapping budget")
class MappingBudgetTest {

    @Nested
    @DisplayName("Reading the platform limit")
    class ReadingTheLimit {

        @Test
        @DisplayName("a limit delivered one byte at a time is read whole")
        void aShortReadDoesNotTruncateTheLimit() throws IOException {
            assertThat(MappingBudget.readLimit(oneByteAtATime("16777216\n")))
                .isEqualTo(16_777_216L);
        }

        @Test
        @DisplayName("a limit that arrives in one piece is read the same way")
        void aWholeReadIsUnchanged() throws IOException {
            assertThat(MappingBudget.readLimit(
                new ByteArrayInputStream("65530\n".getBytes(StandardCharsets.US_ASCII))))
                .isEqualTo(65_530L);
        }

        @Test
        @DisplayName("a file holding something other than a number is refused, not guessed at")
        void unexpectedContentIsRefused() {
            assertThatThrownBy(() -> MappingBudget.readLimit(
                new ByteArrayInputStream("unlimited".getBytes(StandardCharsets.US_ASCII))))
                .isInstanceOf(NumberFormatException.class);
        }
    }

    @Nested
    @DisplayName("The detected budget")
    class Detected {

        @Test
        @DisplayName("is large enough for a namespace to open inside it")
        void leavesRoomForANamespace() {
            // The check this replaces: a budget read short reported a high-water mark of 0, so
            // the first namespace opened was already over it and every open logged a warning
            // about evicting namespaces that did not exist.
            MappingBudget budget = MappingBudget.detect();

            assertThat(budget.highWaterMark())
                .as("a budget with no room for one namespace can only be a misread limit")
                .isGreaterThan(0);
            assertThat(budget.wouldExceedHighWater(1, 1)).isFalse();
        }
    }

    @Nested
    @DisplayName("The descriptor half of the budget")
    class Descriptors {

        @Test
        @DisplayName("reads the process's open-file limit out of /proc/self/limits")
        void readsTheDescriptorLimit() {
            String limits = """
                Limit                     Soft Limit           Hard Limit           Units
                Max cpu time              unlimited            unlimited            seconds
                Max open files            1024                 524288               files
                Max locked memory         unlimited            unlimited            bytes
                """;

            assertThat(MappingBudget.readDescriptorLimit(limits)).isEqualTo(1024L);
        }

        @Test
        @DisplayName("treats an unlimited soft limit as no descriptor bound to enforce")
        void unlimitedIsNoBound() {
            assertThat(MappingBudget.readDescriptorLimit(
                "Max open files            unlimited            unlimited            files\n"))
                .isEqualTo(0L);
        }

        @Test
        @DisplayName("reports nothing rather than guessing when the line is absent")
        void absentLineIsNotGuessed() {
            assertThat(MappingBudget.readDescriptorLimit("Max cpu time  unlimited"))
                .isEqualTo(0L);
        }

        @Test
        @DisplayName("evicts on descriptors, not only on mappings")
        void descriptorsDriveEviction() {
            // A namespace is two Lucene indexes' worth of descriptors and thirteen mappings, and
            // which limit binds first depends on the deployment. Counting descriptors and then
            // never consulting them made the second half of the budget decorative.
            MappingBudget budget = MappingBudget.of(1_000_000, 0.7, 100);

            budget.acquire(1, 69);
            assertThat(budget.isOverHighWater()).isFalse();
            assertThat(budget.wouldExceedHighWater(1, 2)).isTrue();

            budget.acquire(1, 2);
            assertThat(budget.isOverHighWater())
                .as("71 descriptors is past the 70 the high-water fraction allows")
                .isTrue();
        }

        @Test
        @DisplayName("enforces no descriptor bound when the platform publishes none")
        void noBoundWhenUnknown() {
            MappingBudget budget = MappingBudget.of(1_000, 0.7, 0);

            budget.acquire(0, 1_000_000);

            assertThat(budget.maxDescriptors()).isZero();
            assertThat(budget.isOverHighWater()).isFalse();
            assertThat(budget.wouldExceedHighWater(1, 1_000_000)).isFalse();
        }

        @Test
        @DisplayName("names both halves, so an operator can see which one bound")
        void saysWhichLimitBound() {
            assertThat(MappingBudget.of(1_000, 0.7, 500).toString())
                .contains("fds=0/500");
        }
    }

    @Nested
    @DisplayName("The rest of the process")
    class RestOfProcess {

        /** A process whose figures a test sets, standing in for {@code /proc/self}. */
        private static final class FixedProcess implements ProcessResourceUsage {
            private volatile OptionalLong mappings = OptionalLong.empty();
            private volatile OptionalLong descriptors = OptionalLong.empty();

            FixedProcess holding(long mappingCount, long descriptorCount) {
                this.mappings = OptionalLong.of(mappingCount);
                this.descriptors = OptionalLong.of(descriptorCount);
                return this;
            }

            FixedProcess unreadable() {
                this.mappings = OptionalLong.empty();
                this.descriptors = OptionalLong.empty();
                return this;
            }

            @Override
            public OptionalLong mappings() {
                return mappings;
            }

            @Override
            public OptionalLong descriptors() {
                return descriptors;
            }
        }

        @Test
        @DisplayName("counts everything the graphs do not account for against the limit")
        void theRestOfTheProcessIsSpent() {
            // The defect this closes: the budget compared the graphs alone against a limit that
            // belongs to the whole process, so a node whose storage segments and vector graphs
            // held most of vm.max_map_count reported room it did not have.
            FixedProcess process = new FixedProcess().holding(650, 40);
            MappingBudget budget = MappingBudget.of(1_000, 0.7, 100, process);
            budget.acquire(50, 5);

            assertThat(budget.refreshProcessUsage()).isTrue();

            assertThat(budget.otherMappings()).isEqualTo(600);
            assertThat(budget.otherDescriptors()).isEqualTo(35);
            assertThat(budget.mappings()).as("the graphs' own figure is unchanged").isEqualTo(50);
            assertThat(budget.isOverHighWater()).isFalse();
            assertThat(budget.wouldExceedHighWater(51, 0))
                .as("650 held plus 51 is past the 700 the fraction allows")
                .isTrue();
            assertThat(budget.wouldExceedHighWater(0, 31))
                .as("40 descriptors held plus 31 is past 70")
                .isTrue();
            assertThat(budget.utilisation()).isCloseTo(0.65, offset(1e-9));
        }

        @Test
        @DisplayName("an unmeasured budget is the graphs alone, as it always was")
        void unmeasuredCountsTheGraphsAlone() {
            MappingBudget budget = MappingBudget.of(1_000, 0.7, 100);
            budget.acquire(50, 5);

            assertThat(budget.refreshProcessUsage()).isFalse();
            assertThat(budget.otherMappings()).isZero();
            assertThat(budget.otherDescriptors()).isZero();
            assertThat(budget.wouldExceedHighWater(600, 0)).isFalse();
        }

        @Test
        @DisplayName("a failed measurement keeps the last one rather than forgetting the node")
        void aFailedReadKeepsThePreviousFigure() {
            FixedProcess process = new FixedProcess().holding(500, 20);
            MappingBudget budget = MappingBudget.of(1_000, 0.7, 100, process);
            budget.refreshProcessUsage();

            process.unreadable();

            assertThat(budget.refreshProcessUsage()).isFalse();
            assertThat(budget.otherMappings()).isEqualTo(500);
            assertThat(budget.otherDescriptors()).isEqualTo(20);
        }

        @Test
        @DisplayName("graphs costed above what the process holds never make the rest negative")
        void theRestIsNeverNegative() {
            // A graph whose Lucene share shrank since it was costed is accounted at more than it
            // holds; a negative rest of process would be headroom that does not exist.
            FixedProcess process = new FixedProcess().holding(10, 1);
            MappingBudget budget = MappingBudget.of(1_000, 0.7, 100, process);
            budget.acquire(50, 5);

            budget.refreshProcessUsage();

            assertThat(budget.otherMappings()).isZero();
            assertThat(budget.otherDescriptors()).isZero();
        }

        @Test
        @DisplayName("says when the rest of the process alone is past the mark")
        void recognisesWhatEvictionCannotFix() {
            FixedProcess process = new FixedProcess().holding(750, 0);
            MappingBudget budget = MappingBudget.of(1_000, 0.7, 100, process);
            budget.refreshProcessUsage();

            assertThat(budget.isRestOfProcessOverHighWater()).isTrue();
            assertThat(budget.isOverHighWater()).isTrue();

            process.holding(10, 80);
            budget.refreshProcessUsage();
            assertThat(budget.isRestOfProcessOverHighWater())
                .as("80 descriptors is past the 70 the fraction allows")
                .isTrue();

            process.holding(10, 10);
            budget.refreshProcessUsage();
            assertThat(budget.isRestOfProcessOverHighWater()).isFalse();
        }

        @Test
        @DisplayName("ignores descriptors when no descriptor limit is enforced")
        void noDescriptorBoundMeansNoDescriptorPressure() {
            FixedProcess process = new FixedProcess().holding(10, 1_000_000);
            MappingBudget budget = MappingBudget.of(1_000, 0.7, 0, process);
            budget.refreshProcessUsage();

            assertThat(budget.isRestOfProcessOverHighWater()).isFalse();
            assertThat(budget.isOverHighWater()).isFalse();
        }

        @Test
        @DisplayName("reports the process measured now, and does not move the decision by doing so")
        void usageIsAFreshReadThatChangesNothing() {
            FixedProcess process = new FixedProcess().holding(300, 30);
            MappingBudget budget = MappingBudget.of(1_000, 0.7, 100, process);
            budget.acquire(40, 4);
            budget.refreshProcessUsage();

            process.holding(900, 90);
            MappingBudget.Usage usage = budget.usage();

            assertThat(usage.graphMappings()).isEqualTo(40);
            assertThat(usage.processMappings()).hasValue(900);
            assertThat(usage.graphDescriptors()).isEqualTo(4);
            assertThat(usage.processDescriptors()).hasValue(90);
            assertThat(usage.mappingHighWater()).isEqualTo(700);
            assertThat(usage.maxMappings()).isEqualTo(1_000);
            assertThat(usage.descriptorHighWater()).isEqualTo(70);
            assertThat(usage.maxDescriptors()).isEqualTo(100);
            assertThat(usage.limitWasRead()).isTrue();
            assertThat(budget.otherMappings())
                .as("a report is not a measurement the eviction decision acts on")
                .isEqualTo(260);
        }

        @Test
        @DisplayName("reports an unmeasurable process as unknown")
        void usageOfAnUnmeasuredProcess() {
            MappingBudget.Usage usage = MappingBudget.of(1_000, 0.7).usage();

            assertThat(usage.processMappings()).isEmpty();
            assertThat(usage.processDescriptors()).isEmpty();
            assertThatThrownBy(() -> new MappingBudget.Usage(0, null, 0, 1, 0,
                OptionalLong.empty(), 0, 0, true)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("refuses a budget with no way of asking the process")
        void aProcessIsRequired() {
            assertThatThrownBy(() -> MappingBudget.of(1_000, 0.7, 0, null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("names the rest of the process alongside the graphs")
        void saysWhatTheRestHolds() {
            MappingBudget budget = MappingBudget.of(1_000, 0.7, 100,
                new FixedProcess().holding(123, 7));
            budget.refreshProcessUsage();

            assertThat(budget.toString()).contains("restOfProcess=123 mappings 7 fds");
        }

        @Test
        @DisplayName("the detected budget has measured the process it is running in")
        void detectionMeasures() {
            MappingBudget budget = MappingBudget.detect();

            if (java.nio.file.Files.isReadable(java.nio.file.Path.of("/proc/self/maps"))) {
                assertThat(budget.otherMappings())
                    .as("a JVM maps its heap and libraries before any graph opens")
                    .isPositive();
            }
        }
    }

    /** A stream that hands back one byte per read, the way {@code /proc} was seen to. */
    private static InputStream oneByteAtATime(String content) {
        byte[] bytes = content.getBytes(StandardCharsets.US_ASCII);
        return new InputStream() {
            private int position;

            @Override
            public int read() {
                return position < bytes.length ? bytes[position++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (position >= bytes.length) {
                    return -1;
                }
                buffer[offset] = bytes[position++];
                return 1;
            }
        };
    }
}
