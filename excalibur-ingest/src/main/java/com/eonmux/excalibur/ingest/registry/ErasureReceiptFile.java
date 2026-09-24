package com.eonmux.excalibur.ingest.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What each recent document erasure did, on disk, so its caller can come back and ask where it
 * stands.
 *
 * <h2>Why it exists</h2>
 * <p>{@code POST /v1/erasure} removes documents from the corpus before it answers, but the graph
 * half - the facts extracted from those documents - is a queued obligation per document in each
 * namespace's erasure log, and the lexical index reclaims the removed chunks at its next
 * compaction. A reply of 202 therefore promises work that finishes later, and a caller under an
 * obligation of its own has to be able to learn that it did. A receipt is the handle for that: it
 * records which graph obligations one request submitted and whether the index still owed a
 * reclamation, and the status route reads those obligations' state back from where they live.
 * The receipt holds no state of its own that could disagree with them.</p>
 *
 * <h2>What a receipt holds, and what it never does</h2>
 * <p>The source id the request named, its scope, the counts the request answered with, and the
 * graph request ids per namespace. Never a document id: the document ids of an erasure are the
 * erased content's names, and a file kept precisely so that an erasure can be accounted for must
 * not become the one place they survive it.</p>
 *
 * <h2>Retention</h2>
 * <p>The {@value #RETAINED_RECEIPTS} most recent receipts are kept; recording another drops the
 * oldest. A receipt that has been dropped answers as unknown. The bound keeps the file, which is
 * rewritten whole on every erasure, a size whose rewrite costs nothing worth measuring, and the
 * number is large enough that a caller polling its own erasure is done long before the receipt
 * goes.</p>
 *
 * <h2>Record format</h2>
 * <p>The catalog's conventions, so the tools that check a data directory read it the same way:
 * a first line {@code \#erasure-receipts|<version>}, one line per receipt,
 * {@code id|recorded_ms|source_id|scope|erased|incomplete|not_found|remaining|deregistered|lexical_owed|graph},
 * with the source id escaped as the catalog escapes, and a last line {@code \#end|<count>|<crc>}
 * - the count and the CRC32C of every byte before it (see {@link EndLine}). The graph field is
 * empty when nothing was submitted to a graph, and otherwise
 * {@code namespace=first+count,first+count;namespace=...}: the ids one request submits to a
 * namespace are nearly always consecutive, so ranges keep a thousand-document erasure to a few
 * bytes.</p>
 *
 * <h2>Reading an unreadable file</h2>
 * <p>As {@link SourceStatusFile} does: a file with no header, an unknown version, a record count
 * or checksum that disagrees with what is there, or one that cannot be read at all is logged and
 * ignored, and the receipts it held answer as unknown. Refusing to start over a report would take
 * a serving node down for the sake of its history, and the obligations themselves are in the
 * erasure logs, which a lost receipt does not touch. A record that does not parse costs that
 * receipt alone.</p>
 *
 * <h2>Durability</h2>
 * <p>Through the catalog's {@link SourceCatalog.CatalogWriter}: the whole file is written to a
 * sibling, forced, renamed over this one, and the directory forced after, so a crash leaves the
 * previous receipts or the new ones and never a mixture. A receipt is published to readers only
 * once its file is.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use; recording is serialised.</p>
 */
public final class ErasureReceiptFile {

    private static final Logger LOGGER = LoggerFactory.getLogger(ErasureReceiptFile.class);

    /** The receipt file, under the data directory. */
    public static final String FILE = "erasure-receipts";

    /**
     * The format this build writes, and the newest it reads.
     *
     * <p>Public for {@code excalibur-fsck}, which judges this file offline and must accept
     * exactly what this class accepts.</p>
     */
    public static final int FORMAT_VERSION = 1;

    /** The first version whose closing line carries a checksum: every one. */
    public static final int FIRST_CHECKSUMMED_VERSION = 1;

    /** The oldest format this build reads. */
    public static final int OLDEST_READABLE_VERSION = 1;

    /** Opens the first line, which names the format the rest of the file is in. */
    public static final String HEADER_MARKER = "\\#erasure-receipts";

    /** Opens the last line, which states the record count and the checksum. */
    public static final String END_MARKER = "\\#end";

    /** How many receipts are kept; see the class documentation. */
    public static final int RETAINED_RECEIPTS = 1024;

    private static final char FIELD_SEPARATOR = '|';
    private static final String REPLACEMENT_SUFFIX = ".tmp";
    private static final int FIELDS = 11;

    /**
     * The longest run of consecutive ids one range may stand for, so that a damaged range with
     * an intact checksum - a hand edit - cannot make a load allocate without bound. Far above
     * the documents one erasure request may name.
     */
    private static final int MAX_RANGE = 1_000_000;

    /** What a receipt id may be made of: a UUID, or anything as tame. */
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    /** What a namespace may be made of, so the graph field's separators cannot occur in one. */
    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    /** What a scope may be made of. */
    private static final Pattern SCOPE = Pattern.compile("[a-z]{1,32}");

    private final Path file;
    private final SourceCatalog.CatalogWriter writer;
    private final Object writeLock = new Object();

    /** Every retained receipt, oldest first. Replaced whole, only once its file is published. */
    private volatile Map<String, Receipt> receipts;

    /**
     * The graph obligations one erasure submitted to one namespace.
     *
     * @param namespace  The namespace whose erasure log handed the ids out
     * @param requestIds The ids, in the order they were submitted
     */
    public record GraphRequests(String namespace, List<Long> requestIds) {

        /**
         * @throws NullPointerException     if either is null
         * @throws IllegalArgumentException if the namespace holds characters this file cannot
         *                                  separate, or there are no ids, or one is not positive
         */
        public GraphRequests {
            Objects.requireNonNull(namespace, "namespace must not be null");
            if (!NAMESPACE.matcher(namespace).matches()) {
                throw new IllegalArgumentException("not a namespace this file can record: "
                    + namespace);
            }
            requestIds = List.copyOf(requestIds);
            if (requestIds.isEmpty()) {
                throw new IllegalArgumentException("a namespace is recorded only with its ids");
            }
            for (long id : requestIds) {
                if (id <= 0) {
                    throw new IllegalArgumentException("request ids are positive: " + id);
                }
            }
        }
    }

    /**
     * One document erasure, as it was answered.
     *
     * @param id                  The receipt id the caller was given
     * @param recordedAtMs        When the erasure answered, epoch milliseconds
     * @param sourceId            The source the request named
     * @param scope               {@code document} or {@code source}
     * @param documentsErased     Documents that left the corpus whole
     * @param documentsIncomplete Documents the node could not finish removing
     * @param documentsNotFound   Documents the node did not hold
     * @param documentsRemaining  Documents of a source-scoped erasure left for another request
     * @param sourceDeregistered  Whether the source was taken out of the crawl
     * @param lexicalOwed         Whether the lexical index still owed a reclamation of the
     *                            removed chunks when the request answered
     * @param graph               The graph obligations it submitted, per namespace
     */
    public record Receipt(String id, long recordedAtMs, String sourceId, String scope,
                          int documentsErased, int documentsIncomplete, int documentsNotFound,
                          int documentsRemaining, boolean sourceDeregistered, boolean lexicalOwed,
                          List<GraphRequests> graph) {

        /**
         * @throws NullPointerException     if a reference is null
         * @throws IllegalArgumentException if the id or scope is malformed, or a count or the
         *                                  time is negative
         */
        public Receipt {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(scope, "scope must not be null");
            if (!ID.matcher(id).matches()) {
                throw new IllegalArgumentException("not a receipt id: " + id);
            }
            if (!SCOPE.matcher(scope).matches()) {
                throw new IllegalArgumentException("not a scope: " + scope);
            }
            if (recordedAtMs < 0 || documentsErased < 0 || documentsIncomplete < 0
                    || documentsNotFound < 0 || documentsRemaining < 0) {
                throw new IllegalArgumentException("receipt counts must not be negative");
            }
            graph = List.copyOf(graph);
        }
    }

    /**
     * Opens the receipt file under a data directory, reading what a previous run left there.
     *
     * @param dataDirectory The node's data directory
     * @return The file
     * @throws NullPointerException if the directory is null
     */
    public static ErasureReceiptFile open(Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory must not be null");
        return new ErasureReceiptFile(dataDirectory.resolve(FILE),
            SourceCatalog.fileSystemWriter());
    }

    /**
     * Opens the file at an explicit path against an explicit set of disk operations.
     *
     * @param file   The receipt file, which need not exist yet
     * @param writer The operations that publish a rewrite
     */
    ErasureReceiptFile(Path file, SourceCatalog.CatalogWriter writer) {
        this.file = Objects.requireNonNull(file, "file must not be null");
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
        this.receipts = Files.exists(file) ? load() : Map.of();
    }

    /**
     * Finds a retained receipt.
     *
     * @param id The receipt id
     * @return The receipt, or empty when it was never recorded or has been dropped
     */
    public Optional<Receipt> find(String id) {
        return Optional.ofNullable(id == null ? null : receipts.get(id));
    }

    /**
     * How many receipts are retained.
     *
     * @return The count
     */
    public int size() {
        return receipts.size();
    }

    /**
     * Records a receipt, dropping the oldest once more than {@value #RETAINED_RECEIPTS} are held.
     *
     * @param receipt The receipt
     * @throws NullPointerException     if the receipt is null
     * @throws IllegalArgumentException if a retained receipt already has its id
     * @throws IOException              if the file cannot be written; the receipts held are
     *                                  unchanged, on disk and in memory
     */
    public void record(Receipt receipt) throws IOException {
        Objects.requireNonNull(receipt, "receipt must not be null");
        synchronized (writeLock) {
            if (receipts.containsKey(receipt.id())) {
                throw new IllegalArgumentException("receipt already recorded: " + receipt.id());
            }
            Map<String, Receipt> next = new LinkedHashMap<>(receipts);
            next.put(receipt.id(), receipt);
            while (next.size() > RETAINED_RECEIPTS) {
                next.remove(next.keySet().iterator().next());
            }
            write(List.copyOf(next.values()));
            receipts = Collections.unmodifiableMap(next);
        }
    }

    private void write(List<Receipt> snapshot) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path replacement = file.resolveSibling(file.getFileName() + REPLACEMENT_SUFFIX);
        try {
            try (ChecksummedTextWriter out = ChecksummedTextWriter.create(replacement)) {
                out.writeLine(HEADER_MARKER + FIELD_SEPARATOR + FORMAT_VERSION);
                for (Receipt receipt : snapshot) {
                    out.writeLine(format(receipt));
                }
                out.writeEnd(END_MARKER, snapshot.size());
            }
            writer.force(replacement);
            writer.publish(replacement, file);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(replacement);
            } catch (IOException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            throw e;
        }
        if (parent != null) {
            writer.forceDirectory(parent);
        }
    }

    private static String format(Receipt receipt) {
        return receipt.id() + FIELD_SEPARATOR
            + receipt.recordedAtMs() + FIELD_SEPARATOR
            + SourceCatalog.escape(receipt.sourceId()) + FIELD_SEPARATOR
            + receipt.scope() + FIELD_SEPARATOR
            + receipt.documentsErased() + FIELD_SEPARATOR
            + receipt.documentsIncomplete() + FIELD_SEPARATOR
            + receipt.documentsNotFound() + FIELD_SEPARATOR
            + receipt.documentsRemaining() + FIELD_SEPARATOR
            + (receipt.sourceDeregistered() ? 1 : 0) + FIELD_SEPARATOR
            + (receipt.lexicalOwed() ? 1 : 0) + FIELD_SEPARATOR
            + formatGraph(receipt.graph());
    }

    private static String formatGraph(List<GraphRequests> graph) {
        StringBuilder field = new StringBuilder();
        for (GraphRequests requests : graph) {
            if (!field.isEmpty()) {
                field.append(';');
            }
            field.append(requests.namespace()).append('=');
            List<Long> ids = requests.requestIds();
            int i = 0;
            while (i < ids.size()) {
                long first = ids.get(i);
                int count = 1;
                while (i + count < ids.size() && ids.get(i + count) == first + count) {
                    count++;
                }
                if (i > 0) {
                    field.append(',');
                }
                field.append(first).append('+').append(count);
                i += count;
            }
        }
        return field.toString();
    }

    private Map<String, Receipt> load() {
        ChecksummedLineReader.Lines read;
        try {
            read = ChecksummedLineReader.readAll(file);
        } catch (IOException e) {
            return ignored("it could not be read: " + e);
        }
        List<String> lines = read.lines();
        if (lines.isEmpty()) {
            return ignored("it is empty");
        }
        String headerPrefix = HEADER_MARKER + FIELD_SEPARATOR;
        String header = lines.get(0);
        if (!header.startsWith(headerPrefix)) {
            return ignored("its first line names no format: " + header);
        }
        int version;
        try {
            version = Integer.parseInt(header.substring(headerPrefix.length()));
        } catch (NumberFormatException e) {
            return ignored("its first line names no format version this build reads: " + header);
        }
        if (version < OLDEST_READABLE_VERSION || version > FORMAT_VERSION) {
            return ignored("it is in format version " + version + " and this build reads versions "
                + OLDEST_READABLE_VERSION + " to " + FORMAT_VERSION);
        }
        EndLine end = lines.size() < 2 ? null
            : EndLine.parse(lines.get(lines.size() - 1), END_MARKER, true);
        if (end == null) {
            return ignored("it does not end with a record count and checksum, so the write that "
                + "published it was cut short");
        }
        List<String> records = lines.subList(1, lines.size() - 1);
        if (end.count() != records.size()) {
            return ignored("it declares " + end.count() + " record(s) and carries "
                + records.size());
        }
        if (end.checksum() != read.checksumBeforeLast()) {
            return ignored(end.mismatch(read.checksumBeforeLast()));
        }
        Map<String, Receipt> parsed = new LinkedHashMap<>();
        int skipped = 0;
        for (String line : records) {
            Receipt receipt = parse(line);
            if (receipt == null || parsed.containsKey(receipt.id())) {
                skipped++;
            } else {
                parsed.put(receipt.id(), receipt);
            }
        }
        if (skipped > 0) {
            LOGGER.warn("Skipped {} unparseable record(s) in the erasure receipt file at {}; "
                + "those receipts answer as unknown", skipped, file);
        }
        return Collections.unmodifiableMap(parsed);
    }

    private Map<String, Receipt> ignored(String reason) {
        LOGGER.warn("Ignoring the erasure receipt file at {}: {}. The receipts it held answer as "
            + "unknown, the erasure obligations they pointed at are unaffected, and the file is "
            + "rewritten at the next erasure", file, reason);
        return Map.of();
    }

    /**
     * Parses one record.
     *
     * @param line The record line
     * @return The receipt, or null when the line is malformed
     */
    static Receipt parse(String line) {
        List<String> fields = SourceCatalog.splitEscaped(line, FIELD_SEPARATOR);
        if (fields.size() != FIELDS) {
            return null;
        }
        try {
            return new Receipt(fields.get(0), Long.parseLong(fields.get(1)),
                SourceCatalog.unescape(fields.get(2)), fields.get(3),
                Integer.parseInt(fields.get(4)), Integer.parseInt(fields.get(5)),
                Integer.parseInt(fields.get(6)), Integer.parseInt(fields.get(7)),
                flag(fields.get(8)), flag(fields.get(9)), parseGraph(fields.get(10)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean flag(String field) {
        return switch (field) {
            case "0" -> false;
            case "1" -> true;
            default -> throw new IllegalArgumentException("not a flag: " + field);
        };
    }

    private static List<GraphRequests> parseGraph(String field) {
        if (field.isEmpty()) {
            return List.of();
        }
        List<GraphRequests> graph = new ArrayList<>();
        for (String part : field.split(";", -1)) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("not a namespace's requests: " + part);
            }
            List<Long> ids = new ArrayList<>();
            for (String range : part.substring(equals + 1).split(",", -1)) {
                int plus = range.indexOf('+');
                if (plus <= 0) {
                    throw new IllegalArgumentException("not a range: " + range);
                }
                long first = Long.parseLong(range.substring(0, plus));
                int count = Integer.parseInt(range.substring(plus + 1));
                if (count <= 0 || count > MAX_RANGE) {
                    throw new IllegalArgumentException("not a range length: " + count);
                }
                for (int i = 0; i < count; i++) {
                    ids.add(first + i);
                }
            }
            graph.add(new GraphRequests(part.substring(0, equals), ids));
        }
        return graph;
    }
}
