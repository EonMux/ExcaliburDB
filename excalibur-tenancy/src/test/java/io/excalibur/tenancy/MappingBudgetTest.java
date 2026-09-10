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
            assertThat(budget.wouldExceedHighWater(1)).isFalse();
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
