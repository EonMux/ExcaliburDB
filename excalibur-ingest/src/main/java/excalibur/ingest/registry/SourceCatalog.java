package excalibur.ingest.registry;

import excalibur.ingest.source.SourceDescriptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * <h2>Durability</h2>
 * <p>Every mutation rewrites the whole file and replaces it by an atomic move. The file holds at
 * most a few hundred short records, so a rewrite costs nothing worth optimising, and a caller that
 * has been told its source is registered must not be able to lose it to a crash a moment later.</p>
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

    private final Path file;
    private final Map<String, SourceDescriptor> sources = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

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
        this.file = Objects.requireNonNull(file, "file must not be null");
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
     * intact rather than a truncated one that loads as a shorter list of sources.</p>
     */
    private void persist() throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(temporary)) {
            for (SourceDescriptor descriptor : sources.values()) {
                writer.write(format(descriptor));
                writer.newLine();
            }
        }

        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
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

        for (String line : lines) {
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
