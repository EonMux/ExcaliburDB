package com.eonmux.excalibur.ingest.processor;

import com.eonmux.excalibur.ingest.source.DocumentLimits;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Converts a PDF to text by running an external program over it.
 *
 * <h2>Why an external program</h2>
 * <p>Reading a scanned page means OCR, and the engines that do it well are models with their own
 * runtimes. Run as a separate process, the engine is chosen and upgraded by the operator, a
 * crash in it fails one document instead of the node, and its threads are its own. The contract
 * is: given the path of a PDF as its last argument, it writes the document's text to standard
 * output and exits zero. {@code indra-ocr} keeps it, writing markdown in reading order with page
 * furniture removed and scanned pages OCRed. A converter that wants its arguments in another
 * order, such as {@code pdftotext}, which takes its output file after its input, is run through
 * a one-line script that puts them there.</p>
 *
 * <h2>How one runs</h2>
 * <p>The document is written to a temporary file, readable only by this process's user, because
 * the bytes converted must be the bytes the registry hashes: a source's file can change between
 * the fetch and the conversion. It goes to {@code java.io.tmpdir}. The program then runs with no
 * input and without the node's {@code EXCALIBUR_*} environment variables, which carry its
 * secrets, since it reads documents the node did not write. Its output is read as UTF-8 up to the
 * largest text a document may have, and what it writes to standard error is kept for the failure
 * message. The temporary file is deleted whatever happens. The processes the program starts are
 * looked for every tenth of a second while it runs, and any of them still running once it exits
 * are killed; one started and left behind within the same tenth of a second can escape that.</p>
 *
 * <p>A conversion that runs past its timeout, or whose run is stopped, is killed together with
 * every process it started, and the document fails; so does one that exits non-zero. A failed
 * document is not registered, so the next crawl tries it again.</p>
 *
 * <h2>What it shares</h2>
 * <p>The bound on concurrent conversions, the timeout and the memory of outcomes the same bytes
 * would repeat are those every converting reader has (see {@link PdfConversions}). For this one
 * a non-zero exit is not remembered: it is how a converter reports a problem of its own, such as
 * models it could not load, which the operator fixes and expects to be retried.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe; the bound on concurrent conversions is shared by every caller.</p>
 */
