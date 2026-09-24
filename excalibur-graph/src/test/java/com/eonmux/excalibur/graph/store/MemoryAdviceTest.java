package com.eonmux.excalibur.graph.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The page size {@code madvise} hints are aligned to, and what a bad override of it does.
 *
 * <p>The override is a JVM flag read once, while the class initialises, by every graph that maps
 * a column. A value the alignment arithmetic cannot use - zero divides by zero in the first
 * hint, and anything but a power of two rounds addresses to boundaries the kernel refuses - must
 * cost the node its hints at worst, never its graphs.</p>
 */
@DisplayName("Memory advice page size")
class MemoryAdviceTest {

    @Test
    @DisplayName("an unset override takes the platform's page size")
    void unset() {
        assertThat(MemoryAdvice.pageSize(null, 16384L)).isEqualTo(16384L);
        assertThat(MemoryAdvice.pageSize("  ", 16384L)).isEqualTo(16384L);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"4096", "16384", "65536", " 8192 "})
    @DisplayName("a positive power of two is used as given")
    void valid(String configured) {
        assertThat(MemoryAdvice.pageSize(configured, 4096L))
            .isEqualTo(Long.parseLong(configured.strip()));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"0", "-4096", "3000", "4097", "4k", "", "9223372036854775808"})
    @DisplayName("anything else falls back to the platform's page size")
    void invalid(String configured) {
        assertThat(MemoryAdvice.pageSize(configured, 16384L)).isEqualTo(16384L);
    }

    @Test
    @DisplayName("the platform's page size is a positive power of two")
    void platform() {
        long pageSize = MemoryAdvice.platformPageSize();

        assertThat(pageSize).isPositive();
        assertThat(Long.bitCount(pageSize)).isOne();
    }

    @Test
    @DisplayName("advising a slice that starts off a page boundary does not throw")
    void unalignedSlice() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(3 * 65536L, 65536L).asSlice(123, 65536L);

            // Whether the hint lands is the platform's business; that it is safe to ask is ours.
            assertThatCode(() -> {
                MemoryAdvice.adviseRandom(segment);
                MemoryAdvice.adviseSequential(segment);
                MemoryAdvice.adviseWillNeed(segment);
                MemoryAdvice.adviseNormal(segment);
            }).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("nothing to advise is not advised")
    void nothingToAdvise() {
        assertThat(MemoryAdvice.adviseRandom(null)).isFalse();
        assertThat(MemoryAdvice.adviseRandom(MemorySegment.NULL)).isFalse();
    }
}
