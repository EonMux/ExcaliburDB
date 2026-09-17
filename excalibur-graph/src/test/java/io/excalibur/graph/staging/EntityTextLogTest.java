package io.excalibur.graph.staging;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.provenance.SourceType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Entity text log")
class EntityTextLogTest {

    private static final Provenance DOC_42 = new Provenance(SourceType.DOCUMENT, 42, 90, 3);
    private static final Provenance DOC_77 = new Provenance(SourceType.DOCUMENT, 77, 90, 3);

    private static Path file(Path dir) {
        return dir.resolve("entity_text.log");
    }

    /** Everything the log holds, as {@code entityId|snippet|sourceType#sourceId/version}. */
    private static List<String> dump(Path path) throws IOException {
        List<String> seen = new ArrayList<>();
        try (EntityTextLog log = EntityTextLog.open(path)) {
            log.forEach((entityId, snippet, provenance) -> seen.add(entityId + "|" + snippet + "|"
                + provenance.sourceType() + "#" + provenance.sourceId()
                + "/" + provenance.extractorVersion()));
        }
        return seen;
    }

    /** Writes a log in the pre-provenance format an existing deployment already has on disk. */
    private static void writeLegacyLog(Path path, List<String> snippets) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeLong(EntityTextLog.MAGIC);
        out.writeInt(EntityTextLog.VERSION_WITHOUT_PROVENANCE);
        out.writeInt(0);
        for (int id = 0; id < snippets.size(); id++) {
            byte[] text = snippets.get(id).getBytes(StandardCharsets.UTF_8);
            CRC32 crc = new CRC32();
            crc.update(text);
            out.writeInt(id);
            out.writeInt(text.length);
            out.write(text);
            out.writeInt((int) crc.getValue());
        }
        out.flush();
        Files.createDirectories(path.getParent());
        Files.write(path, bytes.toByteArray());
    }

    @Nested
    @DisplayName("provenance")
    class WithProvenance {

        @Test
        @DisplayName("a snippet keeps the source it was copied out of")
        void provenanceRoundTrips(@TempDir Path dir) throws IOException {
            try (EntityTextLog log = EntityTextLog.open(file(dir))) {
                log.append(1, "Jane worked at Acme", DOC_42);
                log.append(2, "Acme filed accounts", DOC_77);
            }

            assertThat(dump(file(dir))).containsExactly(
                "1|Jane worked at Acme|DOCUMENT#42/3",
                "2|Acme filed accounts|DOCUMENT#77/3");
        }

        @Test
        @DisplayName("erasing a source removes that document's prose and leaves the rest")
        void sourceScopedErasureReachesSnippets(@TempDir Path dir) throws IOException {
            // The finding: snippets carried no provenance, so withdrawing a document removed its
            // triples and left up to 2 KiB of its prose per mention, to be re-indexed into the
            // Lucene document index and re-embedded on the very next build.
            try (EntityTextLog log = EntityTextLog.open(file(dir))) {
                log.append(1, "confidential prose from document 42", DOC_42);
                log.append(2, "ordinary prose from document 77", DOC_77);

                EntityTextLog.Erased erased = log.erase((entityId, provenance) ->
                    provenance.sourceType() == SourceType.DOCUMENT && provenance.sourceId() == 42);

                assertThat(erased.removed()).isEqualTo(1);
                assertThat(erased.ofUnknownOrigin()).isZero();
            }

            assertThat(dump(file(dir)))
                .containsExactly("2|ordinary prose from document 77|DOCUMENT#77/3");
            assertThat(new String(Files.readAllBytes(file(dir)), StandardCharsets.UTF_8))
                .as("the prose has to be gone from the bytes, not merely unreferenced")
                .doesNotContain("confidential");
        }

        @Test
        @DisplayName("erasing an extractor version removes only that version's snippets")
        void extractorScopedErasureReachesSnippets(@TempDir Path dir) throws IOException {
            try (EntityTextLog log = EntityTextLog.open(file(dir))) {
                log.append(1, "from the bad extractor",
                    new Provenance(SourceType.DOCUMENT, 1, 90, 7));
                log.append(2, "from the good extractor",
                    new Provenance(SourceType.DOCUMENT, 2, 90, 8));

                assertThat(log.erase((id, provenance) -> provenance.extractorVersion() == 7)
                    .removed()).isEqualTo(1);
            }

            assertThat(dump(file(dir))).containsExactly("2|from the good extractor|DOCUMENT#2/8");
        }

        @Test
        @DisplayName("a snippet recorded without provenance is reported, never quietly kept")
        void unprovenancedSnippetsAreCounted(@TempDir Path dir) throws IOException {
            // A snippet whose origin was never recorded cannot be matched against a source scope,
            // and cannot be removed on suspicion either - that would destroy prose nobody asked
            // to erase. It has to be counted, so the obligation is not reported as discharged.
            try (EntityTextLog log = EntityTextLog.open(file(dir))) {
                log.append(1, "origin never recorded", Provenance.UNKNOWN);
                log.append(2, "from document 42", DOC_42);

                EntityTextLog.Erased erased = log.erase((entityId, provenance) ->
                    provenance.sourceType() == SourceType.DOCUMENT && provenance.sourceId() == 42);

                assertThat(erased.removed()).isEqualTo(1);
                assertThat(erased.ofUnknownOrigin()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("erasing an entity still reaches its snippets whatever their origin")
        void entityScopedErasureIsUnaffected(@TempDir Path dir) throws IOException {
            try (EntityTextLog log = EntityTextLog.open(file(dir))) {
                log.append(1, "about Jane", Provenance.UNKNOWN);
                log.append(2, "about Acme", DOC_42);

                assertThat(log.erase((entityId, provenance) -> entityId == 1).removed())
                    .isEqualTo(1);
            }

            assertThat(dump(file(dir))).containsExactly("2|about Acme|DOCUMENT#42/3");
        }

        @Test
        @DisplayName("erasing nothing leaves the file byte for byte as it was")
        void erasingNothingRewritesNothing(@TempDir Path dir) throws IOException {
            try (EntityTextLog log = EntityTextLog.open(file(dir))) {
                log.append(1, "one", DOC_42);
                log.append(2, "two", DOC_77);
                log.flush();
                byte[] before = Files.readAllBytes(file(dir));

                assertThat(log.erase((entityId, provenance) -> false).removed()).isZero();

                assertThat(Files.readAllBytes(file(dir))).isEqualTo(before);
            }
        }
    }

    @Nested
    @DisplayName("upgrading an existing deployment")
    class Upgrade {

        @Test
        @DisplayName("a log written before provenance was recorded opens with its snippets intact")
        void legacySnippetsSurviveTheUpgrade(@TempDir Path dir) throws IOException {
            writeLegacyLog(file(dir), List.of("first snippet", "second snippet"));

            assertThat(dump(file(dir))).containsExactly(
                "0|first snippet|UNKNOWN#0/0",
                "1|second snippet|UNKNOWN#0/0");
        }

        @Test
        @DisplayName("the upgrade happens once and the file is a current-version log afterwards")
        void theUpgradeIsWrittenBack(@TempDir Path dir) throws IOException {
            writeLegacyLog(file(dir), List.of("only snippet"));
            try (EntityTextLog log = EntityTextLog.open(file(dir))) {
                log.append(1, "added after the upgrade", DOC_42);
            }

            assertThat(java.nio.ByteBuffer.wrap(Files.readAllBytes(file(dir))).getInt(8))
                .isEqualTo(EntityTextLog.VERSION);
            assertThat(dump(file(dir))).containsExactly(
                "0|only snippet|UNKNOWN#0/0",
                "1|added after the upgrade|DOCUMENT#42/3");
        }

        @Test
        @DisplayName("upgraded snippets cannot be matched by source and are reported as such")
        void upgradedSnippetsAreOfUnknownOrigin(@TempDir Path dir) throws IOException {
            writeLegacyLog(file(dir), List.of("prose of unrecorded origin"));

            try (EntityTextLog log = EntityTextLog.open(file(dir))) {
                EntityTextLog.Erased erased = log.erase((entityId, provenance) ->
                    provenance.sourceType() == SourceType.DOCUMENT && provenance.sourceId() == 42);

                assertThat(erased.removed()).isZero();
                assertThat(erased.ofUnknownOrigin()).isEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("damaged records")
    class Damage {

        @Test
        @DisplayName("a corrupt record with intact snippets after it aborts the rewrite untouched")
        void midFileDamageAbortsTheRewrite(@TempDir Path dir) throws IOException {
            // The sibling triples path throws for exactly this, and the rewrite's own contract
            // promises the logs are left alone. Breaking at a bad length field and then renaming
            // the short temp over the real log discarded every snippet after the damage.
            //
            // The damage is inflicted while the log is open, because that is now the only way
            // the rewrite can meet it: a file already damaged this way is refused by open. A
            // device error under a running process is the case this leaves covered.
            try (EntityTextLog log = EntityTextLog.open(file(dir))) {
                log.append(1, "first", DOC_42);
                log.append(2, "second", DOC_77);
                log.append(3, "third", DOC_77);
                log.flush();

                byte[] all = Files.readAllBytes(file(dir));
                // The length field of the second record, made implausible.
                int second = EntityTextLog.HEADER_BYTES + 20 + "first".length() + Integer.BYTES;
                java.nio.ByteBuffer.wrap(all).putInt(second + 16, Integer.MAX_VALUE);
                Files.write(file(dir), all);
                byte[] damaged = Files.readAllBytes(file(dir));

                assertThatThrownBy(() -> log.erase((entityId, provenance) -> entityId == 1))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("intact record");
                assertThatThrownBy(() -> log.forEach((id, snippet, provenance) -> { }))
                    .as("the replay refuses it too, rather than publishing a prefix")
                    .isInstanceOf(IOException.class);

                assertThat(Files.readAllBytes(file(dir)))
                    .as("a corrupt record aborts the rewrite with the log untouched")
                    .isEqualTo(damaged);
            }
        }

        @Test
        @DisplayName("a torn tail is trimmed on open, so what is appended after it is readable")
        void aTornTailIsTrimmedOnOpen(@TempDir Path dir) throws IOException {
            // A crash mid-append leaves a partial record. The next run opens the log and appends
            // after it, and from that moment the damage has an intact record behind it - which is
            // the shape mid-file corruption has. Every later replay and every later erasure then
            // refuses the log, for ever, with nothing an operator can do about it.
            try (EntityTextLog log = EntityTextLog.open(file(dir))) {
                log.append(1, "before the crash", DOC_42);
                log.append(2, "cut short by the crash", DOC_77);
            }
            byte[] all = Files.readAllBytes(file(dir));
            Files.write(file(dir), java.util.Arrays.copyOf(all, all.length - 3));

            try (EntityTextLog log = EntityTextLog.open(file(dir))) {
                log.append(3, "after the restart", DOC_42);
            }

            assertThat(dump(file(dir))).containsExactly(
                "1|before the crash|DOCUMENT#42/3",
                "3|after the restart|DOCUMENT#42/3");
        }

        @Test
        @DisplayName("erasure still reaches the log after a crash mid-append and a restart")
        void erasureSurvivesATornTailFollowedByAnAppend(@TempDir Path dir) throws IOException {
            // The consequence with teeth: the snippet is the prose an erasure exists to destroy,
            // and a log wedged this way refuses every erasure aimed at it from then on.
            try (EntityTextLog log = EntityTextLog.open(file(dir))) {
                log.append(1, "confidential prose about Jane", DOC_42);
                log.append(2, "cut short by the crash", DOC_77);
            }
            byte[] all = Files.readAllBytes(file(dir));
            Files.write(file(dir), java.util.Arrays.copyOf(all, all.length - 3));

            try (EntityTextLog log = EntityTextLog.open(file(dir))) {
                log.append(3, "more prose about Jane", DOC_42);

                assertThat(log.erase((entityId, provenance) -> entityId == 1 || entityId == 3)
                    .removed()).isEqualTo(2);
            }

            assertThat(new String(Files.readAllBytes(file(dir)), StandardCharsets.UTF_8))
                .as("the prose has to be gone from the bytes")
                .doesNotContain("Jane");
        }

        @Test
        @DisplayName("a trimmed tail is still refused when it is really mid-file corruption")
        void openRefusesCorruptionInTheMiddle(@TempDir Path dir) throws IOException {
            // Trimming on open must not become a licence to truncate: a partial record with
            // perfectly good records after it is a write that failed for a reason the file
            // outlived, and cutting there would destroy snippets that were acknowledged.
            try (EntityTextLog log = EntityTextLog.open(file(dir))) {
                log.append(1, "first", DOC_42);
                log.append(2, "second", DOC_77);
                log.append(3, "third", DOC_77);
            }
            byte[] all = Files.readAllBytes(file(dir));
            int second = EntityTextLog.HEADER_BYTES + 20 + "first".length() + Integer.BYTES;
            java.nio.ByteBuffer.wrap(all).putInt(second + 16, Integer.MAX_VALUE);
            Files.write(file(dir), all);

            assertThatThrownBy(() -> EntityTextLog.open(file(dir)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("intact record");
        }

        @Test
        @DisplayName("a genuine torn tail does not abort the rewrite")
        void aTornTailIsTolerated(@TempDir Path dir) throws IOException {
            try (EntityTextLog log = EntityTextLog.open(file(dir))) {
                log.append(1, "kept", DOC_42);
                log.append(2, "erased", DOC_77);
            }
            byte[] all = Files.readAllBytes(file(dir));
            Files.write(file(dir), java.util.Arrays.copyOf(all, all.length - 3));

            try (EntityTextLog log = EntityTextLog.open(file(dir))) {
                assertThat(log.erase((entityId, provenance) -> entityId == 99).removed()).isZero();
            }

            assertThat(dump(file(dir))).containsExactly("1|kept|DOCUMENT#42/3");
        }
    }
}