public final class PdfConverter implements PdfReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfConverter.class);

    /** How much of the program's standard error is kept for a failure's message. */
    private static final int STDERR_TAIL_BYTES = 4096;

    /** How long the output readers are given to finish once the program has exited. */
    private static final Duration READER_GRACE = Duration.ofSeconds(10);

    /** How often a running program is looked at for the processes it started. */
    private static final Duration DESCENDANT_POLL = Duration.ofMillis(100);

    /** The prefix of the node's own settings, kept from the program's environment. */
    private static final String NODE_ENVIRONMENT_PREFIX = "EXCALIBUR_";

    private final Path executable;
    private final List<String> args;
    private final PdfConversions conversions;
    private final String identity;

    private PdfConverter(Path executable, List<String> args, PdfConversions conversions) {
        this.executable = executable;
        this.args = List.copyOf(args);
        this.conversions = conversions;
        List<String> named = new ArrayList<>();
        named.add(String.valueOf(executable.getFileName()));
        named.addAll(this.args);
        this.identity = String.join(" ", named);
    }

    /**
     * Builds a converter, finding its program now rather than at the first PDF.
     *
     * <p>A command with a slash in it is a path; one without is looked up on the {@code PATH}, as
     * a shell would. A program that cannot be found is refused here, at startup, rather than
     * discovered by a crawl in which every PDF fails.</p>
     *
     * @param command     The program, a name on the {@code PATH} or a path
     * @param args        Arguments placed before the PDF's path
     * @param timeout     The longest one document may take
     * @param parallelism How many documents may be converted at once
     * @return The converter
     * @throws IllegalArgumentException if the program cannot be found or is not executable, or a
     *                                  bound is not positive
     * @throws NullPointerException     if any argument is null
     */
    public static PdfConverter of(String command, List<String> args, Duration timeout,
                                  int parallelism) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(args, "args must not be null");
        PdfConversions conversions = new PdfConversions(timeout, parallelism);
        return new PdfConverter(locate(command), args, conversions);
    }

    /**
     * Names what a document's text depends on besides its bytes: the program and its arguments.
     *
     * <p>The program by name rather than by path, so moving it does not have every PDF read again,
     * while changing it, or how it is asked to convert, does.</p>
     *
     * @return The program's file name followed by its arguments
     */
    @Override
    public String identity() {
        return identity;
    }

    /**
     * Converts one PDF.
     *
     * @param document The PDF
     * @return The converted text, or the reason there is none that can be indexed
     * @throws InterruptedException  if the thread is interrupted while waiting for a slot, staging
     *                               the document or waiting for the program, which is then killed
     * @throws PdfConversionException if the program fails, times out, or writes more text than a
     *                               document may have, now or the last time these bytes were
     *                               converted
     */
    @Override
    public DocumentDecoder.Decoded read(FetchedDocument document) throws InterruptedException {
        return conversions.convert(document, this::convert);
    }

    private DocumentDecoder.Decoded convert(FetchedDocument document, long deadline)
            throws InterruptedException, PdfConversions.Unrepeatable {
        String documentId = document.getDocumentId();
        Path input = writeTemporary(document.contentView(), documentId);
        try {
            return run(input, documentId, deadline);
        } finally {
            deleteQuietly(input);
        }
    }

    /** Runs the program over a file and reads what it wrote. */
    private DocumentDecoder.Decoded run(Path input, String documentId, long deadline)
            throws InterruptedException, PdfConversions.Unrepeatable {
        List<String> command = new ArrayList<>(args.size() + 2);
        command.add(executable.toString());
        command.addAll(args);
        command.add(input.toString());

        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().keySet().removeIf(name -> name.startsWith(NODE_ENVIRONMENT_PREFIX));
            process = builder.start();
        } catch (IOException e) {
            throw new PdfConversionException("Could not start " + executable + " for "
                + documentId + ": " + e.getMessage(), e);
        }
        // The program is given nothing on standard input; closed, a read of it ends at once
        // rather than waiting on this process.
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            killTree(process, Set.of());
            throw new PdfConversionException("Could not close " + executable.getFileName()
                + "'s input for " + documentId + ": " + e.getMessage(), e);
        }

        AtomicReference<byte[]> stdout = new AtomicReference<>();
        AtomicReference<IOException> stdoutFailure = new AtomicReference<>();
        Thread outReader = Thread.ofVirtual().name("pdf-converter-stdout").start(() -> {
            try (InputStream out = process.getInputStream()) {
                // One byte past the bound tells output at the limit from output over it.
                stdout.set(out.readNBytes(DocumentLimits.MAX_DOCUMENT_BYTES + 1));
                out.transferTo(OutputStream.nullOutputStream());
            } catch (IOException e) {
                stdoutFailure.set(e);
            }
        });
        StderrTail stderr = new StderrTail();
        Thread errReader = Thread.ofVirtual().name("pdf-converter-stderr")
            .start(() -> stderr.drain(process.getErrorStream()));

        Set<ProcessHandle> started = new HashSet<>();
        boolean exited;
        try {
            exited = awaitExit(process, started, deadline);
        } catch (InterruptedException e) {
            killTree(process, started);
            throw e;
        }
        if (!exited) {
            killTree(process, started);
            errReader.join(READER_GRACE);
            throw new PdfConversions.Unrepeatable(executable.getFileName() + " did not finish "
                + documentId + " within " + conversions.timeout().toSeconds() + "s; raise "
                + "excalibur.ingest.pdf-converter-timeout-seconds if it is that large"
                + stderr.describe());
        }
        // A program that exits leaving a child behind holding its output open would keep the
        // readers waiting; nothing it started has any work left once it has answered.
        killTree(process, started);
        outReader.join(READER_GRACE);
        errReader.join(READER_GRACE);

        int exit = process.exitValue();
        if (exit != 0) {
            throw new PdfConversionException(executable.getFileName() + " exited " + exit
                + " converting " + documentId + stderr.describe());
        }
        if (stdoutFailure.get() != null) {
            throw new PdfConversionException("Could not read " + executable.getFileName()
                + "'s output for " + documentId + ": " + stdoutFailure.get().getMessage(),
                stdoutFailure.get());
        }
        byte[] output = stdout.get();
        if (output == null) {
            throw new PdfConversionException(executable.getFileName() + "'s output for "
                + documentId + " was still being read " + READER_GRACE.toSeconds()
                + "s after it exited");
        }
        if (output.length > DocumentLimits.MAX_DOCUMENT_BYTES) {
            throw new PdfConversions.Unrepeatable(executable.getFileName() + " wrote more than "
                + DocumentLimits.MAX_DOCUMENT_BYTES + " bytes of text for " + documentId
                + stderr.describe());
        }

        String text = PdfText.withoutControlCharacters(
            new String(output, StandardCharsets.UTF_8)).strip();
        if (text.isEmpty()) {
            return DocumentDecoder.Decoded.text(text);
        }
        if (!TextContent.isText(text)) {
            return DocumentDecoder.Decoded.refused(executable.getFileName()
                + " wrote output for it that is not UTF-8 text");
        }
        return DocumentDecoder.Decoded.text(text);
    }

    /** Writes the document where the program can read it, readable by this user alone. */
    private static Path writeTemporary(ByteBuffer content, String documentId)
            throws InterruptedException {
        Path file = null;
        try {
            // createTempFile gives the file owner-only permissions on a POSIX file system.
            file = Files.createTempFile("excalibur-pdf-", ".pdf");
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                ByteBuffer remaining = content.slice();
                while (remaining.hasRemaining()) {
                    channel.write(remaining);
                }
            }
            return file;
        } catch (ClosedByInterruptException e) {
            // A stop arrived while the copy was written: the run is ending, not the disk failing.
            if (file != null) {
                deleteQuietly(file);
            }
            Thread.interrupted();
            InterruptedException stopped = new InterruptedException("Stopped while staging "
                + documentId + " for conversion");
            stopped.initCause(e);
            throw stopped;
        } catch (IOException e) {
            if (file != null) {
                deleteQuietly(file);
            }
            throw new PdfConversionException("Could not stage " + documentId
                + " for conversion in " + System.getProperty("java.io.tmpdir") + ": "
                + e.getMessage(), e);
        }
    }

    /**
     * Waits for the program, noting every process it starts along the way.
     *
     * <p>Once a program exits, the processes it left running are adopted by another and are no
     * longer its descendants, so they can only be found by having been seen while it ran.</p>
     *
     * @param process The program
     * @param started  Filled with every descendant seen
     * @param deadline The {@link System#nanoTime()} past which it is given up on
     * @return Whether it exited by the deadline
     */
    private static boolean awaitExit(Process process, Set<ProcessHandle> started, long deadline)
            throws InterruptedException {
        while (true) {
            process.descendants().forEach(started::add);
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            if (process.waitFor(Math.min(left, DESCENDANT_POLL.toNanos()), TimeUnit.NANOSECONDS)) {
                return true;
            }
        }
    }

    /**
     * Kills a process and everything it started, children first so none is orphaned.
     *
     * @param process The program
     * @param started Descendants seen while it ran, some of which may since have been adopted
     */
    private static void killTree(Process process, Set<ProcessHandle> started) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        for (ProcessHandle seen : started) {
            seen.descendants().forEach(ProcessHandle::destroyForcibly);
            seen.destroyForcibly();
        }
        process.destroyForcibly();
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // A temporary file that outlives its document is a leak of disk, not of data the
            // node serves; failing the document over it would throw the conversion away.
            LOGGER.warn("Could not delete the temporary copy {}: {}", file, e.getMessage());
        }
    }

    /**
     * Finds the program a command names.
     *
     * @param command A path, or a name to look up on the {@code PATH}
     * @return The program's absolute path
     * @throws IllegalArgumentException if there is no such executable
     */
    static Path locate(String command) {
        String name = command.strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("excalibur.ingest.pdf-converter-command is empty");
        }
        if (name.contains("/") || name.contains(File.separator)) {
            Path path = Path.of(name).toAbsolutePath();
            if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                return path;
            }
            throw new IllegalArgumentException("excalibur.ingest.pdf-converter-command names "
                + path + ", which is not an executable file");
        }
        String searchPath = System.getenv("PATH");
        if (searchPath != null) {
            for (String directory : searchPath.split(File.pathSeparator)) {
                if (directory.isEmpty()) {
                    continue;
                }
                Path candidate = Path.of(directory, name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath();
                }
            }
        }
        throw new IllegalArgumentException("excalibur.ingest.pdf-converter-command names "
            + name + ", which is not an executable on this node's PATH; give its full path");
    }

    /** Keeps the last few kilobytes a program wrote to standard error. */
    private static final class StderrTail {

        private final ByteArrayOutputStream tail = new ByteArrayOutputStream();

        void drain(InputStream err) {
            byte[] buffer = new byte[8192];
            try (err) {
                int read;
                while ((read = err.read(buffer)) != -1) {
                    append(buffer, read);
                }
            } catch (IOException e) {
                // The process is gone or killed; what was kept so far is what there is.
            }
        }

        private synchronized void append(byte[] buffer, int read) {
            tail.write(buffer, 0, read);
            if (tail.size() > 2 * STDERR_TAIL_BYTES) {
                byte[] all = tail.toByteArray();
                tail.reset();
                tail.write(all, all.length - STDERR_TAIL_BYTES, STDERR_TAIL_BYTES);
            }
        }

        synchronized String describe() {
            String text = tail.toString(StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                return "";
            }
            if (text.length() > STDERR_TAIL_BYTES) {
                text = text.substring(text.length() - STDERR_TAIL_BYTES);
            }
            return ": " + text;
        }
    }
}
