package com.eonmux.excalibur.backup.verify;

import com.eonmux.excalibur.backup.testing.DataDirectoryFixture;
import com.eonmux.excalibur.core.integrity.IndexChecksum;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.retrieval.lexical.native_.LexicalSidecars;
import com.eonmux.excalibur.retrieval.lexical.native_.SegmentManifest;
import com.eonmux.excalibur.store.RoutingDirectory;
import com.eonmux.excalibur.store.format.WriteLogFormat;
import com.eonmux.excalibur.store.format.WriteLogHeader;
import com.eonmux.excalibur.vector.hnsw.NodeDocumentMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each format in an image, handed to the checker that knows it.
 *
 * <p>Every file here is written by the class that owns its format, so a verdict of sound is
 * a statement about the bytes a node really writes, and a verdict of damaged is produced by
 * flipping one of those bytes rather than by feeding a checker something no writer would
 * ever have produced. Two routes are covered: the engine's own checkers, run on a scratch
 * copy because several of them repair what they open, and the read-only checkers
 * {@code excalibur-fsck} dispatches to, run on the file where it lies.</p>
 */
@DisplayName("Asking each format in an image about itself")
class FormatChecksTest {

    @TempDir
    Path tempDir;

    private Path dataDir;

    @BeforeEach
    void writeTheDirectory() throws IOException {
        dataDir = DataDirectoryFixture.realIndexedDirectory(tempDir.resolve("data"));
    }

    private ContentCheck check(String relativePath) throws IOException {
        return FormatChecks.check(dataDir.resolve(relativePath), relativePath);
    }

    @ParameterizedTest(name = "{0} is walked by its own format's checker and proven")
    @ValueSource(strings = {
        "catalog.dat",
        "segment_0.dat",
        DataDirectoryFixture.GRAPH,
        DataDirectoryFixture.DOCUMENT_MAPPING,
        DataDirectoryFixture.CODEBOOK,
        DataDirectoryFixture.LSH_TABLE
    })
    void aSealedFileIsProven(String relativePath) throws IOException {
        ContentCheck check = check(relativePath);

        assertThat(check.path()).isEqualTo(relativePath);
        assertThat(check.healthy()).as(check.detail()).isTrue();
        assertThat(check.proven()).as(check.detail()).isTrue();
    }

    @Test
    @DisplayName("a node-to-document mapping an older build wrote is proven, and named as such")
    void aDocumentKeyedMappingIsProven() throws IOException {
        Path mapping = dataDir.resolve(DataDirectoryFixture.DOCUMENT_MAPPING);
        byte[] bytes = java.nio.file.Files.readAllBytes(mapping);
        java.nio.ByteBuffer header =
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        header.putInt(Long.BYTES,
            com.eonmux.excalibur.vector.hnsw.NodeDocumentMapping.VERSION_DOCUMENT_KEYED);
        header.putLong(40, com.eonmux.excalibur.core.integrity.IndexChecksum.of(bytes, 0, 40));
        java.nio.file.Files.write(mapping, bytes);

        ContentCheck check = check(DataDirectoryFixture.DOCUMENT_MAPPING);

        assertThat(check.healthy()).as(check.detail()).isTrue();
        assertThat(check.proven()).as(check.detail()).isTrue();
        assertThat(check.detail()).contains("name documents");
    }

    @Test
    @DisplayName("a node mapping at a version this build does not read fails, not proven")
    void aMappingFromANewerBuildFails() throws IOException {
        Path mapping = dataDir.resolve(DataDirectoryFixture.DOCUMENT_MAPPING);
        byte[] bytes = Files.readAllBytes(mapping);
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(Long.BYTES, NodeDocumentMapping.VERSION + 1);
        header.putLong(40, IndexChecksum.of(bytes, 0, 40));
        Files.write(mapping, bytes);

        ContentCheck check = check(DataDirectoryFixture.DOCUMENT_MAPPING);

        assertThat(check.healthy())
            .as("the node refuses the file, so an image holding it does not restore a node "
                + "that serves: %s", check.detail())
            .isFalse();
        assertThat(check.proven()).isFalse();
        assertThat(check.detail())
            .contains("UNREADABLE")
            .contains("format version " + (NodeDocumentMapping.VERSION + 1))
            .doesNotContain("name documents");
    }

