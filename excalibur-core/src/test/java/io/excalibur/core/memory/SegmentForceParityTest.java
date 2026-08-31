package io.excalibur.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code force()} on a segment that has no file behind it.
 *
 * <p>{@code MemorySegment.force()} is defined only for mapped segments and throws for anything
 * else, while the ByteBuffer implementation checks and no-ops - so the two backends disagreed on
 * a method the interface defines for both. The same unguarded call inside {@code close()} then
 * landed in a catch-all that logs an error claiming the data might not be durable, on a segment
 * with nothing to flush and a null path.</p>
 */
@DisplayName("Segment force parity")
class SegmentForceParityTest {

    @Test
    @DisplayName("forcing an anonymous native segment is a no-op, not a failure")
    void forcingAnAnonymousSegmentIsANoOp() {
        Segment segment = MemorySegmentFactory.allocateNative(1024);
        try {
            assertThatCode(segment::force).doesNotThrowAnyException();
        } finally {
            segment.close();
        }
    }

    @Test
    @DisplayName("closing an anonymous native segment does not attempt an msync")
    void closingAnAnonymousSegmentIsClean() {
        Segment segment = MemorySegmentFactory.allocateNative(1024);

        assertThatCode(segment::close).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the fallback implementation agrees")
    void theFallbackAgrees() {
        Segment segment = MemorySegmentFactory.wrapBuffer(
            java.nio.ByteBuffer.allocateDirect(1024));
        try {
            assertThatCode(segment::force).doesNotThrowAnyException();
        } finally {
            segment.close();
        }
    }
}
