package com.eonmux.excalibur.ingest.processor;

import com.eonmux.excalibur.ingest.source.FetchedDocument;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * What the readers that convert PDFs - OCR in process or an external program - share: a bound
 * on how many conversions run at once, a deadline for each, and a memory of the outcomes the
 * same bytes would only meet again.
 *
 * <h2>How many at once</h2>
 * <p>At most {@code parallelism} conversions run at a time, across every source the node is
 * crawling, because an OCR engine spreads one document over every core it may use and two of
 * them at once only compete for the same cores.</p>
 *
 * <h2>What is not converted twice</h2>
 * <p>A document whose conversion ended in a way its bytes decide - it ran past its deadline,
 * wrote more text than a document may have, could not be opened, or came out with no text that
 * can be indexed - is remembered by its content hash, and is not converted again while its bytes
 * are unchanged. Converting it again would end the same way, and every re-crawl, hourly by
 * default, would spend the converter's time finding that out. A changed file, a changed reader
 * configuration or a restart tries it again. Any other failure is not remembered: it is the
 * converter's own, such as models it could not load, which the operator fixes and expects to be
 * retried.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe.</p>
 */
final class PdfConversions {

    /** Documents whose outcome is remembered, oldest forgotten first. */
    private static final int REMEMBERED_OUTCOMES = 16_384;

    private final Duration timeout;
    private final Semaphore slots;
    private final Map<ContentKey, Remembered> remembered = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ContentKey, Remembered> eldest) {
            return size() > REMEMBERED_OUTCOMES;
        }
    };

    /**
     * @param timeout     The longest one document may take
     * @param parallelism How many documents may be converted at once
     * @throws IllegalArgumentException if a bound is not positive
     */
    PdfConversions(Duration timeout, int parallelism) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive, got: " + timeout);
        }
        if (parallelism < 1) {
            throw new IllegalArgumentException("parallelism must be at least 1, got: "
                + parallelism);
        }
        this.timeout = timeout;
        this.slots = new Semaphore(parallelism, true);
    }

    /**
     * The longest one document may take.
     *
     * @return The timeout
     */
    Duration timeout() {
        return timeout;
    }

    /**
     * Converts one document once a slot is free, unless its bytes are known to end the same way.
     *
     * @param document   The PDF
     * @param conversion What converts it
     * @return The conversion's outcome, or the remembered one
     * @throws InterruptedException   if the thread is interrupted while waiting for a slot or
     *                                converting
     * @throws PdfConversionException if the conversion fails, now or the last time these bytes
     *                                were converted
     */
    DocumentDecoder.Decoded convert(FetchedDocument document, Conversion conversion)
            throws InterruptedException {
        Objects.requireNonNull(document, "document must not be null");
        Objects.requireNonNull(conversion, "conversion must not be null");
        ContentKey key = new ContentKey(document.contentHash(), document.getContentLength());
        Remembered before = recall(key);
        if (before != null) {
            return before.replay(document.getDocumentId());
        }
        slots.acquire();
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            DocumentDecoder.Decoded decoded = conversion.convert(document, deadline);
            if (!decoded.isText() || decoded.text().isEmpty()) {
                remember(key, Remembered.outcome(decoded));
            }
            return decoded;
        } catch (Unrepeatable e) {
            remember(key, Remembered.failure(e.getMessage()));
            throw new PdfConversionException(e.getMessage());
        } finally {
            slots.release();
        }
    }

    private Remembered recall(ContentKey key) {
        synchronized (remembered) {
            return remembered.get(key);
        }
    }

    private void remember(ContentKey key, Remembered outcome) {
        synchronized (remembered) {
            remembered.put(key, outcome);
        }
    }

    /** Converts one document by a deadline. */
    @FunctionalInterface
    interface Conversion {

        /**
         * @param document The PDF
         * @param deadline The {@link System#nanoTime()} by which it must be done
         * @return Its text, or the reason it has none that can be indexed
         * @throws InterruptedException   if the thread is interrupted
         * @throws Unrepeatable           if it fails in a way its bytes decide
         * @throws PdfConversionException if it fails in a way a later attempt might not
         */
        DocumentDecoder.Decoded convert(FetchedDocument document, long deadline)
            throws InterruptedException, Unrepeatable;
    }

    /** A failure the same bytes would meet again: remembered, then reported as a conversion's. */
    static final class Unrepeatable extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * @param message What failed, naming the document
         */
        Unrepeatable(String message) {
            super(message, null, false, false);
        }
    }

    /** A document's bytes, by the hash the registry records and their length. */
    private record ContentKey(long hash, int length) {
    }

    /** How converting one document's bytes ended, for a conversion that would end the same. */
    private record Remembered(String failure, DocumentDecoder.Decoded decoded) {

        static Remembered failure(String message) {
            return new Remembered(message, null);
        }

        static Remembered outcome(DocumentDecoder.Decoded decoded) {
            return new Remembered(null, decoded);
        }

        DocumentDecoder.Decoded replay(String documentId) {
            if (failure != null) {
                throw new PdfConversionException("Not converting " + documentId + " again: its "
                    + "bytes are those of a conversion that failed before, until they change or "
                    + "the node restarts. It failed with: " + failure);
            }
            return decoded;
        }
    }
}
