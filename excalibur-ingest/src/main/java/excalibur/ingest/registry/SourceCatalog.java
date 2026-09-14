package excalibur.ingest.registry;

import excalibur.ingest.source.SourceDescriptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sources this node has been asked to crawl, on disk.
 *
 * <h2>Why it is on disk</h2>
 * <p>Registering a source is the one ingest operation whose effect is meant to outlast the
 * request: the source is crawled once and then re-crawled on a schedule for as long as it stays
 * registered. Held only in memory that promise ends at the next restart, and it ends without a
 * symptom - the corpus is still indexed and still queryable, {@code /v1/ingest/status} simply
 * reports nothing and no re-crawl ever runs again.</p>
 *
 * <p>So the catalog lives beside the {@link DocumentRegistry} under the data directory. It holds
 * {@link SourceDescriptor}s, not sources: what a restart has to rebuild is the registration, and
 * the crawler is built from it the same way the original request built one.</p>
 *
 * <h2>Record format</h2>
 * <p>One line per source, {@code type|id|key=value|key=value}, with {@code \}, {@code |},
 * {@code =}, and the line terminators escaped. Roots and URLs are caller-supplied text and
 * contain all of those, so escaping is not optional.</p>
 *
 * <p>A last line, {@code \#end|count}, closes the file. Without it a catalog that lost its tail
 * is a catalog of whole, well-formed records that simply omits sources - which reads exactly like
 * a catalog those sources were never registered in, and is the one failure a registry of promises
 * must not be able to make quietly. The marker cannot collide with a record: {@link
 * #escape(String)} doubles every backslash, so no formatted record begins with a single one.</p>
 *
 * <h2>Durability</h2>
 * <p>Every mutation rewrites the whole file into a sibling, forces it, and renames it over the
 * catalog; the directory is forced afterwards, because a rename is a directory change and is no
 * more durable on its own than the contents were. Renaming an unforced replacement publishes a
 * correctly named catalog whose contents may still be only in the page cache - the same loss the
 * rename exists to prevent, reached from the other end. The file holds at most a few hundred
 * short records, so a rewrite costs nothing worth optimising, and a caller that has been told its
 * source is registered must not be able to lose it to a crash a moment later.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe. Reads are lock-free; a mutation holds a lock for the rewrite, so concurrent
 * registrations serialise on the file rather than racing to replace it.</p>
 */
public final class SourceCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger(SourceCatalog.class);

    /** The catalog file, under the data directory. */
    public static final String FILE = "source-catalog";

    /** Separates the fields of one record. */
    private static final char FIELD_SEPARATOR = '|';

    /** Separates a configuration key from its value. */
    private static final char PAIR_SEPARATOR = '=';

    /** Introduces an escape sequence. */
    private static final char ESCAPE = '\\';

    /** Fields before the configuration pairs: the type and the id. */
    private static final int FIXED_FIELDS = 2;

    /** Suffix of the sibling a rewrite is assembled in before it is published. */
    private static final String REPLACEMENT_SUFFIX = ".tmp";

    /** Opens the last line, which states how many records precede it. */
    private static final String END_MARKER = "\\#end";

    private final Path file;
    private final CatalogWriter writer;
    private final Map<String, SourceDescriptor> sources = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    /**
     * The disk operations that publish a rewritten catalog.
     *
     * <p>Durability here is an ordering rather than any single call: the replacement's contents
     * have to reach the platter before the rename that names it the catalog, and the rename
     * itself is a directory change that is no more durable than the contents were. A test cannot
     * cut the power between two of those steps, so the ordering is pinned by recording the steps
     * as they are taken; that is what this seam exists for, and it is why the three operations
     * are named separately rather than being one {@code publish} call.</p>
     */
    interface CatalogWriter {

        /**
         * Forces a written file's contents to the underlying device.
         *
         * @param path The file to force
         * @throws IOException if the contents cannot be made durable
         */
        void force(Path path) throws IOException;

        /**
         * Renames a finished replacement over the live catalog.
         *
         * @param replacement The file holding the new contents
         * @param target      The catalog path
         * @throws IOException if the replacement cannot be published
         */
        void publish(Path replacement, Path target) throws IOException;

        /**
         * Forces the directory entry a rename has just changed.
         *
         * @param directory The directory holding the catalog
         */
        void forceDirectory(Path directory);
    }

    /**
     * Thrown when a catalog exists but cannot be read.
     *
     * <p>An unreadable catalog is not an empty one. Starting empty would leave the node running
     * with no registered sources, which is indistinguishable from a node nobody has registered
     * anything on: the re-crawls stop, the status endpoint reports nothing, and the next
     * registration overwrites what could not be read. Failing here is the only way the operator
     * can tell the two apart.</p>
     */
    public static final class CatalogLoadException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        CatalogLoadException(Path path, IOException cause) {
            super("Failed to load source catalog from " + path, cause);
        }

        CatalogLoadException(Path path, String reason) {
            super("Failed to load source catalog from " + path + ": " + reason);
        }
    }

    /**
     * Opens the catalog under a data directory, loading what a previous run recorded.
     *
     * @param dataDirectory The node's data directory
     * @return The catalog
     * @throws NullPointerException  if the directory is null
     * @throws CatalogLoadException  if a catalog exists and cannot be read
     */
    public static SourceCatalog open(Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory must not be null");
        return new SourceCatalog(dataDirectory.resolve(FILE));
    }

    /**
     * Opens the catalog at an explicit path.
     *
     * @param file The catalog file, which need not exist yet
     * @throws NullPointerException if the path is null
     * @throws CatalogLoadException if the file exists and cannot be read
     */
    public SourceCatalog(Path file) {
        this(file, fileSystemWriter());
    }

    /**
     * Returns the operations as the filesystem performs them.
     *
     * <p>The production writer, named so a test that needs the real steps to happen underneath
     * its recording can reach them rather than reimplementing an fsync of its own.</p>
     *
     * @return A writer that forces and renames for real
     */
    static CatalogWriter fileSystemWriter() {
        return new FileSystemWriter();
    }

    /**
     * Opens the catalog against an explicit set of disk operations.
     *
     * <p>Exists so a test can observe the order the contents and the rename are made durable in,
     * which is the whole of the crash-safety this class claims and is otherwise only visible to a
     * power cut.</p>
     *
     * @param file   The catalog file, which need not exist yet
     * @param writer The operations that publish a rewrite
     * @throws NullPointerException if either argument is null
     * @throws CatalogLoadException if the file exists and cannot be read
     */
    SourceCatalog(Path file, CatalogWriter writer) {
        this.file = Objects.requireNonNull(file, "file must not be null");
        this.writer = Objects.requireNonNull(writer, "writer must not be null");
        if (Files.exists(file)) {
            load();
        }
    }

    /**
     * Records a registration, replacing any earlier one under the same id.
     *
     * <p>Returns once the record is on disk. A caller that is about to answer "registered" has to
     * be able to rely on that, or the answer is a guess about whether the process survives.</p>
     *
     * @param descriptor What the source was registered as
     * @throws NullPointerException if the descriptor is null
     * @throws IOException          if the catalog cannot be written
     */
    public void record(SourceDescriptor descriptor) throws IOException {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        synchronized (writeLock) {
            SourceDescriptor previous = sources.put(descriptor.sourceId(), descriptor);
            try {
                persist();
            } catch (IOException e) {
                // Nothing reached the file, so memory has to be put back the way the file has it.
                if (previous == null) {
                    sources.remove(descriptor.sourceId());
                } else {
                    sources.put(descriptor.sourceId(), previous);
                }
                throw e;
            }
        }
    }

    /**
     * Drops a registration, so the next start does not bring it back.
     *
     * @param sourceId The source id to forget
     * @return true if the catalog held a record under that id
     * @throws NullPointerException if the id is null
     * @throws IOException          if the catalog cannot be written
     */
    public boolean forget(String sourceId) throws IOException {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        synchronized (writeLock) {
            SourceDescriptor removed = sources.remove(sourceId);
            if (removed == null) {
                return false;
            }
            try {
                persist();
            } catch (IOException e) {
                sources.put(sourceId, removed);
                throw e;
            }
            return true;
        }
    }

    /**
     * Returns every recorded registration.
     *
     * @return The descriptors, in no particular order
     */
    public Collection<SourceDescriptor> all() {
        return List.copyOf(sources.values());
    }

    /**
     * Returns how many registrations the catalog holds.
     *
     * @return The record count
     */
    public int size() {
        return sources.size();
    }

    /**
     * Rewrites the whole file and replaces the old one in a single step.
     *
     * <p>Written to a sibling and moved over, so a crash mid-write leaves the previous catalog
     * intact rather than a truncated one that loads as a shorter list of sources. The sibling is
     * forced before the rename and the directory after it, because a rename that is durable
     * while its contents are not reproduces the very truncation the rename is here to avoid.</p>
     *
     * <p>Forcing the contents is not best effort: {@link #record(SourceDescriptor)} answers
     * "registered" on the strength of it, so a failure has to reach the caller and put memory
     * back the way the file has it. Forcing the directory is best effort, because a platform that
     * will not open a directory channel is not a reason to fail a registration that is otherwise
     * on disk.</p>
     */
    private void persist() throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path replacement = file.resolveSibling(file.getFileName() + REPLACEMENT_SUFFIX);
        try {
            write(replacement);
            writer.force(replacement);
            writer.publish(replacement, file);
        } catch (IOException e) {
            // The published catalog is still the old one, so the only thing to undo is the
            // half-written sibling. Suppressed rather than thrown, because losing the real cause
            // to a tidy-up failure is the worse trade.
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

    /**
     * Writes every record and the end marker that proves the file is whole.
     *
     * <p>The count is taken from the same pass that writes the records rather than from
     * {@code sources.size()}, so it states what actually reached the file.</p>
     */
    private void write(Path replacement) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(replacement)) {
            int written = 0;
            for (SourceDescriptor descriptor : sources.values()) {
                out.write(format(descriptor));
                out.newLine();
                written++;
            }
            out.write(END_MARKER);
            out.write(FIELD_SEPARATOR);
            out.write(Integer.toString(written));
            out.newLine();
        }
    }

    /**
     * The operations as the filesystem performs them.
     */
    private static final class FileSystemWriter implements CatalogWriter {

        @Override
        public void force(Path path) throws IOException {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }

        @Override
        public void publish(Path replacement, Path target) throws IOException {
            try {
                Files.move(replacement, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                LOGGER.warn("This filesystem cannot rename atomically; the source catalog is "
                    + "published with a plain replace and a crash during it can truncate it");
                Files.move(replacement, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        @Override
        public void forceDirectory(Path directory) {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            } catch (IOException e) {
                LOGGER.warn("Could not force the directory entry for the source catalog at {}; "
                    + "a host crash immediately after a registration can lose the rename that "
                    + "published it: {}", directory, e.getMessage());
            }
        }
    }

    private void load() {
        int skipped = 0;
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new CatalogLoadException(file, e);
        }

        for (String line : records(lines)) {
            SourceDescriptor descriptor = parse(line);
            if (descriptor == null) {
                skipped++;
                continue;
            }
            sources.put(descriptor.sourceId(), descriptor);
        }

        if (skipped > 0) {
            LOGGER.warn("Skipped {} unparseable record(s) while loading the source catalog from "
                + "{}; those sources will not be re-crawled until they are registered again",
                skipped, file);
        }
    }

    /**
     * Strips the end marker and checks that the file in front of it is all there.
     *
     * <p>A catalog that lost its tail keeps whole records, so nothing about the lines themselves
     * says anything is missing; only the marker and the count it carries can. Refusing to load is
     * the same stance the class takes on a catalog it cannot read at all - a node that starts on
     * a short catalog re-crawls fewer sources than it was asked to and overwrites the rest at the
     * next registration, with nothing on the way saying so.</p>
     *
     * @param lines Every line of the file
     * @return The record lines, marker removed
     * @throws CatalogLoadException if the marker is missing or disagrees with what is there
     */
    private List<String> records(List<String> lines) {
        if (lines.isEmpty()) {
            throw new CatalogLoadException(file,
                "the file is empty, so the write that created it never reached the disk");
        }

        List<String> records = lines.subList(0, lines.size() - 1);
        int declared = declaredCount(lines.get(lines.size() - 1));
        if (declared < 0) {
            throw new CatalogLoadException(file,
                "it does not end with a record count, so it was cut short before the write that "
                    + "published it finished, or it was written by hand");
        }
        if (declared != records.size()) {
            throw new CatalogLoadException(file, "it declares " + declared
                + " record(s) and carries " + records.size());
        }
        return records;
    }

    /**
     * Reads the count out of a candidate end marker.
     *
     * @return The declared record count, or -1 if the line is not an end marker
     */
    private static int declaredCount(String line) {
        String prefix = END_MARKER + FIELD_SEPARATOR;
        if (!line.startsWith(prefix)) {
            return -1;
        }
        try {
            int count = Integer.parseInt(line.substring(prefix.length()));
            return count < 0 ? -1 : count;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String format(SourceDescriptor descriptor) {
        StringBuilder record = new StringBuilder()
            .append(escape(descriptor.sourceType()))
            .append(FIELD_SEPARATOR)
            .append(escape(descriptor.sourceId()));
        for (Map.Entry<String, String> entry : new TreeMap<>(descriptor.config()).entrySet()) {
            record.append(FIELD_SEPARATOR)
                .append(escape(entry.getKey()))
                .append(PAIR_SEPARATOR)
                .append(escape(entry.getValue()));
        }
        return record.toString();
    }

    /**
     * Parses one persisted record.
     *
     * @return The descriptor, or null if the line is malformed
     */
    private static SourceDescriptor parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        List<String> fields = splitEscaped(line, FIELD_SEPARATOR);
        if (fields.size() < FIXED_FIELDS) {
            return null;
        }

        Map<String, String> config = new LinkedHashMap<>();
        for (String field : fields.subList(FIXED_FIELDS, fields.size())) {
            List<String> pair = splitEscaped(field, PAIR_SEPARATOR);
            if (pair.size() != 2) {
                return null;
            }
            config.put(unescape(pair.get(0)), unescape(pair.get(1)));
        }

        try {
            return new SourceDescriptor(unescape(fields.get(0)), unescape(fields.get(1)), config);
        } catch (IllegalArgumentException e) {
            // A record whose type or id did not survive the round trip names no crawler.
            return null;
        }
    }

    /**
     * Escapes the characters that carry structure in a record.
     *
     * <p>{@code \\} first, so unescaping is unambiguous.</p>
     */
    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case ESCAPE -> escaped.append(ESCAPE).append(ESCAPE);
                case FIELD_SEPARATOR -> escaped.append(ESCAPE).append('p');
                case PAIR_SEPARATOR -> escaped.append(ESCAPE).append('e');
                case '\n' -> escaped.append(ESCAPE).append('n');
                case '\r' -> escaped.append(ESCAPE).append('r');
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /**
     * Reverses {@link #escape(String)}.
     */
    private static String unescape(String value) {
        if (value.indexOf(ESCAPE) < 0) {
            return value;
        }
        StringBuilder plain = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != ESCAPE || i + 1 >= value.length()) {
                plain.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case ESCAPE -> plain.append(ESCAPE);
                case 'p' -> plain.append(FIELD_SEPARATOR);
                case 'e' -> plain.append(PAIR_SEPARATOR);
                case 'n' -> plain.append('\n');
                case 'r' -> plain.append('\r');
                default -> plain.append(ESCAPE).append(next);
            }
        }
        return plain.toString();
    }

    /**
     * Splits on a delimiter, ignoring delimiters that were escaped.
     *
     * <p>A plain {@code String.split} would cut inside an escaped value and turn one source into
     * two malformed ones.</p>
     */
    private static List<String> splitEscaped(String value, char delimiter) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ESCAPE && i + 1 < value.length()) {
                current.append(c).append(value.charAt(++i));
                continue;
            }
            if (c == delimiter) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }
}
