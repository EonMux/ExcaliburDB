package excalibur.backup.manifest;

import excalibur.backup.io.AtomicPublish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.CRC32C;

/**
 * Reads and writes the manifest file.
 *
 * <h2>Why text</h2>
 * <p>A manifest is read twice: by this tool, and by whoever is standing in front of a
 * failed restore at three in the morning wanting to know what the image was supposed to
 * contain. The second reader has no tooling and no patience, so the format is lines of
 * UTF-8 they can page through, grep and diff. The cost of that choice is a parser that has
 * to be strict, which is below.</p>
 *
 * <h2>Why it checksums itself</h2>
 * <p>The manifest is what proves the payload intact, so nothing else is in a position to
 * prove the manifest intact. A CRC32C over the body, written as its last line, closes
 * that: an edited, truncated or rotted manifest is refused rather than half-believed, and
 * a manifest that has lost its last few entries - the failure a trailing count alone would
 * miss - fails both the count and the checksum.</p>
 *
 * <h2>Why it is published, not written</h2>
 * <p>It goes down last and by rename, so a snapshot directory either has a complete
 * manifest or has none. A directory with no manifest is an interrupted snapshot, and
 * saying so is more useful than a manifest describing files that were never copied.</p>
 *
 * @author ExcaliburDB
 */
public final class ManifestCodec {

    /** The manifest's name inside a snapshot directory. */
    public static final String FILE_NAME = "MANIFEST";

    /** The first line of every manifest, which is how one is recognised. */
    public static final String MAGIC_LINE = "EXCALIBUR-SNAPSHOT-MANIFEST";

    /** The manifest format this build writes and is the newest it can read. */
    public static final int MANIFEST_VERSION = 1;

    private static final String FILES_HEADING = "files:";
    private static final String END_PREFIX = "end: ";
    private static final String CRC_PREFIX = "body-crc32c: ";

    private ManifestCodec() {
    }

    /**
     * Writes a manifest, forcing its bytes before the name that makes it authoritative.
     *
     * @param manifest What to write
     * @param file     Where to write it
     * @throws IOException          if the file cannot be staged, forced or renamed
     * @throws NullPointerException if either argument is null
     */
    public static void write(SnapshotManifest manifest, Path file) throws IOException {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(file, "file");
        String body = render(manifest);
        String document = body + CRC_PREFIX + crc32c(body) + "\n";
        Path staged = file.resolveSibling(file.getFileName() + ".staging");
        Files.writeString(staged, document, StandardCharsets.UTF_8);
        AtomicPublish.publish(staged, file);
    }

    /**
     * Reads a manifest, refusing anything it cannot prove whole.
     *
     * @param file The manifest
     * @return What it says
     * @throws ManifestFormatException if the file is absent, is not a manifest, was written
     *                                 by a newer format, or does not check out
     * @throws IOException             if the file cannot be read
     */
    public static SnapshotManifest read(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!Files.isRegularFile(file)) {
            throw new ManifestFormatException(file, "there is no manifest here, so this is "
                + "either not a snapshot directory or the snapshot never finished");
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        requireChecksum(file, lines);
        return parse(file, lines.subList(0, lines.size() - 1));
    }

    /**
     * Renders every line of the manifest except its checksum.
     *
     * @param manifest What to render
     * @return The body, newline-terminated
     */
    private static String render(SnapshotManifest manifest) {
        StringBuilder body = new StringBuilder(MAGIC_LINE).append('\n');
        body.append("manifest-version: ").append(manifest.manifestVersion()).append('\n');
        body.append("snapshot-id: ").append(manifest.snapshotId()).append('\n');
        body.append("created-at: ").append(manifest.createdAt()).append('\n');
        body.append("producer: ").append(manifest.producer()).append('\n');
        body.append("source-data-dir: ").append(manifest.sourceDataDir()).append('\n');
        body.append("epoch: ").append(manifest.epoch()).append('\n');
        body.append("epoch-source: ").append(manifest.epochSource()).append('\n');
        body.append("consistency: ").append(manifest.consistency()).append('\n');
        for (var version : new TreeMap<>(manifest.formatVersions()).entrySet()) {
            body.append("format-version: ").append(version.getKey()).append('=')
                .append(version.getValue()).append('\n');
        }
        body.append("total-bytes: ").append(manifest.totalBytes()).append('\n');
        body.append(FILES_HEADING).append('\n');
        for (FileEntry entry : manifest.files()) {
            body.append(entry.size()).append(' ').append(entry.crc32c()).append(' ')
                .append(entry.sha256()).append(' ').append(entry.path()).append('\n');
        }
        return body.append(END_PREFIX).append(manifest.files().size()).append('\n').toString();
    }

