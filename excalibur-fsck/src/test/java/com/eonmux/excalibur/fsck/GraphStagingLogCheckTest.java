package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.DataDirectoryScan;
import com.eonmux.excalibur.fsck.check.DataDirectoryScanner;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.GraphStagingCheck;
import com.eonmux.excalibur.fsck.repair.RepairPlan;
import com.eonmux.excalibur.fsck.repair.RepairPlanner;
import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.EntityTextLog;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.staging.LabelInterner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The staging logs a graph is built from, judged by reading their records the way the node does.
 *
 * <p>Every log here is written by the class that owns its format, through a real staging store,
 * so a verdict of sound is a statement about bytes a node really writes. Each kind of damage is
 * then made the way it happens - a byte flipped under records written after it, a write cut short
 * at the end, a header from a newer build - and the verdict held to what the node does when it
 * opens the same file: refuse it, trim it, or refuse to read it at all.</p>
 */
@DisplayName("Graph staging logs")
class GraphStagingLogCheckTest {

    private static final Provenance DOCUMENT = Provenance.of(SourceType.DOCUMENT, 42, 0.9, 3);

    /** Where a tenant's staging logs live, relative to the data directory. */
    private static final String STAGING = "shared/graph-staging/";

    @TempDir
    Path dataDir;

    private Path staging;

    @BeforeEach
    void stageSomeFacts() throws IOException {
        staging = dataDir.resolve(STAGING);
        try (GraphStagingStore store = GraphStagingStore.open(staging)) {
            store.append(ExtractedTriple.alwaysValid("Ada", "employer", "Acme", DOCUMENT));
            store.append(ExtractedTriple.alwaysValid("Grace", "employer", "Navy", DOCUMENT));
            store.append(ExtractedTriple.alwaysValid("Ada", "colleague", "Grace", DOCUMENT));
            store.appendEntityText(0, "Ada wrote the first program", DOCUMENT);
            store.appendEntityText(1, "Acme employed Ada", DOCUMENT);
        }
    }

    private FileCheck check(String name) {
        return GraphStagingCheck.check(staging.resolve(name), STAGING + name);
    }

    private static String fingerprint(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void flip(Path file, long offset) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        bytes[Math.toIntExact(offset)] ^= (byte) 0xFF;
        Files.write(file, bytes);
    }

