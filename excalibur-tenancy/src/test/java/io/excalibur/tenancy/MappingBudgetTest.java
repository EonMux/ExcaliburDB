package io.excalibur.tenancy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