    /**
     * Checks the manifest's own checksum before a single field of it is believed.
     *
     * @param file  The manifest, for the message
     * @param lines Every line of it, the checksum line included
     * @throws ManifestFormatException if the last line is not a checksum, or does not match
     */
    private static void requireChecksum(Path file, List<String> lines)
        throws ManifestFormatException {
        if (lines.isEmpty() || !lines.get(0).equals(MAGIC_LINE)) {
            throw new ManifestFormatException(file, "this is not a manifest: it does not "
                + "begin with " + MAGIC_LINE);
        }
        String last = lines.get(lines.size() - 1);
        if (!last.startsWith(CRC_PREFIX)) {
            throw new ManifestFormatException(file, "the manifest has no trailing checksum, "
                + "so it has been truncated");
        }
        String body = String.join("\n", lines.subList(0, lines.size() - 1)) + "\n";
        String expected = crc32c(body);
        String stored = last.substring(CRC_PREFIX.length()).trim();
        if (!expected.equals(stored)) {
            throw new ManifestFormatException(file, "the manifest body checksum is " + stored
                + " but its bytes compute to " + expected + "; it has been edited or damaged");
        }
    }

    /**
     * Parses a body whose checksum has already been accepted.
     *
     * @param file  The manifest, for messages
     * @param body  Every line except the checksum
     * @return What it says
     * @throws ManifestFormatException if a field is missing, malformed or from the future
     */
    private static SnapshotManifest parse(Path file, List<String> body)
        throws ManifestFormatException {
        int filesAt = body.indexOf(FILES_HEADING);
        if (filesAt < 0 || !body.get(body.size() - 1).startsWith(END_PREFIX)) {
            throw new ManifestFormatException(file, "the manifest has no file list");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> formats = new TreeMap<>();
        readHeaders(file, body.subList(1, filesAt), headers, formats);
        List<FileEntry> files = readEntries(file, body.subList(filesAt + 1, body.size() - 1));
        requireDeclaredCount(file, body.get(body.size() - 1), files.size());
        int version = integer(file, headers, "manifest-version");
        if (version > MANIFEST_VERSION) {
            throw new ManifestFormatException(file, "the manifest is format version " + version
                + ", which this build cannot read; it supports up to " + MANIFEST_VERSION);
        }
        return new SnapshotManifest(version, required(file, headers, "snapshot-id"),
            instant(file, headers), required(file, headers, "producer"),
            required(file, headers, "source-data-dir"),
            longValue(file, headers, "epoch"),
            enumValue(file, EpochSource.class, required(file, headers, "epoch-source")),
            enumValue(file, Consistency.class, required(file, headers, "consistency")),
            formats, files);
    }

    /**
     * Splits the header lines into plain fields and repeated format-version fields.
     *
     * @param file    The manifest, for messages
     * @param lines   The header lines
     * @param headers Receives the plain fields
     * @param formats Receives the format versions
     * @throws ManifestFormatException if a line is not a field
     */
    private static void readHeaders(Path file, List<String> lines, Map<String, String> headers,
                                    Map<String, String> formats) throws ManifestFormatException {
        for (String line : lines) {
            int colon = line.indexOf(": ");
            if (colon < 0) {
                throw new ManifestFormatException(file, "the manifest has a line that is not "
                    + "a field: " + line);
            }
            String key = line.substring(0, colon);
            String value = line.substring(colon + 2);
            if (key.equals("format-version")) {
                int equals = value.indexOf('=');
                if (equals < 0) {
                    throw new ManifestFormatException(file, "a format version in the manifest "
                        + "is not a name=version pair: " + value);
                }
                formats.put(value.substring(0, equals), value.substring(equals + 1));
            } else {
                headers.put(key, value);
            }
        }
    }

    /**
     * Parses the file list.
     *
     * @param file  The manifest, for messages
     * @param lines One line per file
     * @return The entries, in the order they were written
     * @throws ManifestFormatException if a line is not an entry
     */
    private static List<FileEntry> readEntries(Path file, List<String> lines)
        throws ManifestFormatException {
        List<FileEntry> entries = new ArrayList<>(lines.size());
        for (String line : lines) {
            String[] parts = line.split(" ", 4);
            if (parts.length != 4) {
                throw new ManifestFormatException(file, "the manifest has a file line that is "
                    + "not size, crc32c, sha256 and path: " + line);
            }
            try {
                entries.add(new FileEntry(parts[3], Long.parseLong(parts[0]), parts[1],
                    parts[2]));
            } catch (IllegalArgumentException e) {
                throw new ManifestFormatException(file, "the manifest has an unusable file "
                    + "line (" + e.getMessage() + "): " + line);
            }
        }
        return entries;
    }

    /**
     * Checks the trailing count against the entries actually read.
     *
     * @param file     The manifest, for messages
     * @param endLine  The trailing line
     * @param actual   How many entries were parsed
     * @throws ManifestFormatException if the two disagree
     */
    private static void requireDeclaredCount(Path file, String endLine, int actual)
        throws ManifestFormatException {
        String declared = endLine.substring(END_PREFIX.length()).trim();
        if (!declared.equals(Integer.toString(actual))) {
            throw new ManifestFormatException(file, "the manifest says it names " + declared
                + " files and names " + actual);
        }
    }

    /**
     * Returns a required header.
     *
     * @param file    The manifest, for messages
     * @param headers The parsed headers
     * @param key     The field name
     * @return Its value
     * @throws ManifestFormatException if the field is absent
     */
    private static String required(Path file, Map<String, String> headers, String key)
        throws ManifestFormatException {
        String value = headers.get(key);
        if (value == null) {
            throw new ManifestFormatException(file, "the manifest has no " + key + " field");
        }
        return value;
    }

    /**
     * Returns a required header as an int.
     *
     * @param file    The manifest, for messages
     * @param headers The parsed headers
     * @param key     The field name
     * @return Its value
     * @throws ManifestFormatException if the field is absent or not a number
     */
    private static int integer(Path file, Map<String, String> headers, String key)
        throws ManifestFormatException {
        return (int) longValue(file, headers, key);
    }

    /**
     * Returns a required header as a long.
     *
     * @param file    The manifest, for messages
     * @param headers The parsed headers
     * @param key     The field name
     * @return Its value
     * @throws ManifestFormatException if the field is absent or not a number
     */
    private static long longValue(Path file, Map<String, String> headers, String key)
        throws ManifestFormatException {
        String value = required(file, headers, key);
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new ManifestFormatException(file, "the manifest's " + key + " is not a "
                + "number: " + value);
        }
    }