    @Test
    @DisplayName("a registry at a version its reader refuses fails; a status history does not")
    void aRegistryFromANewerBuildFailsAndAStatusHistoryDoesNot() throws IOException {
        Files.writeString(dataDir.resolve("document-registry"),
            sealed(List.of("\\#document-registry|3"), 0));
        Files.writeString(dataDir.resolve("source-status"),
            sealed(List.of("\\#source-status|4", "docs|idle|1.0|420|0|7|0|1730000000000"), 1));

        ContentCheck registry = check("document-registry");
        ContentCheck status = check("source-status");

        assertThat(registry.healthy()).as(registry.detail()).isFalse();
        assertThat(registry.detail()).contains("refuses to start");
        assertThat(status.healthy())
            .as("the node discards a history it does not read, and a restored node starts")
            .isTrue();
        assertThat(status.proven()).isFalse();
        assertThat(status.detail()).contains("discards it");
    }

    @ParameterizedTest(name = "a flipped byte in {0} fails its format's checker")
    @ValueSource(strings = {
        DataDirectoryFixture.GRAPH,
        DataDirectoryFixture.DOCUMENT_MAPPING,
        DataDirectoryFixture.CODEBOOK,
        DataDirectoryFixture.LSH_TABLE
    })
    void aDamagedSidecarFailsItsCheck(String relativePath) throws IOException {
        DataDirectoryFixture.flipAByte(dataDir.resolve(relativePath));

        ContentCheck check = check(relativePath);

        assertThat(check.healthy()).as(check.detail()).isFalse();
        assertThat(check.proven()).isFalse();
        assertThat(check.detail()).contains("CORRUPT");
    }

    @Test
    @DisplayName("an earlier build's term sketch is reported, never read, and not damage")
    void anObsoleteTermSketchIsHealthyButUnproven() throws IOException {
        // An image an earlier build took names each segment's segment_N.tfs. Nothing in this
        // build reads one, so its bytes say nothing about the image's health either way.
        Path file = dataDir.resolve("segment_0.tfs");
        Files.write(file, new byte[48]);
        DataDirectoryFixture.flipAByte(file);

        ContentCheck check = check("segment_0.tfs");

        assertThat(check.healthy()).as(check.detail()).isTrue();
        assertThat(check.proven()).isFalse();
        assertThat(check.detail()).contains("NOT_DATA", "earlier build");
    }

    @Test
    @DisplayName("a compaction's lone staged mapping is proven by the mapping's own checker")
    void aLoneStagedMappingIsProven() throws IOException {
        String staged = "segment_0.hnsw.compact.docmap";
        Files.copy(dataDir.resolve(DataDirectoryFixture.DOCUMENT_MAPPING),
            dataDir.resolve(staged));

        ContentCheck check = check(staged);

        assertThat(check.format()).contains("staged");
        assertThat(check.healthy()).as(check.detail()).isTrue();
        assertThat(check.proven()).as(check.detail()).isTrue();
        assertThat(check.detail()).contains("pending");
    }

    @Test
    @DisplayName("a damaged lone staged mapping fails: it is the only one the graph matches")
    void aDamagedLoneStagedMappingFails() throws IOException {
        String staged = "segment_0.hnsw.compact.docmap";
        Files.copy(dataDir.resolve(DataDirectoryFixture.DOCUMENT_MAPPING),
            dataDir.resolve(staged));
        DataDirectoryFixture.flipAByte(dataDir.resolve(staged));

        ContentCheck check = check(staged);

        assertThat(check.healthy()).as(check.detail()).isFalse();
        assertThat(check.detail()).contains("CORRUPT");
    }

    @Test
    @DisplayName("a torn compaction pair is a leftover the node discards, not damage")
    void aTornCompactionPairIsALeftover() throws IOException {
        writeTornCompactionPair(dataDir);

        ContentCheck graph = check("segment_0.hnsw.compact");
        ContentCheck mapping = check("segment_0.hnsw.compact.docmap");

        assertThat(graph.healthy()).as(graph.detail()).isTrue();
        assertThat(graph.proven()).isFalse();
        assertThat(graph.detail()).contains("UNCHECKED");
        assertThat(mapping.healthy()).as(mapping.detail()).isTrue();
        assertThat(mapping.proven()).isFalse();
        assertThat(mapping.detail()).contains("UNCHECKED", "never swapped");
    }

