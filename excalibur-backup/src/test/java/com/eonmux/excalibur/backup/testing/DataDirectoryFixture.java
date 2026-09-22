package com.eonmux.excalibur.backup.testing;

import com.eonmux.excalibur.store.StorageEngine;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Builds data directories that look like the ones a node leaves behind.
 *
 * <p>Two shapes are offered. {@link #realEngineDirectory(Path)} starts a real
 * {@link StorageEngine}, writes entries through it and stops it, so the catalog, the routing
 * directory, the segment files and their write-ahead logs are the engine's own bytes rather
 * than a guess at them. {@link #syntheticDirectory(Path)} hand-writes the same tree in
 * miniature, including the files that only the ingestion and graph modules ever create, so
 * that the tests which care about classification and refusal do not pay for a 4 GiB engine
 * start.</p>
 *
 * <p>Both are sparse where the engine is sparse: a write-ahead log is mapped at its maximum
 * size and filled a block at a time, and a fixture that materialised those zeros would be
 * testing a file the engine never writes.</p>
 */
public final class DataDirectoryFixture {

    /** The write-ahead log size the engine maps, mostly hole. */
    public static final long WAL_APPARENT_SIZE = 64L * 1024 * 1024;

    private DataDirectoryFixture() {
    }

    /**
     * Starts a real storage engine over {@code dataDir}, writes entries and stops it.
     *
     * @param dataDir The directory to populate; created if absent
     * @param entries How many key/value pairs to write
     * @return The same directory, now holding a real engine's files
     * @throws IOException if the directory cannot be created
     */
    public static Path realEngineDirectory(Path dataDir, int entries) throws IOException {
        Files.createDirectories(dataDir);
        try (Arena arena = Arena.ofShared();
             StorageEngine engine = new StorageEngine(dataDir, arena)) {
            engine.start();
            for (int i = 0; i < entries; i++) {
                engine.put(("key-" + i).getBytes(StandardCharsets.UTF_8),
                    ("value-" + i).getBytes(StandardCharsets.UTF_8));
            }
            engine.flush();
        }
        return dataDir;
    }

    /**
     * Hand-writes a miniature of the full tree, ingestion and graph files included.
     *
     * <p>Two segments, each with a data file, a sparse write-ahead log, a graph and a
     * document mapping; the catalog and routing directory beside them; the ingestion
     * registries; a lexical index directory; a tenant namespace with its staging logs and a
     * built graph; and one of every staging leftover a crash can strand.</p>
     *
     * @param dataDir The directory to populate; created if absent
     * @return The same directory
     * @throws IOException if any file cannot be written
     */
    public static Path syntheticDirectory(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        writeRandom(dataDir.resolve("catalog.dat"), 61440, 1L);
        writeRandom(dataDir.resolve("routing.dir"), 272, 2L);
        writeRandom(dataDir.resolve("document-registry"), 512, 3L);
        writeRandom(dataDir.resolve("source-catalog"), 384, 4L);
        for (int segment = 0; segment < 2; segment++) {
            writeRandom(dataDir.resolve("segment_" + segment + ".dat"), 8192, 10L + segment);
            writeSparse(dataDir.resolve("segment_" + segment + ".wal"), 4096, 20L + segment);
            writeRandom(dataDir.resolve("segment_" + segment + ".hnsw"), 4096, 30L + segment);
            writeRandom(dataDir.resolve("segment_" + segment + ".hnsw.docmap"), 24,
                40L + segment);
            writeRandom(dataDir.resolve("segment_" + segment + ".pqcb"), 128, 50L + segment);
        }
        writeIngestionIndex(dataDir);
        writeTenantNamespace(dataDir);
        writeStagingLeftovers(dataDir);
        return dataDir;
    }

    /** Writes the derived lexical index. */
    private static void writeIngestionIndex(Path dataDir) throws IOException {
        Path chunkIndex = Files.createDirectories(dataDir.resolve("chunk-index"));
        writeRandom(chunkIndex.resolve("manifest"), 64, 60L);
        Path segment = Files.createDirectories(chunkIndex.resolve("seg-0"));
        writeRandom(segment.resolve("terms.dict"), 256, 61L);
        writeRandom(segment.resolve("post.dat"), 256, 62L);
    }

    /** Writes one tenant namespace: authoritative staging logs and a derived edge store. */
    private static void writeTenantNamespace(Path dataDir) throws IOException {
        Path staging = Files.createDirectories(dataDir.resolve("shared/graph-staging"));
        writeRandom(staging.resolve("entities.labels"), 128, 70L);
        writeRandom(staging.resolve("relations.labels"), 128, 71L);
        writeRandom(staging.resolve("triples.log"), 360, 72L);
        writeRandom(staging.resolve("entity_text.log"), 200, 73L);
        writeRandom(staging.resolve("erasure.log"), 96, 74L);
        Path graph = Files.createDirectories(dataDir.resolve("shared/graph"));
        writeRandom(graph.resolve("edges.csr"), 512, 75L);
    }

    /** Writes one of every staged name a crash can leave behind. */
    private static void writeStagingLeftovers(Path dataDir) throws IOException {
        writeRandom(dataDir.resolve("routing.dir.tmp"), 272, 80L);
        writeRandom(dataDir.resolve("segment_0.dat.compact"), 8192, 81L);
        writeRandom(dataDir.resolve("segment_0.wal.old"), 1024, 82L);
        writeRandom(dataDir.resolve("segment_1.dat.left"), 4096, 83L);
        writeRandom(dataDir.resolve("chunk-index/.compact-seg-0"), 64, 84L);
    }

    /**
     * Writes a file of deterministic pseudo-random bytes.
     *
     * @param file  Where to write
     * @param bytes How many bytes
     * @param seed  Seed, so the same fixture is the same bytes on every run
     * @throws IOException if the write fails
     */
    public static void writeRandom(Path file, int bytes, long seed) throws IOException {
        byte[] content = new byte[bytes];
        new Random(seed).nextBytes(content);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, content);
    }

    /**
     * Writes a file the shape of a write-ahead log: a short head, then a 64 MiB hole.
     *
     * @param file      Where to write
     * @param headBytes How many bytes of real content at the front
     * @param seed      Seed for the head's bytes
     * @throws IOException if the write fails
     */
    public static void writeSparse(Path file, int headBytes, long seed) throws IOException {
        byte[] head = new byte[headBytes];
        new Random(seed).nextBytes(head);
        try (var channel = java.nio.channels.FileChannel.open(file,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(head));
            channel.position(WAL_APPARENT_SIZE - 1);
            channel.write(ByteBuffer.wrap(new byte[] { 0 }));
        }
    }

    /**
     * Digests every regular file under a tree, keyed by its path relative to the root.
     *
     * <p>What "byte-identical" is asserted with: a restored directory that produces the same
     * map as the source produced holds the same content under the same names.</p>
     *
     * @param root The tree
     * @return Relative path to lowercase hex SHA-256, in sorted path order
     * @throws IOException if the tree cannot be walked or a file cannot be read
     */
    public static Map<String, String> digestTree(Path root) throws IOException {
        Map<String, String> digests = new LinkedHashMap<>();
        try (var walk = Files.walk(root)) {
            var files = walk.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(Path::toString)).toList();
            for (Path file : files) {
                digests.put(root.relativize(file).toString().replace('\\', '/'), digest(file));
            }
        }
        return digests;
    }

    /**
     * Digests one file with SHA-256.
     *
     * @param file The file
     * @return Lowercase hex
     * @throws IOException if the file cannot be read
     */
    public static String digest(Path file) throws IOException {
        MessageDigest sha256 = newSha256();
        byte[] buffer = new byte[1 << 20];
        try (var stream = Files.newInputStream(file)) {
            int read;
            while ((read = stream.read(buffer)) > 0) {
                sha256.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha256.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Returns a SHA-256 digest.
     *
     * @return The digest
     */
    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    /**
     * Flips one bit in the middle of a file, leaving its length alone.
     *
     * @param file The file to damage
     * @throws IOException if the file cannot be read or written
     */
    public static void flipAByte(Path file) throws IOException {
        byte[] content = Files.readAllBytes(file);
        if (content.length == 0) {
            throw new IllegalArgumentException("Cannot flip a byte in an empty file: " + file);
        }
        int position = content.length / 2;
        content[position] = (byte) (content[position] ^ 0x01);
        Files.write(file, content);
    }
}
