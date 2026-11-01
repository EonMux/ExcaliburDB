package com.eonmux.excalibur.ocr;

import com.eonmux.excalibur.ocr.model.ModelStore;
import com.eonmux.excalibur.ocr.model.ModelUnavailableException;
import com.eonmux.excalibur.ocr.pipeline.PageText;
import com.eonmux.excalibur.ocr.testing.Models;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Indra OCR, document by document")
class IndraOcrTest {

    private static final IndraOptions FOUR_THREADS =
        new IndraOptions(ReadMode.AUTO, 200, false, false, true, 4);

    @Test
    @DisplayName("joins the pages in order as Indra's command line does, text where no markdown")
    void joinsPages() throws Exception {
        FakePages pages = new FakePages((doc, i) -> i == 1
            ? new PageText(null, "plain " + i)
            : new PageText("# page " + i, "ignored"));
        try (IndraOcr ocr = ocr(pages, FOUR_THREADS)) {
            ConvertedDocument out = ocr.convert(pdf(3), Deadline.NONE);

            assertThat(out.markdown()).isEqualTo("# page 0\n\nplain 1\n\n# page 2\n");
            assertThat(out.pages()).isEqualTo(3);
            assertThat(out.failures()).isEmpty();
        }
    }

    @Test
    @DisplayName("reads pages in parallel, each worker from its own copy, all closed after")
    void parallelCopies() throws Exception {
        Set<PDDocument> all = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));
        Map<PDDocument, Thread> inUse = Collections.synchronizedMap(new IdentityHashMap<>());
        AtomicInteger shared = new AtomicInteger();
        CountDownLatch together = new CountDownLatch(2);
        FakePages pages = new FakePages((doc, i) -> {
            all.add(doc);
            if (inUse.putIfAbsent(doc, Thread.currentThread()) != null) {
                shared.incrementAndGet();
            }
            try {
                together.countDown();
                together.await();
                return new PageText("p" + i, "");
            } finally {
                inUse.remove(doc);
            }
        });
        try (IndraOcr ocr = ocr(pages, FOUR_THREADS)) {
            ConvertedDocument out = ocr.convert(pdf(12), Deadline.NONE);

            assertThat(out.markdown()).startsWith("p0\n\np1\n\n").endsWith("p11\n");
        }
        assertThat(all).hasSizeBetween(2, 4);
        assertThat(shared).as("a copy read by two threads at once").hasValue(0);
        all.forEach(doc -> assertThat(doc.getDocument().isClosed()).isTrue());
    }

    @Test
    @DisplayName("counts the pages in the page tree, not the count a file claims")
    void claimedPageCount() throws Exception {
        FakePages pages = new FakePages((doc, i) -> new PageText("p" + i, ""));
        try (IndraOcr ocr = ocr(pages, FOUR_THREADS)) {
            for (int claimed : new int[] {Integer.MAX_VALUE, 1_000_000, -5, 0}) {
                ConvertedDocument out = ocr.convert(claiming(2, claimed), Deadline.NONE);

                assertThat(out.pages()).as("claiming %d", claimed).isEqualTo(2);
                assertThat(out.markdown()).isEqualTo("p0\n\np1\n");
            }
        }
    }

    @Test
    @DisplayName("reads no more pages at once than its memory bound holds")
    void memoryBound() throws Exception {
        AtomicInteger now = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        FakePages pages = new FakePages((doc, i) -> {
            most.accumulateAndGet(now.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20);
                return new PageText("p", "");
            } finally {
                now.decrementAndGet();
            }
        });
        // A US letter page at 200 dpi renders at 1700 x 2200: two do not fit in 1.5 pages.
        long onePage = PageMemory.need(1700.0 * 2200.0);
        PageMemory memory = PageMemory.ofBytes(onePage * 3 / 2);
        try (IndraOcr ocr = IndraOcr.create(ModelStore.local(Models.dir()), FOUR_THREADS,
                (dir, o) -> pages, memory)) {
            assertThat(ocr.convert(pdf(8), Deadline.NONE).pages()).isEqualTo(8);
        }
        assertThat(most).hasValue(1);
        assertThat(memory.availableKib() * 1024L).isGreaterThanOrEqualTo(onePage * 3 / 2 - 1024);
    }

    @Test
    @DisplayName("gives up waiting for memory at its deadline")
    void memoryDeadline() throws Exception {
        PageMemory memory = PageMemory.ofBytes(1L << 20);
        FakePages pages = new FakePages((doc, i) -> new PageText("p", ""));
        try (IndraOcr ocr = IndraOcr.create(ModelStore.local(Models.dir()), FOUR_THREADS,
                (dir, o) -> pages, memory);
             PageMemory.Lease held = memory.acquire(1L << 20, Deadline.NONE)) {
            assertThatThrownBy(() -> ocr.convert(pdf(1), Deadline.after(Duration.ofMillis(100))))
                .isInstanceOf(ConversionTimeoutException.class);
            assertThat(pages.reads).hasValue(0);
        }
        assertThat(memory.availableKib()).isEqualTo(1024);
        assertThatThrownBy(() -> PageMemory.ofBytes(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("lets two documents take turns at the cores, page by page")
    void documentsTakeTurns() throws Exception {
        FakePages pages = new FakePages((doc, i) -> {
            Thread.sleep(40);
            return new PageText("p", "");
        });
        IndraOptions oneThread = new IndraOptions(ReadMode.AUTO, 200, false, false, true, 1);
        try (IndraOcr ocr = ocr(pages, oneThread)) {
            ocr.prepare();
            AtomicLong longDone = new AtomicLong();
            Thread longer = Thread.ofPlatform().start(() -> {
                try {
                    ocr.convert(pdf(30), Deadline.NONE);
                    longDone.set(System.nanoTime());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(100);
            ocr.convert(pdf(2), Deadline.NONE);
            long shortDone = System.nanoTime();
            longer.join(10_000);

            assertThat(longDone.get()).as("the long document finished").isPositive();
            assertThat(shortDone).as("the short one did not wait out the long one")
                .isLessThan(longDone.get());
        }
    }

    @Test
    @DisplayName("frees its models when closed while they load, without waiting for the load")
    void closedWhileLoading() throws Exception {
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch finishLoad = new CountDownLatch(1);
        FakePages pages = new FakePages((d, i) -> new PageText("p", ""));
        IndraOcr ocr = IndraOcr.create(ModelStore.local(Models.dir()), FOUR_THREADS,
            (dir, options) -> {
                loadStarted.countDown();
                try {
                    finishLoad.await();
                } catch (InterruptedException e) {
                    throw new InterruptedIOException("load interrupted");
                }
                return pages;
            });
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                ocr.convert(pdf(1), Deadline.NONE);
            } catch (Throwable t) {
                outcome.set(t);
            }
        });
        loadStarted.await();

        assertThat(ocr.close(Duration.ZERO)).isTrue();
        finishLoad.countDown();
        caller.join(5_000);

        assertThat(outcome.get()).isInstanceOf(InterruptedException.class);
        assertThat(pages.closed).isTrue();
        assertThatThrownBy(ocr::prepare).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("closed with no time left, still frees idle models")
    void idleCloseWithoutWait() throws Exception {
        FakePages pages = new FakePages((d, i) -> new PageText("p", ""));
        IndraOcr ocr = ocr(pages, FOUR_THREADS);
        ocr.convert(pdf(3), Deadline.NONE);

        assertThat(ocr.close(Duration.ZERO)).isTrue();
        assertThat(pages.closed).isTrue();
    }

    @Test
    @DisplayName("turns a failed page into an empty one and reads the rest")
    void pageFailure() throws Exception {
        FakePages pages = new FakePages((doc, i) -> {
            if (i == 1) {
                throw new IllegalStateException("bad glyph");
            }
            return new PageText("ok " + i, "");
        });
        try (IndraOcr ocr = ocr(pages, FOUR_THREADS)) {
            ConvertedDocument out = ocr.convert(pdf(3), Deadline.NONE);

            assertThat(out.markdown()).isEqualTo("ok 0\n\n\n\nok 2\n");
            assertThat(out.failures()).singleElement().satisfies(f -> {
                assertThat(f.page()).isEqualTo(2);
                assertThat(f.reason()).contains("IllegalStateException", "bad glyph");
            });
        }
    }

    @Test
    @DisplayName("gives up at its deadline, with every worker stopped when it returns")
    void deadline() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        FakePages pages = new FakePages((doc, i) -> {
            inFlight.incrementAndGet();
            try {
                Thread.sleep(50);
                return new PageText("p", "");
            } finally {
                inFlight.decrementAndGet();
            }
        });
        try (IndraOcr ocr = ocr(pages, FOUR_THREADS)) {
            assertThatThrownBy(() -> ocr.convert(pdf(400), Deadline.after(Duration.ofMillis(150))))
                .isInstanceOf(ConversionTimeoutException.class);
            assertThat(inFlight).hasValue(0);
            assertThat(pages.reads.get()).isLessThan(400);
        }
    }

    @Test
    @DisplayName("stops when its thread is interrupted")
    void interrupted() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        FakePages pages = new FakePages((doc, i) -> {
            started.countDown();
            Thread.sleep(10_000);
            return new PageText("p", "");
        });
        try (IndraOcr ocr = ocr(pages, FOUR_THREADS)) {
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread caller = Thread.ofPlatform().start(() -> {
                try {
                    ocr.convert(pdf(8), Deadline.NONE);
                } catch (Throwable t) {
                    outcome.set(t);
                }
            });
            started.await();
            caller.interrupt();
            caller.join(5_000);

            assertThat(caller.isAlive()).isFalse();
            assertThat(outcome.get()).isInstanceOf(InterruptedException.class);
        }
    }

    @Test
    @DisplayName("closed mid-conversion, ends it and frees its models once its pages stop")
    void closedWhileConverting() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        FakePages pages = new FakePages((doc, i) -> {
            started.countDown();
            Thread.sleep(10_000);
            return new PageText("p", "");
        });
        IndraOcr ocr = ocr(pages, new IndraOptions(ReadMode.AUTO, 200, false, false, true, 1));
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                ocr.convert(pdf(6), Deadline.NONE);
            } catch (Throwable t) {
                outcome.set(t);
            }
        });
        started.await();

        assertThat(ocr.close(Duration.ofSeconds(10))).isTrue();
        caller.join(5_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(outcome.get()).isInstanceOf(InterruptedException.class);
        assertThat(pages.closed).isTrue();
    }

    @Test
    @DisplayName("keeps its models loaded while a page it cannot stop is still being read")
    void closeLeavesBusyModels() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakePages pages = new FakePages((doc, i) -> {
            started.countDown();
            while (release.getCount() > 0) {
                Thread.onSpinWait();
            }
            return new PageText("p", "");
        });
        IndraOcr ocr = ocr(pages, new IndraOptions(ReadMode.AUTO, 200, false, false, true, 1));
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                ocr.convert(pdf(1), Deadline.NONE);
            } catch (Exception e) {
                // The outcome is not what this test is about.
            }
        });
        started.await();

        assertThat(ocr.close(Duration.ofMillis(100))).isFalse();
        assertThat(pages.closed).isFalse();
        release.countDown();
        caller.join(5_000);
        assertThat(ocr.close(Duration.ofSeconds(5))).isTrue();
        assertThat(pages.closed).isTrue();
    }

    @Test
    @DisplayName("refuses a document it cannot open, saying why")
    void unreadable() throws Exception {
        try (IndraOcr ocr = ocr(new FakePages((d, i) -> null), FOUR_THREADS)) {
            assertThatThrownBy(() -> ocr.convert(ByteBuffer.wrap(
                    "%PDF-1.7 not really".getBytes(StandardCharsets.US_ASCII)), Deadline.NONE))
                .isInstanceOf(UnreadablePdfException.class)
                .hasMessageContaining("cannot open the PDF");
            assertThatThrownBy(() -> ocr.convert(encrypted(), Deadline.NONE))
                .isInstanceOf(UnreadablePdfException.class)
                .hasMessageContaining("password");
        }
    }

    @Test
    @DisplayName("loads its models once, when prepared or at the first conversion, and reports "
        + "when it cannot")
    void models(@TempDir Path empty) throws Exception {
        AtomicInteger loads = new AtomicInteger();
        FakePages pages = new FakePages((d, i) -> new PageText("p", ""));
        try (IndraOcr ocr = IndraOcr.create(ModelStore.local(Models.dir()), FOUR_THREADS,
                (dir, options) -> {
                    loads.incrementAndGet();
                    return pages;
                })) {
            assertThat(loads).hasValue(0);
            ocr.prepare();
            assertThat(loads).hasValue(1);
            ocr.convert(pdf(1), Deadline.NONE);
            ocr.convert(pdf(2), Deadline.NONE);
            assertThat(loads).hasValue(1);
        }
        assertThat(pages.closed).isTrue();

        try (IndraOcr missing = IndraOcr.create(ModelStore.local(empty), FOUR_THREADS,
                (dir, options) -> pages)) {
            assertThatThrownBy(() -> missing.convert(pdf(1), Deadline.NONE))
                .isInstanceOf(ModelUnavailableException.class);
        }
        try (IndraOcr broken = IndraOcr.create(ModelStore.local(Models.dir()), FOUR_THREADS,
                (dir, options) -> {
                    throw new IOException("bad model");
                })) {
            assertThatThrownBy(() -> broken.convert(pdf(1), Deadline.NONE))
                .isInstanceOf(ModelUnavailableException.class).hasMessageContaining("bad model");
        }
    }

    @Test
    @DisplayName("names its version, model revision and every option that changes the text")
    void identity() {
        IndraOcr ocr = ocr(new FakePages((d, i) -> null),
            new IndraOptions(ReadMode.OCR, 300, true, true, false, 2));
        ocr.close();
        ocr.close();

        assertThat(ocr.identity()).isEqualTo("indra-ocr " + IndraOcr.VERSION + " models=v1.1.0 "
            + "mode=ocr dpi=300 math=on furniture=keep image-ocr=off");
        assertThat(ocr.options().threads()).isEqualTo(2);
        assertThatThrownBy(() -> ocr.convert(pdf(1), Deadline.NONE))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("takes Indra's options and defaults, refusing impossible ones")
    void options() {
        IndraOptions defaults = IndraOptions.defaults();

        assertThat(defaults.describe())
            .isEqualTo("mode=auto dpi=200 math=off furniture=drop image-ocr=on");
        assertThat(defaults.effectiveThreads()).isEqualTo(
            Runtime.getRuntime().availableProcessors());
        assertThat(new IndraOptions(ReadMode.TEXT, 150.5, false, false, true, 1).describe())
            .contains("mode=text", "dpi=150.5");
        assertThat(ReadMode.of(" Ocr ")).isEqualTo(ReadMode.OCR);
        assertThatThrownBy(() -> ReadMode.of("fast")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndraOptions(ReadMode.AUTO, 71, false, false, true, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IndraOptions(ReadMode.AUTO, 200, false, false, true, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("deadlines pass, or never do")
    void deadlines() throws Exception {
        assertThat(Deadline.NONE.passed()).isFalse();
        assertThat(Deadline.after(Duration.ofDays(365_000)).passed()).isFalse();
        Deadline past = Deadline.after(Duration.ZERO);
        Thread.sleep(1);
        assertThat(past.passed()).isTrue();
        assertThatThrownBy(past::check).isInstanceOf(ConversionTimeoutException.class);
        Thread.currentThread().interrupt();
        assertThatThrownBy(Deadline.NONE::check).isInstanceOf(InterruptedException.class);
    }

    private static IndraOcr ocr(FakePages pages, IndraOptions options) {
        return IndraOcr.create(ModelStore.local(Models.dir()), options, (dir, o) -> pages);
    }

    private static ByteBuffer pdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return ByteBuffer.wrap(out.toByteArray());
        }
    }

    /** A document of so many pages whose page tree claims another count. */
    private static ByteBuffer claiming(int pages, int claimed) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            document.getPages().getCOSObject().setInt(COSName.COUNT, claimed);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return ByteBuffer.wrap(out.toByteArray());
        }
    }

    private static ByteBuffer encrypted() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy =
                new StandardProtectionPolicy("owner", "user", new AccessPermission());
            document.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return ByteBuffer.wrap(out.toByteArray());
        }
    }

    @FunctionalInterface
    private interface PageFunction {
        PageText read(PDDocument document, int index) throws Exception;
    }

    private static final class FakePages implements PageConverter {

        private final PageFunction function;
        final AtomicInteger reads = new AtomicInteger();
        volatile boolean closed;

        FakePages(PageFunction function) {
            this.function = function;
        }

        @Override
        public PageText read(PDDocument document, int index, Deadline deadline) throws Exception {
            reads.incrementAndGet();
            deadline.check();
            return function.read(document, index);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