    /**
     * Leaves what a compaction interrupted halfway through writing its staged pair leaves:
     * both names present, each cut short of its seal.
     *
     * @param directory The data directory
     * @throws IOException if the pair cannot be written
     */
    private static void writeTornCompactionPair(Path directory) throws IOException {
        byte[] graph = Files.readAllBytes(directory.resolve(DataDirectoryFixture.GRAPH));
        byte[] mapping =
            Files.readAllBytes(directory.resolve(DataDirectoryFixture.DOCUMENT_MAPPING));
        Files.write(directory.resolve("segment_0.hnsw.compact"),
            Arrays.copyOf(graph, graph.length / 2));
        Files.write(directory.resolve("segment_0.hnsw.compact.docmap"),
            Arrays.copyOf(mapping, mapping.length / 2));
    }

    @Test
    @DisplayName("an LSH table is checked as an LSH table, not opened as a segment")
    void anLshTableIsNotASegment() throws IOException {
        ContentCheck check = check(DataDirectoryFixture.LSH_TABLE);

        assertThat(check.format()).isEqualTo("lsh");
    }

    @Test
    @DisplayName("a catalog of zeroes is a create that never finished, not damage")
    void aBlankCatalogIsNotDamage() throws IOException {
        Files.write(dataDir.resolve("catalog.dat"), new byte[64]);

        ContentCheck check = check("catalog.dat");

        assertThat(check.healthy()).as(check.detail()).isTrue();
        assertThat(dataDir.resolve("catalog.dat")).as("checked on a copy, so never rewritten")
            .hasBinaryContent(new byte[64]);
    }

    @Test
    @DisplayName("a segment that will not open is damaged, whatever its checksum said")
    void anUnopenableSegmentIsDamaged() throws IOException {
        Files.write(dataDir.resolve("segment_0.dat"), new byte[64]);

        ContentCheck check = check("segment_0.dat");

        assertThat(check.healthy()).isFalse();
        assertThat(check.detail()).contains("will not open");
    }

    @Test
    @DisplayName("a catalog that will not open is damaged")
    void anUnopenableCatalogIsDamaged() throws IOException {
        byte[] truncated = new byte[64];
        java.util.Arrays.fill(truncated, (byte) 0x5A);
        Files.write(dataDir.resolve("catalog.dat"), truncated);

        ContentCheck check = check("catalog.dat");

        assertThat(check.healthy()).isFalse();
        assertThat(check.detail()).contains("will not open");
    }

    @ParameterizedTest(name = "{0} is read by excalibur-fsck's checker for its format")
    @ValueSource(strings = {"segment_0.wal", "routing.dir"})
    void theRemainingEngineFormatsGoThroughFsck(String relativePath) throws IOException {
        Path file = dataDir.resolve(relativePath);
        String before = DataDirectoryFixture.digest(file);

        ContentCheck check = check(relativePath);

        assertThat(check.healthy()).as(check.detail()).isTrue();
        assertThat(check.format()).isNotBlank();
        assertThat(DataDirectoryFixture.digest(file))
            .as("these checkers read where the file lies, so they must not write")
            .isEqualTo(before);
    }

    @Test
    @DisplayName("a write-ahead log is proven by its header and its records' checksums")
    void aWriteAheadLogIsProven() throws IOException {
        ContentCheck check = check("segment_0.wal");

        assertThat(check.healthy()).as(check.detail()).isTrue();
        assertThat(check.proven()).as(check.detail()).isTrue();
        assertThat(check.detail()).contains("INTACT");
    }

