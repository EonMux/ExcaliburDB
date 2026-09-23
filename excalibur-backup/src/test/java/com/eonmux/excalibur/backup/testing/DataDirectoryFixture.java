package com.eonmux.excalibur.backup.testing;

import com.eonmux.excalibur.filter.lsh.LshConfig;
import com.eonmux.excalibur.filter.lsh.LshIndex;
import com.eonmux.excalibur.retrieval.lexical.native_.LexicalStore;
import com.eonmux.excalibur.store.StorageEngine;
import com.eonmux.excalibur.vector.distance.L2Distance;
import com.eonmux.excalibur.vector.hnsw.HnswConfig;
import com.eonmux.excalibur.vector.hnsw.HnswGraph;
import com.eonmux.excalibur.vector.hnsw.NodeDocumentMapping;
import com.eonmux.excalibur.vector.quantization.PQCodebookFile;
import com.eonmux.excalibur.vector.quantization.ProductQuantizer;

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
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Builds data directories that look like the ones a node leaves behind.
 *
 * <p>Three shapes are offered. {@link #realEngineDirectory(Path, int)} starts a real
 * {@link StorageEngine}, writes entries through it and stops it, so the catalog, the routing
 * directory, the segment files and their write-ahead logs are the engine's own bytes rather
 * than a guess at them. {@link #syntheticDirectory(Path)} hand-writes the same tree in
 * miniature, including the files that only the ingestion and graph modules ever create, so
 * that the tests which care about classification and refusal do not pay for a 4 GiB engine
 * start. {@link #realIndexedDirectory(Path)} is the first with every sidecar a segment can
 * own written beside it by the module that owns its format - a graph, its document mapping,
 * a codebook and an LSH table - and a lexical store written by the lexical store itself, so
 * that a deep verification is exercised against each format's genuine bytes.</p>
 *
 * <p>All three are sparse where the engine is sparse: a write-ahead log is mapped at its maximum
 * size and filled a block at a time, and a fixture that materialised those zeros would be
 * testing a file the engine never writes.</p>
 */
public final class DataDirectoryFixture {

    /** The write-ahead log size the engine maps, mostly hole. */
    public static final long WAL_APPARENT_SIZE = 64L * 1024 * 1024;

    /** Width of every vector the indexed fixture writes. */
    public static final int DIMENSIONS = 8;

    /** The graph {@link #realIndexedDirectory(Path)} writes, relative to the directory. */
    public static final String GRAPH = "segment_0.hnsw";

    /** The graph's document mapping. */
    public static final String DOCUMENT_MAPPING = "segment_0.hnsw.docmap";

    /** The graph's quantiser codebook. */
    public static final String CODEBOOK = "segment_0.hnsw.pqcb";

    /**
     * The first segment's term sketch, which the storage engine itself writes beside the
     * segment when it closes cleanly.
     */
    public static final String TERM_SKETCH = "segment_0.tfs";

    /** The first table of the segment's LSH index. */
    public static final String LSH_TABLE = "segment_0.lsh/lsh_table_0.dat";

    /** The in-flight ingest journal, a format that carries nothing to verify. */
    public static final String IN_FLIGHT_JOURNAL = "document-registry.in-flight";

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
     * Writes a real engine directory with every sidecar and a lexical store beside it.
     *
     * <p>Each structure is produced by the class that owns its format and closed the way a
     * node closes it, so every one carries the seal a clean shutdown writes: an image of this
     * directory should verify to the deepest level, and any damage a test introduces is
     * damage to the real layout rather than to a guess at it.</p>
     *
     * @param dataDir The directory to populate; created if absent
     * @return The same directory
     * @throws IOException if any structure cannot be written
     */
    public static Path realIndexedDirectory(Path dataDir) throws IOException {
        realEngineDirectory(dataDir, 24);
        List<float[]> vectors = vectors(64);
        try (HnswGraph graph = new HnswGraph(dataDir.resolve(GRAPH),
                HnswConfig.create(DIMENSIONS), L2Distance.getInstance(), 128)) {
            NodeDocumentMapping mapping = new NodeDocumentMapping(
                dataDir.resolve(DOCUMENT_MAPPING));
            for (int i = 0; i < 16; i++) {
                mapping.register(graph.insert(vectors.get(i)), "source:doc-" + i + ":chunk:0");
            }
            mapping.persist();
        }
        ProductQuantizer quantizer = new ProductQuantizer(DIMENSIONS, 2, 4);
        quantizer.train(vectors.toArray(new float[0][]));
        PQCodebookFile.write(dataDir.resolve(CODEBOOK), quantizer);
        try (LshIndex lsh = LshIndex.open(LshConfig.createWithSeed(DIMENSIONS, 2, 4, 42L),
                dataDir.resolve(LSH_TABLE).getParent())) {
            for (int i = 0; i < 16; i++) {
                lsh.insert(vectors.get(i), i);
            }
        }
        try (LexicalStore lexical = LexicalStore.open(dataDir.resolve("chunk-index"))) {
            lexical.add(Map.of("source:doc-0:chunk:0", "cold segments move to cheaper disks",
                "source:doc-1:chunk:0", "the graph links every vector to its neighbours"), 1L);
        }
        Files.writeString(dataDir.resolve(IN_FLIGHT_JOURNAL), "");
        return dataDir;
    }

    /**
     * Returns deterministic vectors, the same on every run.
     *
     * @param count How many
     * @return The vectors
     */
    private static List<float[]> vectors(int count) {
        Random random = new Random(7L);
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> {
            float[] vector = new float[DIMENSIONS];
            for (int d = 0; d < DIMENSIONS; d++) {
                vector[d] = random.nextFloat();
            }
            return vector;
        }).toList();
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
     * Flips one bit in the middle of what a file holds, leaving its length alone.
     *
     * <p>The middle of the written extent - from the start to the last byte that is not
     * zero - rather than of the file. A graph or an LSH table is created at its full
     * capacity and filled from the front, so the middle of the file is a hole no checksum
     * covers, and damage there is damage no checker should be expected to see. A file that
     * holds nothing but zeroes is flipped in its middle.</p>
     *
     * @param file The file to damage
     * @throws IOException if the file cannot be read or written
     */
    public static void flipAByte(Path file) throws IOException {
        byte[] content = Files.readAllBytes(file);
        if (content.length == 0) {
            throw new IllegalArgumentException("Cannot flip a byte in an empty file: " + file);
        }
        int written = content.length - 1;
        while (written > 0 && content[written] == 0) {
            written--;
        }
        int position = written == 0 ? content.length / 2 : written / 2;
        content[position] = (byte) (content[position] ^ 0x01);
        Files.write(file, content);
    }
}
