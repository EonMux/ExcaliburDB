// Ported from Indra OCR 1.1.2, document.py, parallel.py and cli.py (Apache License 2.0);
// see excalibur-ocr/NOTICE.
package com.eonmux.excalibur.ocr;

import com.eonmux.excalibur.ocr.model.IndraModels;
import com.eonmux.excalibur.ocr.model.ModelStore;
import com.eonmux.excalibur.ocr.model.ModelUnavailableException;
import com.eonmux.excalibur.ocr.pdf.DocumentLayer;
import com.eonmux.excalibur.ocr.pdf.PdfPage;
import com.eonmux.excalibur.ocr.pipeline.PageText;
import com.eonmux.excalibur.ocr.pipeline.PdfPages;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Indra OCR in process: PDFs in, markdown out, as {@code indra-ocr file.pdf} writes it.
 *
 * <h2>How a document is read</h2>
 * <p>Page by page, each read from its text layer where that can be trusted and by OCR elsewhere
 * (see {@link ReadMode}). Pages are read in parallel, as Indra's command line reads them on one
 * process per core: a pool of {@link IndraOptions#effectiveThreads()} threads, shared by every
 * conversion this instance runs. A conversion runs up to one worker per thread, each reading its
 * own copy of the document, which it opens and closes itself, as PDFBox documents are not
 * thread-safe; the bytes are shared. A worker reads one page at a time and then queues behind
 * the pool's other work, so two documents converted at once take turns at the cores, page by
 * page, rather than the second waiting out the first.</p>
 *
 * <p>The pages being read at once share a bound on the heap they may hold ({@link PageMemory}),
 * so large pages on many cores wait for one another rather than exhaust it.</p>
 *
 * <p>A page that fails is an error page, which adds nothing to the markdown, and the rest of the
 * document is read: one bad page does not end a book. A document that cannot be opened, or whose
 * page tree cannot be walked, fails as a whole ({@link UnreadablePdfException}). Its pages are
 * counted by walking the page tree rather than trusted from the count the file states, which a
 * damaged or hostile file can set to billions.</p>
 *
 * <h2>Models</h2>
 * <p>Loaded at the first conversion, or by {@link #prepare()}, not when this is built, so a node
 * that never sees a PDF never downloads them. A conversion that cannot get them fails, and the
 * next one tries again.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe.</p>
 */
public final class IndraOcr implements AutoCloseable {

    /**
     * This port's version: Indra's version, and the revision of the port. The revision rises
     * whenever a change to this module changes what it writes for some document.
     */
    public static final String VERSION = "1.1.2-java.1";

    private static final Logger LOGGER = LoggerFactory.getLogger(IndraOcr.class);

    /** How long {@link #close()} waits for pages being read: a few of the slowest steps. */
    private static final long CLOSE_WAIT_SECONDS = 30;

    /** A US letter page in points: the size assumed for a page whose own cannot be read. */
    private static final double LETTER_WIDTH = 612;
    private static final double LETTER_HEIGHT = 792;

    private final ModelStore store;
    private final IndraOptions options;
    private final EngineFactory factory;
    private final ExecutorService pool;
    private final PageMemory memory;
    /** Held while models are fetched and loaded, which can take minutes; never by close. */
    private final ReentrantLock loading = new ReentrantLock();
    /** Page steps under way, each using the models: they are freed only when none is. */
    private final AtomicInteger reading = new AtomicInteger();
    /** Conversions under way, ended by close. */
    private final Set<Work> works = ConcurrentHashMap.newKeySet();
    private volatile PageConverter pages;
    private volatile boolean closed;

    private IndraOcr(ModelStore store, IndraOptions options, EngineFactory factory,
                     PageMemory memory) {
        this.store = store;
        this.options = options;
        this.factory = factory;
        this.memory = memory;
        this.pool = Executors.newFixedThreadPool(options.effectiveThreads(), pageThreads());
    }

    /**
     * An OCR engine whose models are loaded from the store the first time a document is read.
     * Nothing is downloaded or loaded here.
     *
     * @param store   Where the models are, or are downloaded to
     * @param options How documents are read
     * @return The engine
     * @throws NullPointerException if an argument is null
     */
    public static IndraOcr create(ModelStore store, IndraOptions options) {
        return create(store, options, IndraOcr::loadPages);
    }

    static IndraOcr create(ModelStore store, IndraOptions options, EngineFactory factory) {
        return create(store, options, factory, PageMemory.forHeap());
    }

    static IndraOcr create(ModelStore store, IndraOptions options, EngineFactory factory,
                           PageMemory memory) {
        Objects.requireNonNull(store, "store must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(factory, "factory must not be null");
        Objects.requireNonNull(memory, "memory must not be null");
        return new IndraOcr(store, options, factory, memory);
    }

    /**
     * Names what a document's text depends on besides its bytes: this port's version, the model
     * revision, and every option that changes what is written.
     *
     * @return For example {@code indra-ocr 1.1.2-java.1 models=v1.1.0 mode=auto dpi=200 ...}
     */
    public String identity() {
        return "indra-ocr " + VERSION + " models=" + IndraModels.REVISION + " "
            + options.describe();
    }

    /**
     * The options documents are read with.
     *
     * @return The options
     */
    public IndraOptions options() {
        return options;
    }

    /**
     * Fetches and loads the models now, if they are not loaded yet: a caller that times its
     * conversions does this first, so a download is not counted against a document.
     *
     * @throws ModelUnavailableException if the models are not available
     * @throws InterruptedException       if the thread is interrupted, or the engine is closed
     *                                    while the models load
     * @throws IllegalStateException      if the engine is closed
     */
    public void prepare() throws ModelUnavailableException, InterruptedException {
        pages();
    }

    /**
     * Converts one PDF.
     *
     * @param pdf      The document, from its position to its limit; not modified
     * @param deadline When the conversion must be done by
     * @return Its markdown, and the pages that failed
     * @throws UnreadablePdfException     if the document cannot be opened
     * @throws ModelUnavailableException  if the models are not available
     * @throws ConversionTimeoutException if the deadline passes first
     * @throws InterruptedException       if the thread is interrupted, or the engine is closed
     * @throws IllegalStateException      if the engine is closed
     */
    public ConvertedDocument convert(ByteBuffer pdf, Deadline deadline)
            throws UnreadablePdfException, ModelUnavailableException,
            ConversionTimeoutException, InterruptedException {
        Objects.requireNonNull(pdf, "pdf must not be null");
        Objects.requireNonNull(deadline, "deadline must not be null");
        ByteBuffer bytes = pdf.slice();
        int count;
        try (PDDocument document = open(bytes)) {
            count = countPages(document);
        } catch (IOException e) {
            throw new UnreadablePdfException("Could not close a PDF: " + e.getMessage(), e);
        }
        PageConverter converter = pages();
        Work work = new Work(converter, bytes, deadline, count);
        enlist(work);
        try {
            if (closed) {
                throw closedWhileConverting();
            }
            int workers = Math.max(1, Math.min(options.effectiveThreads(), count));
            for (int i = 0; i < workers && !work.isFinished(); i++) {
                work.start();
            }
            return work.await();
        } catch (ConversionTimeoutException e) {
            throw work.shared ? new ConversionTimeoutException(e.getMessage(), true) : e;
        } finally {
            works.remove(work);
            work.stopAndAwait();
        }
    }

    /**
     * Adds a conversion to those under way, marking it and every other as shared when there is
     * more than one: from then on they take turns at the page readers.
     */
    private void enlist(Work work) {
        synchronized (works) {
            if (!works.isEmpty()) {
                work.shared = true;
                works.forEach(other -> other.shared = true);
            }
            works.add(work);
        }
    }

    /**
     * The pages a document has, by walking its page tree: the count the file states is only a
     * claim, and a page array is sized by it.
     */
    private static int countPages(PDDocument document) throws UnreadablePdfException {
        try {
            int n = 0;
            for (PDPage ignored : document.getPages()) {
                n++;
            }
            return n;
        } catch (RuntimeException | StackOverflowError e) {
            throw new UnreadablePdfException("cannot walk the PDF's page tree ("
                + e.getClass().getSimpleName() + ")", e);
        }
    }

    /** Reads one page, turning any failure of the page alone into its error. */
    private static PagePart readPage(PageConverter converter, PDDocument document, int index,
                                     Deadline deadline)
            throws ConversionTimeoutException, InterruptedException {
        try {
            return new PagePart(converter.read(document, index, deadline).part(), null);
        } catch (ConversionTimeoutException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("OCR stopped");
            }
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOGGER.warn("Indra OCR could not read page {}: {}", index + 1, reason);
            LOGGER.debug("Page {} failed", index + 1, e);
            return new PagePart("", reason);
        }
    }

    private static PDDocument open(ByteBuffer bytes)
            throws UnreadablePdfException, InterruptedException {
        try {
            return Loader.loadPDF(new RandomAccessReadBuffer(bytes.duplicate()));
        } catch (InvalidPasswordException e) {
            throw new UnreadablePdfException("password-protected PDF", e);
        } catch (InterruptedIOException e) {
            throw new InterruptedException("interrupted opening the PDF");
        } catch (IOException e) {
            throw new UnreadablePdfException("cannot open the PDF (" + e.getMessage() + ")", e);
        }
    }

    /**
     * The heap a page is expected to hold while it is read, from the size it renders at; a page
     * whose size cannot be read is counted as a letter page, and fails when it is read.
     */
    private long pageNeed(PDDocument document, int index) {
        double width;
        double height;
        try {
            PdfPage page = PdfPage.of(document, index);
            width = page.width();
            height = page.height();
        } catch (RuntimeException e) {
            width = LETTER_WIDTH;
            height = LETTER_HEIGHT;
        }
        double scale = DocumentLayer.pageScale(width, height, options.dpi());
        return PageMemory.need(width * scale * height * scale);
    }

    private static InterruptedException closedWhileConverting() {
        return new InterruptedException("Indra OCR was closed during the conversion");
    }

    private PageConverter pages() throws ModelUnavailableException, InterruptedException {
        PageConverter loaded = pages;
        if (loaded != null) {
            return loaded;
        }
        if (closed) {
            throw new IllegalStateException("this OCR engine is closed");
        }
        loading.lockInterruptibly();
        try {
            if (pages != null) {
                return pages;
            }
            if (closed) {
                throw new IllegalStateException("this OCR engine is closed");
            }
            Path dir = store.ensure(IndraModels.files(options.math()));
            try {
                loaded = factory.load(dir, options);
            } catch (IOException | RuntimeException | LinkageError e) {
                // A model's metadata that does not parse, or an ONNX Runtime whose native library
                // will not load on this platform, fails every conversion as surely as a missing
                // file; reported as one, it fails the document rather than the run reading it.
                throw new ModelUnavailableException("Could not load Indra OCR's models from "
                    + dir + ": " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            }
            synchronized (this) {
                if (closed) {
                    loaded.close();
                    throw closedWhileConverting();
                }
                pages = loaded;
            }
            LOGGER.info("Indra OCR {} loaded its models from {} ({})", VERSION, dir,
                options.describe());
            return loaded;
        } finally {
            loading.unlock();
        }
    }

    private static ThreadFactory pageThreads() {
        AtomicInteger n = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "indra-ocr-page-" + n.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Stops every conversion and releases the models, waiting up to a bound for pages being read.
     *
     * <p>A model run cannot be cut short, and freeing a model under one would crash the process
     * rather than fail the page; so the models are released only once no page is being read,
     * at the end of the step each reader is in. A conversion stopped this way fails with an
     * {@link InterruptedException}. A download or load under way is not waited for: it fails
     * when it returns, and frees what it loaded.</p>
     *
     * @param wait How long to wait for pages being read
     * @return true when the models were released, false when a page was still being read at the
     *         end of the wait and they were left loaded
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean close(Duration wait) throws InterruptedException {
        Objects.requireNonNull(wait, "wait must not be null");
        synchronized (this) {
            closed = true;
        }
        for (Work work : works) {
            work.fail(closedWhileConverting());
        }
        for (Runnable queued : pool.shutdownNow()) {
            if (queued instanceof Future<?> never) {
                never.cancel(false);
            }
        }
        // An idle pool's threads end at once, but not instantly: with no page being read the
        // models are free whether or not they have, so a zero wait does not misreport.
        if (!pool.awaitTermination(wait.toNanos(), TimeUnit.NANOSECONDS) && reading.get() > 0) {
            LOGGER.warn("Indra OCR's models are left loaded: a page was still being read {}s "
                + "after the engine was closed", wait.toSeconds());
            return false;
        }
        synchronized (this) {
            if (pages != null) {
                pages.close();
                pages = null;
            }
        }
        return true;
    }

    /** Closes the engine, waiting up to {@value #CLOSE_WAIT_SECONDS}s for pages being read. */
    @Override
    public void close() {
        try {
            close(Duration.ofSeconds(CLOSE_WAIT_SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static PageConverter loadPages(Path dir, IndraOptions options) throws IOException {
        PdfPages pages = PdfPages.load(dir, options);
        return new PageConverter() {
            @Override
            public PageText read(PDDocument document, int index, Deadline deadline)
                    throws Exception {
                return pages.read(document, index, deadline);
            }

            @Override
            public void close() {
                pages.close();
            }
        };
    }

    /** Loads the page reader from a model directory. */
    @FunctionalInterface
    interface EngineFactory {

        /**
         * @param modelDir A directory holding every model the options need, verified
         * @param options  How documents are read
         * @return The page reader
         * @throws IOException if a model cannot be loaded
         */
        PageConverter load(Path modelDir, IndraOptions options) throws IOException;
    }

    /** A page's part of the document, and why it failed when it did. */
    private record PagePart(String part, String failure) {
    }

    /**
     * One conversion: which page is read next, what each page came to, the workers' copies of
     * the document, and how it ended.
     */
    private final class Work {

        private final PageConverter converter;
        private final ByteBuffer bytes;
        private final Deadline deadline;
        private final AtomicInteger next = new AtomicInteger();
        private final PagePart[] parts;
        private final List<Worker> workers = new ArrayList<>();
        /** The threads running a step now, interrupted when the conversion stops. */
        private final Set<Thread> running = new HashSet<>();
        private int done;
        private int active;
        private boolean stopped;
        private Throwable failure;
        /** Whether another conversion was under way at some time while this one was. */
        private volatile boolean shared;

        Work(PageConverter converter, ByteBuffer bytes, Deadline deadline, int pages) {
            this.converter = converter;
            this.bytes = bytes;
            this.deadline = deadline;
            this.parts = new PagePart[pages];
        }

        /** Starts one more worker. */
        void start() {
            Worker worker = new Worker();
            synchronized (this) {
                workers.add(worker);
            }
            worker.schedule();
        }

        /** Waits until every page is read, the conversion fails, or its deadline passes. */
        ConvertedDocument await()
                throws UnreadablePdfException, ConversionTimeoutException, InterruptedException {
            synchronized (this) {
                while (failure == null && done < parts.length) {
                    if (deadline.nanoTime() == Long.MAX_VALUE) {
                        wait();
                    } else {
                        long left = deadline.nanoTime() - System.nanoTime();
                        if (left <= 0) {
                            throw new ConversionTimeoutException("ran past its deadline");
                        }
                        TimeUnit.NANOSECONDS.timedWait(this, left);
                    }
                }
                if (failure != null) {
                    rethrow(failure);
                }
            }
            return document();
        }

        synchronized boolean isFinished() {
            return failure != null || done >= parts.length;
        }

        /** Ends the conversion with a failure, unless it already ended. */
        synchronized void fail(Throwable t) {
            if (failure == null && done < parts.length) {
                failure = t;
            }
            notifyAll();
        }

        private synchronized void finished(int index, PagePart part) {
            parts[index] = part;
            done++;
            notifyAll();
        }

        private synchronized boolean enter() {
            if (stopped || failure != null) {
                return false;
            }
            active++;
            running.add(Thread.currentThread());
            return true;
        }

        private synchronized void exit() {
            running.remove(Thread.currentThread());
            active--;
            notifyAll();
        }

        /**
         * Lets no worker take another page, interrupts the steps under way, waits for them to
         * end, and closes the workers' copies of the document: a stopped conversion's work is
         * over when this returns. Not interruptible, as the wait is one step at most; an
         * interruption meanwhile is kept for the caller.
         */
        void stopAndAwait() {
            List<Worker> all;
            synchronized (this) {
                stopped = true;
                running.forEach(Thread::interrupt);
                boolean interrupted = false;
                while (active > 0) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                all = List.copyOf(workers);
            }
            all.forEach(Worker::closeDocument);
        }

        /** The pages' parts joined as Indra's command line joins them, and the failures. */
        private ConvertedDocument document() {
            List<String> text = new ArrayList<>(parts.length);
            List<ConvertedDocument.PageFailure> failures = new ArrayList<>();
            for (int i = 0; i < parts.length; i++) {
                text.add(parts[i].part());
                if (parts[i].failure() != null) {
                    failures.add(new ConvertedDocument.PageFailure(i + 1, parts[i].failure()));
                }
            }
            return new ConvertedDocument(String.join("\n\n", text) + "\n", parts.length,
                failures);
        }

        /** One worker: a page per step, each step queued behind the pool's other work. */
        private final class Worker {

            /** Its copy of the document; used by one step at a time, as steps follow steps. */
            private PDDocument document;

            void schedule() {
                try {
                    pool.execute(this::step);
                } catch (RejectedExecutionException e) {
                    fail(closedWhileConverting());
                }
            }

            private void step() {
                if (!enter()) {
                    return;
                }
                boolean more = false;
                reading.incrementAndGet();
                try {
                    if (closed) {
                        throw closedWhileConverting();
                    }
                    int index = next.getAndIncrement();
                    if (index < parts.length) {
                        deadline.check();
                        if (document == null) {
                            document = open(bytes);
                        }
                        try (PageMemory.Lease ignored = memory.acquire(pageNeed(document, index),
                                deadline)) {
                            finished(index, readPage(converter, document, index, deadline));
                        }
                        more = true;
                    }
                } catch (Throwable t) {
                    fail(t);
                } finally {
                    reading.decrementAndGet();
                    exit();
                }
                if (more) {
                    schedule();
                }
            }

            void closeDocument() {
                if (document != null) {
                    closeQuietly(document);
                    document = null;
                }
            }
        }
    }

    /** Throws a worker's failure as the conversion's: its own type where that is declared. */
    private static void rethrow(Throwable failure)
            throws UnreadablePdfException, ConversionTimeoutException, InterruptedException {
        switch (failure) {
            case ConversionTimeoutException timeout -> throw timeout;
            case InterruptedException interrupted -> throw interrupted;
            case UnreadablePdfException unreadable -> throw unreadable;
            case RuntimeException runtime -> throw runtime;
            case Error error -> throw error;
            default -> throw new IllegalStateException("A page worker failed", failure);
        }
    }

    private static void closeQuietly(PDDocument document) {
        try {
            document.close();
        } catch (IOException e) {
            LOGGER.debug("Could not close a page reader's copy of a PDF", e);
        }
    }
}
