package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.ingest.registry.DocumentRegistry;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.registry.SourceStatusFile;

/**
 * The line-oriented registries that end with a record count, and what their readers accept.
 *
 * <p>The three share one layout - a {@code <marker>|<version>} first line, one line per record,
 * an {@code \#end|<count>|<crc>} last line, or {@code \#end|<count>} in the versions written
 * before the checksum - and differ in what their readers do with a file that departs from it. That difference is the whole of what a checker has to know beyond the layout:
 * the verdict on a file has to be the reader's verdict, or the checker passes a file the node
 * will not load. The markers and the version range come from the classes that write and read
 * each file, and so does the first version whose last line carries a checksum, so a version
 * this build adds is accepted here the moment the reader accepts it; what is written down here
 * is only each reader's policy for the shapes that carry no version.</p>
 */
public enum CountMarkedFormat {

    /**
     * {@code document-registry}: which chunks each ingested document was stored as.
     *
     * <p>Read with no header at all, because the build before the header wrote none - and wrote
     * no count either, so there is nothing to check such a file against. An empty file is read
     * as a registry that holds nothing.</p>
     */
    DOCUMENT_REGISTRY(FileKind.DOCUMENT_REGISTRY, "document registry",
        DocumentRegistry.HEADER_MARKER, DocumentRegistry.END_MARKER,
        DocumentRegistry.OLDEST_READABLE_VERSION, DocumentRegistry.FORMAT_VERSION,
        DocumentRegistry.FIRST_CHECKSUMMED_VERSION, Headerless.READ_WITHOUT_COUNT, true,
        "the node refuses to start on it", true),

    /**
     * {@code source-catalog}: the registration behind every crawl the node keeps running.
     *
     * <p>Read with no header only if it still ends with a count, since the build that added
     * the count wrote no header yet; one with neither is refused, because that is also what a
     * cut-short file of that era looks like. An empty file is refused.</p>
     */
    SOURCE_CATALOG(FileKind.SOURCE_CATALOG, "source catalog",
        SourceCatalog.HEADER_MARKER, SourceCatalog.END_MARKER,
        SourceCatalog.OLDEST_READABLE_VERSION, SourceCatalog.FORMAT_VERSION,
        SourceCatalog.FIRST_CHECKSUMMED_VERSION, Headerless.READ_WITH_COUNT, false,
        "the node refuses to start on it", false),

    /**
     * {@code source-status}: how each registered source's last run ended.
     *
     * <p>Every file this build reads has a header, since the file is newer than the header
     * convention. Anything the reader will not take is set aside rather than refused, because it
     * is a report rather than state.</p>
     */
    SOURCE_STATUS(FileKind.SOURCE_STATUS, "source status history",
        SourceStatusFile.HEADER_MARKER, SourceStatusFile.END_MARKER,
        SourceStatusFile.OLDEST_READABLE_VERSION, SourceStatusFile.FORMAT_VERSION,
        SourceStatusFile.FIRST_CHECKSUMMED_VERSION, Headerless.REFUSED, false,
        "the node discards it, and each source reports from its next run", false);

    /** What a reader does with a file whose first line is not the format's header. */
    public enum Headerless {

        /** Reads every line as a record, with nothing to count them against. */
        READ_WITHOUT_COUNT,

        /** Reads the lines before the record count as records of the oldest version. */
        READ_WITH_COUNT,

        /** Will not read it. */
        REFUSED
    }

    private final FileKind kind;
    private final String displayName;
    private final String headerMarker;
    private final String endMarker;
    private final int oldestVersion;
    private final int newestVersion;
    private final int firstChecksummedVersion;
    private final Headerless headerless;
    private final boolean emptyReadable;
    private final String refusal;
    private final boolean rewrittenAtStart;

    CountMarkedFormat(FileKind kind, String displayName, String headerMarker, String endMarker,
                      int oldestVersion, int newestVersion, int firstChecksummedVersion,
                      Headerless headerless, boolean emptyReadable, String refusal,
                      boolean rewrittenAtStart) {
        this.kind = kind;
        this.displayName = displayName;
        this.headerMarker = headerMarker;
        this.endMarker = endMarker;
        this.oldestVersion = oldestVersion;
        this.newestVersion = newestVersion;
        this.firstChecksummedVersion = firstChecksummedVersion;
        this.headerless = headerless;
        this.emptyReadable = emptyReadable;
        this.refusal = refusal;
        this.rewrittenAtStart = rewrittenAtStart;
    }

    /**
     * Returns the kind of file this format is, which decides what losing one costs.
     *
     * @return The kind
     */
    public FileKind kind() {
        return kind;
    }

    /**
     * Returns the name the report gives this format.
     *
     * @return A human-readable name
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Returns what the first line of a versioned file starts with, before {@code |<version>}.
     *
     * @return The header marker
     */
    public String headerMarker() {
        return headerMarker;
    }

    /**
     * Returns what the last line starts with, before {@code |<count>}.
     *
     * @return The end marker
     */
    public String endMarker() {
        return endMarker;
    }

    /**
     * Returns the oldest version the reader accepts in a header.
     *
     * @return The oldest readable version
     */
    public int oldestVersion() {
        return oldestVersion;
    }

    /**
     * Returns the newest version the reader accepts in a header, the one this build writes.
     *
     * @return The newest readable version
     */
    public int newestVersion() {
        return newestVersion;
    }

    /**
     * Returns the first version whose last line carries the checksum of every byte before it.
     *
     * <p>A file at this version or later is proven or refused by that checksum; one before it
     * carries only its count, and is read with its records unverified.</p>
     *
     * @return The first checksummed version
     */
    public int firstChecksummedVersion() {
        return firstChecksummedVersion;
    }

    /**
     * Reports whether a file at a version carries a checksum on its last line.
     *
     * @param version The version its header declares, or the oldest version for a file with no
     *                header that its reader takes
     * @return true if the last line must carry a matching checksum
     */
    public boolean checksummed(int version) {
        return version >= firstChecksummedVersion;
    }

    /**
     * Says what the node does with a file of this format written before the checksum, beyond
     * reading it.
     *
     * @return A clause for a verdict's detail
     */
    public String upgrade() {
        return rewrittenAtStart
            ? "the node rewrites it in version " + newestVersion + ", with a checksum, as it starts"
            : "the node rewrites it in version " + newestVersion + ", with a checksum, at its next "
                + "save";
    }

    /**
     * Returns what the reader does with a file that has no header.
     *
     * @return The policy
     */
    public Headerless headerless() {
        return headerless;
    }

    /**
     * Reports whether the reader takes an empty file as a registry holding nothing.
     *
     * @return true if an empty file is read; false if it is refused or set aside
     */
    public boolean emptyReadable() {
        return emptyReadable;
    }

    /**
     * Says what the node does with a file of this format that its reader will not take.
     *
     * @return A clause for a verdict's detail
     */
    public String refusal() {
        return refusal;
    }
}