    @Test
    @DisplayName("a write-ahead log a newer build wrote fails the image, and is not walked")
    void aWriteAheadLogFromANewerBuildFails() throws IOException {
        Path log = dataDir.resolve("segment_0.wal");
        try (var file = new java.io.RandomAccessFile(log.toFile(), "rw")) {
            file.write(WriteLogHeader.encode(WriteLogFormat.CURRENT.version() + 1));
        }

        ContentCheck check = check("segment_0.wal");

        assertThat(check.healthy())
            .as("the node refuses the log and will not start, so an image holding it does not "
                + "restore a node that serves: %s", check.detail())
            .isFalse();
        assertThat(check.proven()).isFalse();
        assertThat(check.detail()).contains("UNREADABLE").contains("newer build");
    }

    @Test
    @DisplayName("a write-ahead log an older build wrote, with no header, is healthy and unproven")
    void aHeaderlessWriteAheadLogIsNotProven() throws IOException {
        Files.write(dataDir.resolve("segment_0.wal"), new byte[4096]);

        ContentCheck check = check("segment_0.wal");

        assertThat(check.healthy()).as(check.detail()).isTrue();
        assertThat(check.proven()).isFalse();
        assertThat(check.detail()).contains("LEGACY_UNPROTECTED");
    }

    @Test
    @DisplayName("a routing directory is proven by the checksum over the whole file")
    void aRoutingDirectoryIsProven() throws IOException {
        ContentCheck check = check("routing.dir");

        assertThat(check.healthy()).as(check.detail()).isTrue();
        assertThat(check.proven()).as(check.detail()).isTrue();
        assertThat(check.detail()).contains("INTACT");
    }

    @Test
    @DisplayName("a routing directory with one slot changed fails its checksum")
    void aRoutingDirectoryWithAFlippedSlotFails() throws IOException {
        Path routing = dataDir.resolve("routing.dir");
        byte[] bytes = Files.readAllBytes(routing);
        bytes[RoutingDirectory.HEADER_BYTES + 2 * Integer.BYTES] ^= 1;
        Files.write(routing, bytes);

        ContentCheck check = check("routing.dir");

        assertThat(check.healthy()).isFalse();
        assertThat(check.proven()).isFalse();
        assertThat(check.detail()).contains("CORRUPT").contains("checksum did not match");
    }

    @Test
    @DisplayName("a routing directory an older build wrote is healthy and not proven")
    void anUncheckedRoutingDirectoryIsNotProven() throws IOException {
        Path routing = dataDir.resolve("routing.dir");
        byte[] bytes = Files.readAllBytes(routing);
        byte[] legacy = java.util.Arrays.copyOf(bytes, bytes.length - RoutingDirectory.CHECKSUM_BYTES);
        ByteBuffer.wrap(legacy).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(Long.BYTES, RoutingDirectory.UNCHECKSUMMED_VERSION);
        Files.write(routing, legacy);

        ContentCheck check = check("routing.dir");

        assertThat(check.healthy()).as(check.detail()).isTrue();
        assertThat(check.proven()).isFalse();
        assertThat(check.detail()).contains("LEGACY_UNPROTECTED");
    }

    @Test
    @DisplayName("a routing directory from a newer build fails as unreadable")
    void aRoutingDirectoryFromANewerBuildFails() throws IOException {
        Path routing = dataDir.resolve("routing.dir");
        byte[] bytes = Files.readAllBytes(routing);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(Long.BYTES, RoutingDirectory.VERSION + 1);
        Files.write(routing, bytes);

        ContentCheck check = check("routing.dir");

        assertThat(check.healthy()).isFalse();
        assertThat(check.detail()).contains("UNREADABLE")
            .contains("format version " + (RoutingDirectory.VERSION + 1));
    }

    @Test
    @DisplayName("a damaged routing directory fails")
    void aDamagedRoutingDirectoryFails() throws IOException {
        Files.write(dataDir.resolve("routing.dir"), new byte[8]);

        ContentCheck check = check("routing.dir");

        assertThat(check.healthy()).isFalse();
        assertThat(check.detail()).contains("CORRUPT");
    }

    @Test
    @DisplayName("a file with nothing in it to verify says so rather than passing")
    void aFileWithNothingToVerifyIsReportedHonestly() throws IOException {
        ContentCheck check = check(DataDirectoryFixture.IN_FLIGHT_JOURNAL);

        assertThat(check.healthy()).isTrue();
        assertThat(check.proven()).isFalse();
        assertThat(check.detail()).contains("UNCHECKED").contains("nothing to verify");
    }