    /**
     * Parses the creation timestamp.
     *
     * @param file    The manifest, for messages
     * @param headers The parsed headers
     * @return The instant
     * @throws ManifestFormatException if it is absent or unparseable
     */
    private static Instant instant(Path file, Map<String, String> headers)
        throws ManifestFormatException {
        String value = required(file, headers, "created-at");
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new ManifestFormatException(file, "the manifest's created-at is not an "
                + "instant: " + value);
        }
    }

    /**
     * Parses one of this package's enumerations.
     *
     * @param file  The manifest, for messages
     * @param type  The enumeration
     * @param value The name to parse
     * @param <E>   The enumeration type
     * @return The constant
     * @throws ManifestFormatException if no constant has that name
     */
    private static <E extends Enum<E>> E enumValue(Path file, Class<E> type, String value)
        throws ManifestFormatException {
        try {
            return Enum.valueOf(type, value.trim());
        } catch (IllegalArgumentException e) {
            throw new ManifestFormatException(file, "the manifest names an unknown "
                + type.getSimpleName() + ": " + value);
        }
    }

    /**
     * Computes the CRC32C of a string's UTF-8 bytes.
     *
     * @param text The text
     * @return Eight lowercase hex characters
     */
    private static String crc32c(String text) {
        CRC32C crc = new CRC32C();
        crc.update(text.getBytes(StandardCharsets.UTF_8));
        return String.format("%08x", crc.getValue());
    }
}
