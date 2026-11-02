package com.eonmux.excalibur.ingest.source;

/**
 * The largest single document this node indexes, in bytes and in chunks.
 *
 * <h2>Why one place</h2>
 * <p>The bound is enforced at three points - what a source reads, what a first crawl accepts, and
 * what a re-crawl accepts - and they have to agree. A source allowed to read past what ingestion
 * accepts reads a file whole into memory only to have it discarded and reported as a failure; a
 * re-crawl that accepts what the first crawl refused indexes, as "new", a document the first pass
 * said it could not take. Each point reads its bound from here, so they cannot drift apart.</p>
 *
 * <h2>Why these values</h2>
 * <p>A document is held several times over while it is processed - the fetched bytes, the
 * decoded text, one substring per chunk - and it is published only once every chunk has been
 * embedded and written, so a single document's size is a single request's worth of heap and a
 * single document's chunk count is the work it can schedule before anything is visible. The byte
 * bound keeps the first within reach of an ordinary heap; the chunk bound keeps the second from
 * turning one document into tens of thousands of embedding calls. At the default chunk size
 * (512 tokens, 64 overlapping) the chunk bound is the one a text document meets first, at about
 * 18 MB.</p>
 *
 * <h2>Why a PDF has a bound of its own</h2>
 * <p>A PDF's bytes are mostly its images and fonts, and its text is a small fraction of them: a
 * 1,700-page illustrated textbook runs to several hundred megabytes and holds a few megabytes of
 * text. Held to the text bound, every large book would be refused while yielding a document well
 * inside the chunk bound. So a PDF - recognised by its header, see {@link PdfSignature} - is
 * read up to {@link #MAX_PDF_BYTES}, and the chunk bound still governs what its text may cost.
 * Everything else keeps {@link #MAX_DOCUMENT_BYTES}, because for text the bytes are the text.</p>
 *
 * <h2>Why a PDF's bound follows the heap</h2>
 * <p>A PDF is read whole, and while it is converted the pages being read take up to half the
 * heap besides. A gigabyte-sized file on a node with a heap of a gigabyte or two would end the
 * process - the container image exits on running out of memory - and every crawl after the
 * restart would read it again. So a PDF is read only up to {@link #PDF_HEAP_SHARE} of the
 * maximum heap, within {@link #MAX_PDF_BYTES} and never below {@link #MAX_DOCUMENT_BYTES}; a
 * larger one fails, with a message that says more heap reads it, and the node stays up.</p>
 */
public final class DocumentLimits {

    /** The largest document other than a PDF, in bytes, that is read and indexed: 100 MiB. */
    public static final int MAX_DOCUMENT_BYTES = 100 * 1024 * 1024;

    /** The largest PDF, in bytes, that is read and indexed, on a heap that can hold it: 1 GiB. */
    public static final int MAX_PDF_BYTES = 1024 * 1024 * 1024;

    /** The share of the maximum heap one PDF's bytes may take. */
    public static final double PDF_HEAP_SHARE = 0.25;

    /** The most chunks one document may produce and still be indexed. */
    public static final int MAX_CHUNKS_PER_DOCUMENT = 10_000;

    /**
     * The largest a document of this kind may be.
     *
     * @param pdf Whether the document is a PDF
     * @return {@link #maxPdfBytes()} for a PDF, {@link #MAX_DOCUMENT_BYTES} for anything else
     */
    public static int maxBytes(boolean pdf) {
        return pdf ? maxPdfBytes() : MAX_DOCUMENT_BYTES;
    }

    /**
     * The largest PDF this process reads: {@link #PDF_HEAP_SHARE} of its maximum heap, within
     * {@link #MAX_PDF_BYTES} and never below {@link #MAX_DOCUMENT_BYTES}.
     *
     * @return The bound in bytes
     */
    public static int maxPdfBytes() {
        return maxPdfBytes(Runtime.getRuntime().maxMemory());
    }

    /**
     * The largest PDF a process with this maximum heap reads.
     *
     * @param maxHeap The maximum heap in bytes; {@link Long#MAX_VALUE} when it is unbounded
     * @return The bound in bytes
     */
    static int maxPdfBytes(long maxHeap) {
        long share = (long) (maxHeap * PDF_HEAP_SHARE);
        return (int) Math.max(MAX_DOCUMENT_BYTES, Math.min(MAX_PDF_BYTES, share));
    }

    /**
     * Why a PDF of this size is not read, naming the heap when it, not {@link #MAX_PDF_BYTES},
     * is what refuses it.
     *
     * @param bytes The PDF's size, larger than {@link #maxPdfBytes()}
     * @return The reason, for a refusal's message
     */
    public static String pdfTooLarge(long bytes) {
        return pdfTooLarge(bytes, maxPdfBytes());
    }

    /**
     * Why a PDF of this size is not read under a given bound.
     *
     * @param bytes The PDF's size
     * @param bound The largest PDF read, from {@link #maxPdfBytes(long)}
     * @return The reason
     */
    static String pdfTooLarge(long bytes, int bound) {
        String reason = bytes + " bytes (max: " + bound + " for a PDF";
        if (bound < MAX_PDF_BYTES) {
            reason += ", " + Math.round(PDF_HEAP_SHARE * 100) + "% of this node's maximum heap;"
                + " a larger heap (-Xmx) reads it";
        }
        return reason + ")";
    }

    /**
     * Whether a fetched document is larger than a document of its kind may be.
     *
     * @param document The document as its source handed it back
     * @return Whether it exceeds {@link #maxBytes(boolean)} for what its bytes say it is
     */
    public static boolean exceeds(FetchedDocument document) {
        int length = document.getContentLength();
        return length > MAX_DOCUMENT_BYTES
            && (length > maxPdfBytes() || !PdfSignature.isPdf(document.contentView()));
    }

    /**
     * Checks a source's read bound against what ingestion indexes.
     *
     * <p>Refused rather than lowered: a caller who asked for more than can be indexed is told so
     * when the source is built, not by a crawl whose large documents fail one at a time. The
     * bound is the largest document of any kind, a PDF; a document of another kind read past
     * {@link #MAX_DOCUMENT_BYTES} is still refused, by its source or by ingestion.</p>
     *
     * @param bytes   The bound a source was configured with
     * @param setting The setting's name, for the message
     * @return {@code bytes}, unchanged
     * @throws IllegalArgumentException if {@code bytes} exceeds {@link #MAX_PDF_BYTES}
     */
    static long requireIndexable(long bytes, String setting) {
        if (bytes > MAX_PDF_BYTES) {
            throw new IllegalArgumentException(setting + " " + bytes
                + " exceeds the largest document that can be indexed, " + MAX_PDF_BYTES
                + " bytes");
        }
        return bytes;
    }

    private DocumentLimits() {
        throw new AssertionError("Constants only");
    }
}