    @Test
    @DisplayName("the ingestion registries are proven against the checksum they close with")
    void aChecksummedRegistryIsProven() throws IOException {
        Files.writeString(dataDir.resolve("document-registry"), sealed(List.of(
            "\\#document-registry|2", "docs|a.txt|1000|4242|128|2000|docs:a.txt:chunk:0"), 1));
        Files.writeString(dataDir.resolve("source-catalog"), sealed(List.of(
            "\\#source-catalog|3", "filesystem|a|shared|root_path=/a"), 1));
        Files.writeString(dataDir.resolve("source-status"), sealed(List.of(
            "\\#source-status|3", "a|idle|1.0|3|0|1|0|1000"), 1));
        Files.writeString(dataDir.resolve("erasure-receipts"), sealed(List.of(
            "\\#erasure-receipts|1", "r-1|1000|docs|document|1|0|0|0|0|0|shared=1+1"), 1));

        for (String name : List.of("document-registry", "source-catalog", "source-status",
                "erasure-receipts")) {
            ContentCheck check = check(name);
            assertThat(check.healthy()).as(check.detail()).isTrue();
            assertThat(check.proven()).as(check.detail()).isTrue();
            assertThat(check.detail()).contains("INTACT");
        }
    }

    @Test
    @DisplayName("a registry record altered in place fails, where its count alone would pass")
    void anAlteredRegistryRecordFails() throws IOException {
        Path file = dataDir.resolve("document-registry");
        Files.writeString(file, sealed(List.of(
            "\\#document-registry|2", "docs|a.txt|1000|4242|128|2000|docs:a.txt:chunk:0"), 1));
        DataDirectoryFixture.flipAByte(file);

        ContentCheck check = check("document-registry");

        assertThat(check.healthy()).isFalse();
        assertThat(check.detail()).contains("CORRUPT");
    }

    @Test
    @DisplayName("a registry written before the checksum is healthy and not proven")
    void aRegistryBeforeTheChecksumIsNotProven() throws IOException {
        Files.writeString(dataDir.resolve("document-registry"),
            "\\#document-registry|1\ndocs|a.txt|1000|4242|128|2000|docs:a.txt:chunk:0\n"
                + "\\#end|1\n");

        ContentCheck check = check("document-registry");

        assertThat(check.healthy()).isTrue();
        assertThat(check.proven()).isFalse();
        assertThat(check.detail()).contains("LEGACY_UNPROTECTED");
    }

    @Test
    @DisplayName("an in-flight journal is proven line by line, and a damaged line fails")
    void anInFlightJournalIsProvenLineByLine() throws IOException {
        Path file = dataDir.resolve(DataDirectoryFixture.IN_FLIGHT_JOURNAL);
        Files.writeString(file, lineSealed("\\#in-flight|2") + "\n"
            + lineSealed("+|docs:a.md") + "\n");

        ContentCheck whole = check(DataDirectoryFixture.IN_FLIGHT_JOURNAL);
        assertThat(whole.healthy()).as(whole.detail()).isTrue();
        assertThat(whole.proven()).as(whole.detail()).isTrue();

        Files.writeString(file, lineSealed("\\#in-flight|2") + "\n"
            + lineSealed("+|docs:a.md").replace("docs:a", "docs:b") + "\n");

        ContentCheck damaged = check(DataDirectoryFixture.IN_FLIGHT_JOURNAL);
        assertThat(damaged.healthy()).isFalse();
        assertThat(damaged.detail()).contains("CORRUPT").contains("checksum");
    }

    /**
     * Renders lines followed by the checksummed closing line the ingestion writers end with.
     */
    private static String sealed(List<String> lines, int count) {
        StringBuilder body = new StringBuilder();
        lines.forEach(line -> body.append(line).append('\n'));
        return body + "\\#end|" + count + "|" + crc(body.toString()) + "\n";
    }

    /**
     * Renders a journal line closed with the checksum of its own bytes.
     */
    private static String lineSealed(String content) {
        return content + "|" + crc(content);
    }

