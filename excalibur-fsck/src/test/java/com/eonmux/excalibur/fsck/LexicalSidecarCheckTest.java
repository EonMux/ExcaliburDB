package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.DataDirectoryScan;
import com.eonmux.excalibur.fsck.check.DataDirectoryScanner;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.FileClassifier;
import com.eonmux.excalibur.fsck.check.FileKind;
import com.eonmux.excalibur.fsck.check.LexicalStoreCheck;
import com.eonmux.excalibur.fsck.repair.RepairKind;
import com.eonmux.excalibur.fsck.repair.RepairPlan;
import com.eonmux.excalibur.fsck.repair.RepairPlanner;
import com.eonmux.excalibur.retrieval.lexical.native_.DocumentErasureLog;
import com.eonmux.excalibur.retrieval.lexical.native_.LexicalSidecars;
import com.eonmux.excalibur.retrieval.lexical.native_.LexicalStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The files beside the lexical segments - key tables, tombstones and the erasure log - as the
 * survey judges them and the repair planner treats them.
 */
@DisplayName("Lexical store sidecars in fsck")
class LexicalSidecarCheckTest {

    private static final String STORE = FileClassifier.LEXICAL_STORE_DIRECTORY;
    private static final String SEGMENT = "seg-0";

    private static Map<String, String> docs(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /**
     * A store whose first segment carries a tombstone and whose erasure log still owes one
     * erasure; returns the store directory.
     */
    private static Path store(Path dataDir) throws IOException {
        Path store = dataDir.resolve(STORE);
        try (LexicalStore lexical = LexicalStore.open(store)) {
            lexical.add(docs("a1", "quorum replication", "a2", "quorum election"), 1);
            lexical.add(docs("b1", "quorum snapshot"), 2);
            lexical.erase(Set.of("a2"));
        }
        return store;
    }

    private static FileCheck verdictOn(LexicalStoreCheck.LexicalInspection inspection,
                                       String suffix) {
        return inspection.sidecars().stream()
            .filter(check -> check.relative().equals(STORE + "/" + suffix))
            .findFirst().orElseThrow(() -> new AssertionError("no verdict on " + suffix));
    }

    private static void flip(Path file, int offset) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        bytes[offset] ^= 0x01;
        Files.write(file, bytes);
    }

    @Nested
    @DisplayName("as the node writes them")
    class Fresh {

        @Test
        @DisplayName("are every one intact, and none is left unchecked")
        void intact(@TempDir Path dataDir) throws Exception {
            Path store = store(dataDir);

            var inspection = LexicalStoreCheck.check(store, STORE);

            assertThat(inspection.sidecars()).extracting(FileCheck::relative).contains(
                STORE + "/" + SEGMENT + LexicalSidecars.NAMES,
                STORE + "/" + SEGMENT + LexicalSidecars.OFFSETS,
                STORE + "/" + SEGMENT + LexicalSidecars.LOOKUP_TABLE,
                STORE + "/" + SEGMENT + LexicalSidecars.KEY_CHECKSUMS,
                STORE + "/" + SEGMENT + LexicalSidecars.TOMBSTONES,
                STORE + "/" + DocumentErasureLog.FILE);
            assertThat(inspection.sidecars()).allSatisfy(check ->
                assertThat(check.status()).as(check.relative()).isEqualTo(CheckStatus.INTACT));

            DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();
            assertThat(scan.files()).filteredOn(file -> file.relative().startsWith(STORE + "/"))
                .allSatisfy(file -> assertThat(file.status()).as(file.relative())
                    .isEqualTo(CheckStatus.INTACT));
        }
    }

    @Nested
    @DisplayName("that fail their checksums")
    class Damaged {