    private static void appendBytes(Path file, int count) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        byte[] longer = new byte[bytes.length + count];
        System.arraycopy(bytes, 0, longer, 0, bytes.length);
        longer[bytes.length] = 1;
        Files.write(file, longer);
    }

    @Test
    @DisplayName("the entity labels are replayed and counted")
    void entityLabelsAreIntact() throws IOException {
        Path file = staging.resolve(GraphStagingStore.ENTITY_LABELS);
        String before = fingerprint(file);

        FileCheck result = check(GraphStagingStore.ENTITY_LABELS);

        assertThat(result.status()).as(result.detail()).isEqualTo(CheckStatus.INTACT);
        assertThat(result.format()).isEqualTo("graph label interner");
        assertThat(result.version()).isEqualTo("v" + LabelInterner.VERSION);
        assertThat(result.detail()).contains("all 4 labels matched their checksums");
        assertThat(fingerprint(file)).as("the check must not write").isEqualTo(before);
    }

    @Test
    @DisplayName("the relation labels are replayed and counted")
    void relationLabelsAreIntact() {
        FileCheck result = check(GraphStagingStore.RELATION_LABELS);

        assertThat(result.status()).as(result.detail()).isEqualTo(CheckStatus.INTACT);
        assertThat(result.detail()).contains("all 2 labels matched their checksums");
    }

    @Test
    @DisplayName("the triple log is replayed and counted")
    void tripleLogIsIntact() throws IOException {
        Path file = staging.resolve(GraphStagingStore.TRIPLE_LOG);
        String before = fingerprint(file);

        FileCheck result = check(GraphStagingStore.TRIPLE_LOG);

        assertThat(result.status()).as(result.detail()).isEqualTo(CheckStatus.INTACT);
        assertThat(result.format()).isEqualTo("graph triple log");
        assertThat(result.version()).isEqualTo("v" + GraphStagingStore.VERSION);
        assertThat(result.detail()).contains("all 3 staged facts matched their checksums");
        assertThat(fingerprint(file)).as("the check must not write").isEqualTo(before);
    }

    @Test
    @DisplayName("the entity text log is replayed and counted")
    void entityTextLogIsIntact() throws IOException {
        Path file = staging.resolve(GraphStagingStore.ENTITY_TEXT_LOG);
        String before = fingerprint(file);

        FileCheck result = check(GraphStagingStore.ENTITY_TEXT_LOG);

        assertThat(result.status()).as(result.detail()).isEqualTo(CheckStatus.INTACT);
        assertThat(result.format()).isEqualTo("graph entity text log");
        assertThat(result.version()).isEqualTo("v" + EntityTextLog.VERSION);
        assertThat(result.detail()).contains("all 2 entity text snippets matched");
        assertThat(fingerprint(file)).as("the check must not write").isEqualTo(before);
    }

    @Test
    @DisplayName("a label damaged under labels written after it is corrupt, and left to a human")
    void aDamagedLabelIsCorrupt() throws IOException {
        Path file = staging.resolve(GraphStagingStore.ENTITY_LABELS);
        flip(file, LabelInterner.HEADER_BYTES + Integer.BYTES);
        String before = fingerprint(file);

        FileCheck result = check(GraphStagingStore.ENTITY_LABELS);

        assertThat(result.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.needsOperator()).isTrue();
        assertThat(result.detail()).contains("refuses to open");
        assertThat(fingerprint(file)).isEqualTo(before);
    }

    @Test
    @DisplayName("a staged fact that fails its checksum is corrupt, even the last one")
    void aDamagedFactIsCorrupt() throws IOException {
        Path file = staging.resolve(GraphStagingStore.TRIPLE_LOG);
        flip(file, Files.size(file) - Integer.BYTES - 1);

        FileCheck result = check(GraphStagingStore.TRIPLE_LOG);

        assertThat(result.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.needsOperator()).isTrue();
        assertThat(result.detail()).contains("corrupt record at fact 2");
    }

    @Test
    @DisplayName("a snippet damaged under snippets written after it is corrupt")
    void aDamagedSnippetIsCorrupt() throws IOException {
        Path file = staging.resolve(GraphStagingStore.ENTITY_TEXT_LOG);
        flip(file, EntityTextLog.HEADER_BYTES + 20 + 1);

        FileCheck result = check(GraphStagingStore.ENTITY_TEXT_LOG);

        assertThat(result.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.needsOperator()).isTrue();
    }

    @ParameterizedTest(name = "{0} ending in a write cut short is unsealed, as the node trims it")
    @ValueSource(strings = {
        GraphStagingStore.ENTITY_LABELS,
        GraphStagingStore.TRIPLE_LOG,
        GraphStagingStore.ENTITY_TEXT_LOG
    })
    void aTornTailIsUnsealed(String name) throws IOException {
        Path file = staging.resolve(name);
        appendBytes(file, 7);
        String before = fingerprint(file);

        FileCheck result = check(name);

        assertThat(result.status()).as(result.detail()).isEqualTo(CheckStatus.UNSEALED);
        assertThat(result.detail())
            .contains("7 bytes")
            .contains("cuts them off when it next opens");
        assertThat(result.isDamage()).isFalse();
        assertThat(fingerprint(file)).isEqualTo(before);
    }

    @ParameterizedTest(name = "{0} at a version this build does not read is unreadable")
    @ValueSource(strings = {
        GraphStagingStore.ENTITY_LABELS,
        GraphStagingStore.TRIPLE_LOG,
        GraphStagingStore.ENTITY_TEXT_LOG
    })
    void anUnknownVersionIsUnreadable(String name) throws IOException {
        Path file = staging.resolve(name);
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer.wrap(bytes).putInt(Long.BYTES, 99);
        Files.write(file, bytes);

        FileCheck result = check(name);

        assertThat(result.status()).isEqualTo(CheckStatus.UNREADABLE);
        assertThat(result.version()).isEqualTo("v99");
    }

    @Test
    @DisplayName("a survey proves every staging log, and a repair plans nothing for them")
    void aSurveyProvesTheStagingLogs() throws IOException {
        try (ErasureLog erasures = ErasureLog.open(staging.resolve(GraphStagingCheck.ERASURE_LOG))) {
            erasures.submit(new ErasureScope.Entity(0), Instant.EPOCH);
        }

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();

        assertThat(scan.files())
            .filteredOn(file -> file.relative().startsWith(STAGING))
            .hasSize(5)
            .allSatisfy(file -> assertThat(file.status()).as(file.relative() + ": "
                + file.detail()).isEqualTo(CheckStatus.INTACT));
        assertThat(scan.hasUnverified()).isFalse();
    }

    @Test
    @DisplayName("a torn tail is left for the node to trim: the plan neither moves nor edits it")
    void aTornTailPlansNoRepair() throws IOException {
        appendBytes(staging.resolve(GraphStagingStore.TRIPLE_LOG), 3);

        DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();
        RepairPlan plan = RepairPlanner.plan(scan);

        assertThat(scan.hasDamage()).isFalse();
        assertThat(scan.hasUnverified()).isTrue();
        assertThat(plan.actions()).isEmpty();
        assertThat(plan.needsOperator()).isEmpty();
    }

    @ParameterizedTest(name = "a damaged {0} is put to an operator, never moved aside")
    @ValueSource(strings = {
        GraphStagingStore.ENTITY_LABELS,
        GraphStagingStore.TRIPLE_LOG,
        GraphStagingStore.ENTITY_TEXT_LOG
    })
    void aDamagedLogIsLeftForAnOperator(String name) throws IOException {
        Path file = staging.resolve(name);
        // Inside a record that every one of these logs has intact records after.
        flip(file, 16 + 21);

        RepairPlan plan = RepairPlanner.plan(new DataDirectoryScanner(dataDir).scan());

        assertThat(plan.actions()).as("nothing rebuilds a staging log").isEmpty();
        assertThat(plan.needsOperator()).singleElement().asString()
            .contains(STAGING + name)
            .contains("not moved aside automatically")
            .contains("backup");
    }

    @Test
    @DisplayName("a damaged erasure log is put to an operator: moving it aside forgets erasures")
    void aDamagedErasureLogIsLeftForAnOperator() throws IOException {
        Path file = staging.resolve(GraphStagingCheck.ERASURE_LOG);
        Files.write(file, "not an erasure log, and long enough to hold a header".getBytes());

        FileCheck result = check(GraphStagingCheck.ERASURE_LOG);
        RepairPlan plan = RepairPlanner.plan(new DataDirectoryScanner(dataDir).scan());

        assertThat(result.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.needsOperator()).isTrue();
        assertThat(plan.actions()).isEmpty();
        assertThat(plan.needsOperator()).singleElement().asString()
            .contains("erasure");
    }
}