    private static String crc(String text) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return String.format("%08x", crc.getValue());
    }

    @Test
    @DisplayName("the lexical store is checked as a store, and its verdicts replace its files'")
    void theLexicalStoreIsCheckedAsAWhole() throws IOException {
        List<ContentCheck> perFile = new ArrayList<>();
        try (var walk = Files.walk(dataDir.resolve("chunk-index"))) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = dataDir.relativize(file).toString().replace('\\', '/');
                perFile.add(FormatChecks.check(file, relative));
            }
        }
        assertThat(perFile).as("each file alone is only a lexical store part")
            .allSatisfy(each -> assertThat(each.proven()).isFalse());

        List<ContentCheck> folded = FormatChecks.withLexicalStore(dataDir, perFile);

        List<String> segments;
        try (var entries = Files.list(dataDir.resolve("chunk-index"))) {
            segments = entries.filter(Files::isDirectory)
                .map(segment -> "chunk-index/" + segment.getFileName()).toList();
        }
        assertThat(segments).isNotEmpty();
        assertThat(folded).extracting(ContentCheck::path)
            .contains("chunk-index/manifest")
            .containsAll(segments)
            .noneMatch(path -> segments.stream().anyMatch(s -> path.startsWith(s + "/")));
        assertThat(folded).filteredOn(each -> segments.contains(each.path()))
            .as("every lexical segment the manifest names is verified by streaming its columns")
            .allSatisfy(segment -> assertThat(segment.proven()).as(segment.detail()).isTrue());
        assertThat(folded).allSatisfy(each ->
            assertThat(each.healthy()).as("%s: %s", each.path(), each.detail()).isTrue());
    }

    @Test
    @DisplayName("the lexical store's manifest is proven by the checksum on its end line")
    void theLexicalManifestIsProven() throws IOException {
        ContentCheck manifest = lexicalManifestCheck();

        assertThat(manifest.healthy()).as(manifest.detail()).isTrue();
        assertThat(manifest.proven()).as(manifest.detail()).isTrue();
        assertThat(manifest.detail()).contains("INTACT");
    }

    @Test
    @DisplayName("a lexical manifest with a changed byte fails its checksum")
    void aLexicalManifestWithAFlippedByteFails() throws IOException {
        Path manifest = dataDir.resolve("chunk-index").resolve("manifest");
        byte[] bytes = Files.readAllBytes(manifest);
        // The last digit of the first record's epoch, still a well-formed line.
        int at = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).indexOf('\n') + 1;
        at = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).indexOf('\t', at) + 1;
        bytes[at] = (byte) (bytes[at] == '9' ? '8' : bytes[at] + 1);
        Files.write(manifest, bytes);

        ContentCheck check = lexicalManifestCheck();

        assertThat(check.healthy()).isFalse();
        assertThat(check.detail()).contains("CORRUPT").contains("checksum did not match");
    }

    @Test
    @DisplayName("a counted lexical manifest an older build wrote is healthy and not proven")
    void aCountedLexicalManifestIsNotProven() throws IOException {
        Path manifest = dataDir.resolve("chunk-index").resolve("manifest");
        List<String> lines = Files.readAllLines(manifest);
        List<String> records = lines.subList(1, lines.size() - 1);
        Files.writeString(manifest, "excalibur-lexical-store v2\n" + String.join("\n", records)
            + (records.isEmpty() ? "" : "\n") + "#end\t" + records.size() + "\n");

        ContentCheck check = lexicalManifestCheck();

        assertThat(check.healthy()).as(check.detail()).isTrue();
        assertThat(check.proven()).isFalse();
        assertThat(check.detail()).contains("LEGACY_UNPROTECTED");
    }

    @Test
    @DisplayName("a lexical manifest from a newer build fails as unreadable")
    void aLexicalManifestFromANewerBuildFails() throws IOException {
        Path manifest = dataDir.resolve("chunk-index").resolve("manifest");
        List<String> lines = new ArrayList<>(Files.readAllLines(manifest));
        lines.set(0, "excalibur-lexical-store v" + (SegmentManifest.VERSION + 1));
        Files.writeString(manifest, String.join("\n", lines) + "\n");

        ContentCheck check = lexicalManifestCheck();

        assertThat(check.healthy()).isFalse();
        assertThat(check.detail()).contains("UNREADABLE")
            .contains("format version " + (SegmentManifest.VERSION + 1));
    }

    @Test
    @DisplayName("a lexical segment's key table is proven by the checksums written with it")
    void aLexicalKeyTableIsProven() throws IOException {
        String segment = firstLexicalSegment();

        for (String suffix : List.of(LexicalSidecars.NAMES, LexicalSidecars.OFFSETS,
                LexicalSidecars.LOOKUP_TABLE, LexicalSidecars.KEY_CHECKSUMS)) {
            ContentCheck check = lexicalCheck(segment + suffix);
            assertThat(check.healthy()).as(check.detail()).isTrue();
            assertThat(check.proven()).as(check.detail()).isTrue();
        }
    }

    @Test
    @DisplayName("a lexical key table with a changed name fails its checksum")
    void aLexicalKeyTableWithAFlippedByteFails() throws IOException {
        String segment = firstLexicalSegment();
        Path names = dataDir.resolve(segment + LexicalSidecars.NAMES);
        byte[] bytes = Files.readAllBytes(names);
        bytes[0] ^= 0x01;
        Files.write(names, bytes);

        ContentCheck check = lexicalCheck(segment + LexicalSidecars.NAMES);

        assertThat(check.healthy()).isFalse();
        assertThat(check.detail()).contains("CORRUPT").contains("checksum did not match");
    }

    @Test
    @DisplayName("a lexical key table an older build wrote is healthy and not proven")
    void anUnsealedLexicalKeyTableIsNotProven() throws IOException {
        String segment = firstLexicalSegment();
        Files.delete(dataDir.resolve(segment + LexicalSidecars.KEY_CHECKSUMS));

        ContentCheck check = lexicalCheck(segment + LexicalSidecars.NAMES);

        assertThat(check.healthy()).as(check.detail()).isTrue();
        assertThat(check.proven()).isFalse();
        assertThat(check.detail()).contains("LEGACY_UNPROTECTED");
    }

    @Test
    @DisplayName("lexical key checksums from a newer build fail as unreadable")
    void lexicalKeyChecksumsFromANewerBuildFail() throws IOException {
        String segment = firstLexicalSegment();
        Path sums = dataDir.resolve(segment + LexicalSidecars.KEY_CHECKSUMS);
        byte[] bytes = Files.readAllBytes(sums);
        ByteBuffer.wrap(bytes).putInt(Long.BYTES, 99);
        Files.write(sums, bytes);

        ContentCheck check = lexicalCheck(segment + LexicalSidecars.KEY_CHECKSUMS);

        assertThat(check.healthy()).isFalse();
        assertThat(check.detail()).contains("UNREADABLE").contains("format version 99");
    }

    /**
     * Names the first segment the lexical store's manifest names.
     *
     * @return Its path relative to the data directory
     */
    private String firstLexicalSegment() throws IOException {
        return "chunk-index/"
            + SegmentManifest.read(dataDir.resolve("chunk-index")).entries().get(0).name();
    }

    /**
     * Folds the lexical store over nothing and returns its verdict on one file.
     *
     * @param relative The file's path relative to the data directory
     * @return The verdict
     */
    private ContentCheck lexicalCheck(String relative) {
        return FormatChecks.withLexicalStore(dataDir, List.of()).stream()
            .filter(each -> each.path().equals(relative))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no verdict on " + relative));
    }

    /**
     * Folds the lexical store over nothing and returns the manifest's verdict.
     *
     * @return The verdict on {@code chunk-index/manifest}
     */
    private ContentCheck lexicalManifestCheck() {
        return FormatChecks.withLexicalStore(dataDir, List.of()).stream()
            .filter(each -> each.path().equals("chunk-index/manifest"))
            .findFirst()
            .orElseThrow();
    }

    @Test
    @DisplayName("an image with no lexical store is left as it was")
    void noLexicalStoreLeavesTheChecksAlone() throws IOException {
        Path bare = DataDirectoryFixture.realEngineDirectory(tempDir.resolve("bare"), 4);
        List<ContentCheck> perFile = List.of(FormatChecks.check(bare.resolve("routing.dir"),
            "routing.dir"));

        assertThat(FormatChecks.withLexicalStore(bare, perFile)).isEqualTo(perFile);
    }

    @Test
    @DisplayName("a lexical store whose manifest names a missing segment is damaged")
    void aLexicalStoreMissingASegmentIsDamaged() throws IOException {
        Path store = dataDir.resolve("chunk-index");
        List<Path> segments;
        try (var entries = Files.list(store)) {
            segments = entries.filter(Files::isDirectory).toList();
        }
        assertThat(segments).isNotEmpty();
        com.eonmux.excalibur.backup.io.AtomicPublish.deleteTree(segments.get(0));

        List<ContentCheck> folded = FormatChecks.withLexicalStore(dataDir, List.of());

        assertThat(folded).anySatisfy(each -> assertThat(each.healthy()).isFalse());
    }

    /** Where the tenant's staging logs live in the image, relative to its payload root. */
    private static final String STAGING = "shared/graph-staging/";

    /** Stages facts and text through a real staging store, as ingestion does. */
    private void stageFacts() throws IOException {
        Provenance document = Provenance.of(SourceType.DOCUMENT, 42, 0.9, 3);
        try (GraphStagingStore store = GraphStagingStore.open(dataDir.resolve(STAGING))) {
            store.append(ExtractedTriple.alwaysValid("Ada", "employer", "Acme", document));
            store.append(ExtractedTriple.alwaysValid("Grace", "employer", "Navy", document));
            store.appendEntityText(0, "Ada wrote the first program", document);
            store.appendEntityText(1, "Acme employed Ada", document);
        }
    }

    @ParameterizedTest(name = "{0} is replayed record by record and proven")
    @ValueSource(strings = {
        GraphStagingStore.ENTITY_LABELS,
        GraphStagingStore.RELATION_LABELS,
        GraphStagingStore.TRIPLE_LOG,
        GraphStagingStore.ENTITY_TEXT_LOG
    })
    void aStagingLogIsProven(String name) throws IOException {
        stageFacts();
        Path file = dataDir.resolve(STAGING + name);
        String before = DataDirectoryFixture.digest(file);

        ContentCheck check = check(STAGING + name);

        assertThat(check.healthy()).as(check.detail()).isTrue();
        assertThat(check.proven()).as(check.detail()).isTrue();
        assertThat(check.detail()).contains("INTACT").contains("matched their checksums");
        assertThat(DataDirectoryFixture.digest(file))
            .as("the staging logs are read where they lie, so the check must not write")
            .isEqualTo(before);
    }

    @ParameterizedTest(name = "a byte flipped inside {0}, under records after it, fails the image")
    @ValueSource(strings = {
        GraphStagingStore.ENTITY_LABELS,
        GraphStagingStore.TRIPLE_LOG,
        GraphStagingStore.ENTITY_TEXT_LOG
    })
    void aDamagedStagingLogFails(String name) throws IOException {
        stageFacts();
        Path file = dataDir.resolve(STAGING + name);
        byte[] bytes = Files.readAllBytes(file);
        // Past the header, inside a record every one of these logs has an intact record after.
        bytes[16 + 21] ^= (byte) 0xFF;
        Files.write(file, bytes);

        ContentCheck check = check(STAGING + name);

        assertThat(check.healthy())
            .as("the node refuses the log, so an image holding it does not restore a node that "
                + "builds: %s", check.detail())
            .isFalse();
        assertThat(check.detail()).contains("CORRUPT");
    }

    @Test
    @DisplayName("a staging log ending in a write cut short is healthy and unproven: the node trims it")
    void aTornStagingLogIsHealthyButUnproven() throws IOException {
        stageFacts();
        Path file = dataDir.resolve(STAGING + GraphStagingStore.TRIPLE_LOG);
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length + 5));

        ContentCheck check = check(STAGING + GraphStagingStore.TRIPLE_LOG);

        assertThat(check.healthy()).as(check.detail()).isTrue();
        assertThat(check.proven()).isFalse();
        assertThat(check.detail()).contains("UNSEALED");
    }
}