        @Test
        @DisplayName("tombstones are corrupt, and put to an operator rather than moved aside")
        void tombstones(@TempDir Path dataDir) throws Exception {
            Path store = store(dataDir);
            flip(store.resolve(SEGMENT + LexicalSidecars.TOMBSTONES), 16);

            FileCheck check = verdictOn(LexicalStoreCheck.check(store, STORE),
                SEGMENT + LexicalSidecars.TOMBSTONES);
            assertThat(check.status()).isEqualTo(CheckStatus.CORRUPT);
            assertThat(check.detail()).contains("checksum did not match");
            assertThat(check.isDamage()).isTrue();

            RepairPlan plan = RepairPlanner.plan(new DataDirectoryScanner(dataDir).scan());
            assertThat(plan.actions()).noneMatch(action ->
                action.relative().endsWith(LexicalSidecars.TOMBSTONES));
            assertThat(plan.needsOperator()).anySatisfy(question ->
                assertThat(question).contains(SEGMENT + LexicalSidecars.TOMBSTONES)
                    .contains("must not be"));
        }

        @Test
        @DisplayName("names are corrupt, and put to an operator")
        void names(@TempDir Path dataDir) throws Exception {
            Path store = store(dataDir);
            flip(store.resolve(SEGMENT + LexicalSidecars.NAMES), 0);

            FileCheck check = verdictOn(LexicalStoreCheck.check(store, STORE),
                SEGMENT + LexicalSidecars.NAMES);
            assertThat(check.status()).isEqualTo(CheckStatus.CORRUPT);

            RepairPlan plan = RepairPlanner.plan(new DataDirectoryScanner(dataDir).scan());
            assertThat(plan.needsOperator()).anySatisfy(question ->
                assertThat(question).contains(SEGMENT + LexicalSidecars.NAMES)
                    .contains("nothing can reconstruct"));
        }

        @Test
        @DisplayName("key checksums are corrupt, and put to an operator with the rebuild explained")
        void keyChecksums(@TempDir Path dataDir) throws Exception {
            Path store = store(dataDir);
            flip(store.resolve(SEGMENT + LexicalSidecars.KEY_CHECKSUMS), 20);

            var inspection = LexicalStoreCheck.check(store, STORE);
            FileCheck check = verdictOn(inspection, SEGMENT + LexicalSidecars.KEY_CHECKSUMS);
            assertThat(check.status()).isEqualTo(CheckStatus.CORRUPT);
            assertThat(inspection.covers(STORE + "/" + SEGMENT + LexicalSidecars.NAMES))
                .as("nothing was checked against checksums that could not be read")
                .isFalse();

            DataDirectoryScan scan = new DataDirectoryScanner(dataDir).scan();
            assertThat(scan.files()).filteredOn(file ->
                    file.relative().equals(STORE + "/" + SEGMENT + LexicalSidecars.NAMES))
                .singleElement().satisfies(file ->
                    assertThat(file.status()).isEqualTo(CheckStatus.UNCHECKED));
            assertThat(RepairPlanner.plan(scan).needsOperator()).anySatisfy(question ->
                assertThat(question).contains("next compaction rebuilds"));
        }

        @Test
        @DisplayName("a lookup table is corrupt, is not damage, and may be moved aside")
        void lookupTable(@TempDir Path dataDir) throws Exception {
            Path store = store(dataDir);
            Path table = store.resolve(SEGMENT + LexicalSidecars.LOOKUP_TABLE);
            Files.write(table, new byte[(int) Files.size(table)]);

            FileCheck check = verdictOn(LexicalStoreCheck.check(store, STORE),
                SEGMENT + LexicalSidecars.LOOKUP_TABLE);
            assertThat(check.status()).isEqualTo(CheckStatus.CORRUPT);
            assertThat(FileClassifier.classify(check.relative()))
                .isEqualTo(FileKind.LEXICAL_KEY_LOOKUP);
            assertThat(check.isDamage()).as("the node rebuilds it from the names").isFalse();

            RepairPlan plan = RepairPlanner.plan(new DataDirectoryScanner(dataDir).scan());
            assertThat(plan.actions()).anySatisfy(action -> {
                assertThat(action.kind()).isEqualTo(RepairKind.QUARANTINE_CORRUPT_FILE);
                assertThat(action.relative()).endsWith(LexicalSidecars.LOOKUP_TABLE);
                assertThat(action.rationale()).contains("the segment's names");
            });
        }

