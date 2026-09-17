package io.excalibur.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for the foreign-memory warm-up.
 *
 * <p>The deadlock it exists to prevent needs a fresh JVM to observe, so these tests do not try
 * to reproduce it. They hold the properties the fix depends on: that the warm-up completes, that
 * it is safe to call from many threads at once, and that once it has run the foreign-memory
 * classes are usable from a thread that never called it.</p>
 */
class ForeignMemoryBootstrapTest {

    @Test
    @DisplayName("the warm-up completes")
    void warmUpCompletes() {
        assertThatCode(ForeignMemoryBootstrap::ensureInitialized).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("calling it again is free and still safe")
    void isIdempotent() {
        ForeignMemoryBootstrap.ensureInitialized();
        assertThatCode(() -> {
            for (int i = 0; i < 1_000; i++) {
                ForeignMemoryBootstrap.ensureInitialized();
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("concurrent first callers all return rather than one of them being left behind")
    void concurrentCallersAllReturn() throws InterruptedException {
        int threads = 32;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    ForeignMemoryBootstrap.ensureInitialized();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS))
            .as("every caller returned; a caller still waiting means initialization is wedged")
            .isTrue();
        assertThat(failure.get()).isNull();
    }

    @Test
    @DisplayName("afterwards the foreign-memory API works on a thread that never called it")
    void foreignMemoryIsUsableAfterwards() throws InterruptedException {
        ForeignMemoryBootstrap.ensureInitialized();

        AtomicReference<Long> readBack = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment segment = arena.allocate(Long.BYTES, Long.BYTES);
                segment.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, 0x0123456789ABCDEFL);
                readBack.set(segment.get(ValueLayout.JAVA_LONG_UNALIGNED, 0));
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        other.start();
        other.join(TimeUnit.SECONDS.toMillis(30));

        assertThat(other.isAlive()).as("the thread finished rather than blocking").isFalse();
        assertThat(failure.get()).isNull();
        assertThat(readBack.get()).isEqualTo(0x0123456789ABCDEFL);
    }

    @Test
    @DisplayName("the factory warms up before it takes its detection lock")
    void theFactoryWarmsUpBeforeLocking() throws java.io.IOException {
        // Reading the source is the only way to check this from inside a JVM where the classes
        // are long since initialized. The property that matters is an ordering - the warm-up
        // happens before the synchronized block, not inside it and not in a class initializer -
        // and no runtime observation can distinguish those once initialization has happened.
        // A test that cannot tell them apart is worse than none: it would pass with the fix
        // deleted, which is exactly what the assertion it replaced did.
        Path source = Path.of("src/main/java/io/excalibur/core/memory/MemorySegmentFactory.java");
        assertThat(source).as("the factory source is where the build runs it from").exists();
        String text = Files.readString(source);

        int warmUp = text.indexOf("ForeignMemoryBootstrap.ensureInitialized()");
        int lock = text.indexOf("synchronized (detectionLock)");
        assertThat(warmUp).as("the factory must warm foreign memory up at all").isNotNegative();
        assertThat(lock).as("detection is still guarded by detectionLock").isNotNegative();
        assertThat(warmUp)
            .as("the warm-up must run before the lock is taken; holding any lock across "
                + "java.lang.foreign class initialization is what piles callers up behind it")
            .isLessThan(lock);
        assertThat(text)
            .as("and not from a static initializer, which would hold this class's own "
                + "initialization monitor instead - the same pile-up, invisible to jstack")
            .doesNotContain("static {");
    }
}
