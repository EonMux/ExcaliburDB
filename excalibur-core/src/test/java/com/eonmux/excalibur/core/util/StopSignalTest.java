package com.eonmux.excalibur.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StopSignal")
class StopSignalTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("an interrupt reaching a thread that writes a shared channel closes it for every thread")
    void interruptClosesSharedChannel() throws Exception {
        // The premise the signal exists for, pinned so it cannot quietly stop being true.
        try (FileChannel shared = FileChannel.open(directory.resolve("log"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
            AtomicReference<Throwable> writerFailure = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                Thread.currentThread().interrupt();
                try {
                    shared.write(ByteBuffer.wrap(new byte[] {1}), 0);
                } catch (IOException e) {
                    writerFailure.set(e);
                }
            });
            writer.start();
            writer.join();

            assertThat(writerFailure.get()).isInstanceOf(ClosedByInterruptException.class);
            // Another thread, never interrupted, can no longer use it.
            assertThatThrownBy(() -> shared.read(ByteBuffer.allocate(1), 0))
                .isInstanceOf(ClosedChannelException.class);
        }
    }

    @Test
    @DisplayName("a request outside a window leaves the thread's shared channel usable")
    void requestOutsideWindowDoesNotInterrupt() throws Exception {
        StopSignal stop = new StopSignal();
        try (FileChannel shared = FileChannel.open(directory.resolve("log"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
            CountDownLatch requested = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean sawStop = new AtomicBoolean();
            Thread worker = new Thread(() -> {
                try {
                    requested.await();
                    shared.write(ByteBuffer.wrap(new byte[] {7}), 0);
                    shared.force(true);
                    sawStop.set(stop.isRequested());
                } catch (Exception e) {
                    failure.set(e);
                }
            });
            worker.start();
            stop.request();
            requested.countDown();
            worker.join();

            assertThat(failure.get()).isNull();
            assertThat(sawStop).isTrue();
            assertThat(shared.isOpen()).isTrue();
            ByteBuffer read = ByteBuffer.allocate(1);
            shared.read(read, 0);
            assertThat(read.get(0)).isEqualTo((byte) 7);
        }
    }

    @Test
    @DisplayName("a request interrupts a thread blocked inside a window and leaves it clear")
    void requestInterruptsWindow() throws Exception {
        StopSignal stop = new StopSignal();
        CountDownLatch inside = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean interruptedAfter = new AtomicBoolean(true);
        Thread worker = new Thread(() -> {
            try {
                stop.interruptibly(() -> {
                    inside.countDown();
                    Thread.sleep(TimeUnit.MINUTES.toMillis(5));
                    return null;
                });
            } catch (InterruptedException e) {
                thrown.set(e);
            }
            interruptedAfter.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        stop.request();
        worker.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(worker.isAlive()).isFalse();
        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
        assertThat(interruptedAfter).isFalse();
        assertThat(stop.isRequested()).isTrue();
        assertThat(stop.getAsBoolean()).isTrue();
    }

    @Test
    @DisplayName("a window entered after the request is cut short at once")
    void windowAfterRequest() {
        StopSignal stop = new StopSignal();
        stop.request();
        stop.request();

        assertThatThrownBy(() -> stop.interruptibly(() -> {
            Thread.sleep(TimeUnit.MINUTES.toMillis(5));
            return null;
        })).isInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    @DisplayName("an interrupt from elsewhere absorbed by a window reads as a request")
    void foreignInterruptBecomesRequest() {
        StopSignal stop = new StopSignal();
        String result = stop.interruptibly(() -> {
            Thread.currentThread().interrupt();
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(stop.isRequested()).isTrue();
    }

    @Test
    @DisplayName("a window that ends normally leaves the signal unrequested")
    void quietWindow() {
        StopSignal stop = new StopSignal();
        assertThat(stop.interruptibly(() -> stop.interruptibly(() -> 42))).isEqualTo(42);
        assertThat(stop.isRequested()).isFalse();
        assertThat(stop).hasToString("StopSignal[running]");
    }

    @Test
    @DisplayName("a second thread cannot enter a window another thread holds")
    void oneThreadPerWindow() throws Exception {
        StopSignal stop = new StopSignal();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                stop.interruptibly(() -> {
                    inside.countDown();
                    release.await();
                    return null;
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> stop.interruptibly(() -> null))
                .isInstanceOf(IllegalStateException.class);
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    @DisplayName("pause runs out its time unless a stop is requested")
    void pause() throws Exception {
        StopSignal stop = new StopSignal();
        assertThat(stop.pause(1)).isFalse();
        assertThat(stop.pause(-5)).isFalse();

        CountDownLatch pausing = new CountDownLatch(1);
        AtomicBoolean woke = new AtomicBoolean();
        Thread sleeper = new Thread(() -> {
            pausing.countDown();
            try {
                woke.set(stop.pause(TimeUnit.MINUTES.toMillis(5)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        sleeper.start();
        assertThat(pausing.await(10, TimeUnit.SECONDS)).isTrue();
        stop.request();
        sleeper.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(sleeper.isAlive()).isFalse();
        assertThat(woke).isTrue();
        assertThat(stop.pause(TimeUnit.MINUTES.toMillis(5))).isTrue();
        assertThat(stop).hasToString("StopSignal[requested]");
    }
}