        @Test
        @DisplayName("an erasure log is corrupt, and put to an operator")
        void erasureLog(@TempDir Path dataDir) throws Exception {
            Path store = store(dataDir);
            Path log = store.resolve(DocumentErasureLog.FILE);
            Files.writeString(log, Files.readString(log).replace("\ta2\t", "\ta3\t"));

            FileCheck check = verdictOn(LexicalStoreCheck.check(store, STORE),
                DocumentErasureLog.FILE);
            assertThat(check.status()).isEqualTo(CheckStatus.CORRUPT);
            assertThat(check.detail()).contains("checksum did not match");

            RepairPlan plan = RepairPlanner.plan(new DataDirectoryScanner(dataDir).scan());
            assertThat(plan.needsOperator()).anySatisfy(question ->
                assertThat(question).contains(DocumentErasureLog.FILE)
                    .contains("every erasure it still holds would lapse"));
        }
    }

    @Nested
    @DisplayName("from other builds")
    class OtherBuilds {

        @Test
        @DisplayName("headerless tombstones and an unsealed key table are unprotected")
        void olderAreUnprotected(@TempDir Path dataDir) throws Exception {
            Path store = store(dataDir);
            Files.delete(store.resolve(SEGMENT + LexicalSidecars.KEY_CHECKSUMS));
            Files.write(store.resolve(SEGMENT + LexicalSidecars.TOMBSTONES), new byte[] {2});
            Files.writeString(store.resolve(DocumentErasureLog.FILE),
                "excalibur-lexical-erasure v2\nA\t1767225600000\ta2\n", StandardCharsets.UTF_8);

            var inspection = LexicalStoreCheck.check(store, STORE);

            for (String suffix : new String[] {LexicalSidecars.NAMES, LexicalSidecars.OFFSETS,
                    LexicalSidecars.LOOKUP_TABLE, LexicalSidecars.TOMBSTONES}) {
                assertThat(verdictOn(inspection, SEGMENT + suffix).status()).as(suffix)
                    .isEqualTo(CheckStatus.LEGACY_UNPROTECTED);
            }
            assertThat(verdictOn(inspection, DocumentErasureLog.FILE).status())
                .isEqualTo(CheckStatus.LEGACY_UNPROTECTED);
        }

        @Test
        @DisplayName("a newer build's tombstones are unreadable, and put to an operator")
        void newerIsUnreadable(@TempDir Path dataDir) throws Exception {
            Path store = store(dataDir);
            Path tombs = store.resolve(SEGMENT + LexicalSidecars.TOMBSTONES);
            byte[] bytes = Files.readAllBytes(tombs);
            ByteBuffer.wrap(bytes).putInt(Long.BYTES, 99);
            Files.write(tombs, bytes);

            FileCheck check = verdictOn(LexicalStoreCheck.check(store, STORE),
                SEGMENT + LexicalSidecars.TOMBSTONES);
            assertThat(check.status()).isEqualTo(CheckStatus.UNREADABLE);
            assertThat(check.detail()).contains("version 99");
            assertThat(RepairPlanner.plan(new DataDirectoryScanner(dataDir).scan())
                .needsOperator()).anySatisfy(question ->
                    assertThat(question).contains(SEGMENT + LexicalSidecars.TOMBSTONES));
        }

        @Test
        @DisplayName("a newer build's erasure log is unreadable")
        void newerLogIsUnreadable(@TempDir Path dataDir) throws Exception {
            Path store = store(dataDir);
            Files.writeString(store.resolve(DocumentErasureLog.FILE),
                "excalibur-lexical-erasure v9\n");

            assertThat(verdictOn(LexicalStoreCheck.check(store, STORE), DocumentErasureLog.FILE)
                .status()).isEqualTo(CheckStatus.UNREADABLE);
        }

        @Test
        @DisplayName("an erasure log is still judged when the manifest cannot be read")
        void logWithoutAManifest(@TempDir Path dataDir) throws Exception {
            Path store = store(dataDir);
            Files.writeString(store.resolve(LexicalStoreCheck.MANIFEST), "garbage\n");

            var inspection = LexicalStoreCheck.check(store, STORE);

            assertThat(inspection.manifest().status()).isEqualTo(CheckStatus.CORRUPT);
            assertThat(inspection.sidecars()).singleElement().satisfies(check ->
                assertThat(check.relative()).isEqualTo(STORE + "/" + DocumentErasureLog.FILE));
        }
    }
}
