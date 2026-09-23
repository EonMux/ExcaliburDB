package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.file.GraphCorruptionException;
import com.eonmux.excalibur.graph.format.EdgeColumn;
import com.eonmux.excalibur.graph.integrity.ServedGraphIntegrity;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.core.integrity.IntegrityStatus;
import com.eonmux.excalibur.tenancy.NamespaceId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a node does with a built graph whose files no longer hold what the build wrote: it does
 * not serve it, it keeps the namespace open so the build that replaces it and the erasures owed
 * against it can still run, and an erasure that cannot rewrite it discards it rather than leave
 * the obligation outstanding for good.
 */
@DisplayName("Graph tenant with a damaged graph")
class GraphTenantDamageTest {

    private static final String ERASED_TERM = "quazzlethorpe";

    private static Config config() {
        return new Config(new HashMap<>(Map.of("excalibur.graph.ram-buffer-mb", "4.0")));
    }

    private static GraphTenant open(Path directory) throws IOException {
        return GraphTenant.open(NamespaceId.SHARED, directory, config(),
            GraphModelClients.forConfig(config()));
    }

    private static Provenance from(long sourceId) {
        return new Provenance(SourceType.DOCUMENT, sourceId, 90, 1);
    }

    private static void stageAndBuild(GraphTenant tenant) throws IOException {
        GraphStagingStore staging = tenant.staging();
        int alice = staging.entityIdOf("Alice Quazzlethorpe");
        int bob = staging.entityIdOf("Bob Ordinary");
        staging.append(ExtractedTriple.alwaysValid(
            "Alice Quazzlethorpe", "knows", "Bob Ordinary", from(1)));
        staging.append(ExtractedTriple.alwaysValid(
            "Bob Ordinary", "knows", "Carol Kept", from(2)));
        staging.appendEntityText(alice, "Alice Quazzlethorpe collects barometers", from(1));
        staging.appendEntityText(bob, "Bob Ordinary lectures on thaumaturgy", from(2));
        staging.flush();
        assertThat(tenant.buildService().build().published()).isTrue();
    }

    /** Flips one byte in the middle of a file of the served graph, found by its role. */
    private static Path damage(Path directory, String which) throws IOException {
        Path file = switch (which) {
            case "column" -> directory.resolve(EdgeColumn.EDGES_FWD.fileName());
            case "dictionary" -> directory.resolve("entities.dat");
            case "dictionary index" -> directory.resolve("entities.idx");
            case "lucene" -> luceneDataFile(directory.resolve(GraphRagFactory.DOCUMENT_INDEX));
            default -> throw new IllegalArgumentException(which);
        };
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 0x20;
        Files.write(file, bytes);
        return file;
    }

    /** The largest file of an index other than its commit point: one read lazily. */
    private static Path luceneDataFile(Path index) throws IOException {
        try (Stream<Path> files = Files.list(index)) {
            return files
                .filter(file -> !file.getFileName().toString().startsWith("segments"))
                .filter(file -> !file.getFileName().toString().equals("write.lock"))
                .max((a, b) -> Long.compare(a.toFile().length(), b.toFile().length()))
                .orElseThrow();
        }
    }

    private static boolean anyFileContains(Path root, String text) throws IOException {
        if (!Files.exists(root)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                // Byte for byte: the term is ASCII, and Latin-1 maps every byte to one char.
                if (new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1)
                        .contains(text)) {
                    return true;
                }
            }
        }
        return false;
    }

    @ParameterizedTest(name = "damage in a {0}")
    @ValueSource(strings = {"column", "dictionary", "dictionary index", "lucene"})
    @DisplayName("is refused by the stack, and the tenant opens without serving it")
    void notServed(String which, @TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        try (GraphTenant tenant = open(directory)) {
            stageAndBuild(tenant);
        }
        Path damaged = damage(directory, which);

        assertThatThrownBy(() -> GraphRagFactory.openAt(config(), directory,
                GraphModelClients.forConfig(config())))
            .isInstanceOf(GraphCorruptionException.class)
            .satisfies(e -> assertThat(damaged.startsWith(((GraphCorruptionException) e).file()))
                .as("the refusal names %s", damaged)
                .isTrue());

        try (GraphTenant tenant = open(directory)) {
            assertThat(tenant.holder().isPresent()).as("a damaged graph is not served").isFalse();
            assertThat(tenant.staging().stagedCount()).as("staging is untouched").isEqualTo(2);
            assertThat(Files.isDirectory(directory))
                .as("nothing is deleted by opening")
                .isTrue();
        }
    }

    @Test
    @DisplayName("with a damaged dictionary keeps the namespace closed only while an interner has "
        + "damage to cut that the dictionary alone could vouch for")
    void aDamagedDictionaryRefusesOnlyAnUnvouchedCut(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        try (GraphTenant tenant = open(directory)) {
            GraphStagingStore staging = tenant.staging();
            staging.append(ExtractedTriple.alwaysValid(
                "Alice Quazzlethorpe", "knows", "Bob Ordinary", from(1)));
            // Named by nothing staged: only the dictionary the build publishes holds its id.
            staging.entityIdOf("Dora Graphonly");
            staging.flush();
            assertThat(tenant.buildService().build().published()).isTrue();
        }
        Files.write(directory.resolve("entities.idx"), new byte[16]);
        Path interner = root.resolve("graph-staging").resolve(GraphStagingStore.ENTITY_LABELS);
        byte[] labels = Files.readAllBytes(interner);
        labels[labels.length - Integer.BYTES - 1] ^= 0x40;
        Files.write(interner, labels);

        assertThatThrownBy(() -> open(directory))
            .isInstanceOf(com.eonmux.excalibur.graph.file.LogCorruptionException.class)
            .hasMessageContaining("cannot be read")
            .hasMessageContaining("not a label dictionary");
    }

    @Test
    @DisplayName("is replaced by the next build, which then serves a verified graph")
    void replacedByTheNextBuild(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        try (GraphTenant tenant = open(directory)) {
            stageAndBuild(tenant);
        }
        damage(directory, "column");

        try (GraphTenant tenant = open(directory)) {
            assertThat(tenant.holder().isPresent()).isFalse();

            assertThat(tenant.buildService().build().published()).isTrue();

            assertThat(tenant.holder().isPresent()).as("the rebuilt graph is served").isTrue();
        }
        assertThat(ServedGraphIntegrity.inspect(directory))
            .allSatisfy(report -> assertThat(report.status()).isEqualTo(IntegrityStatus.INTACT));
    }

    @ParameterizedTest(name = "damage in a {0}")
    @ValueSource(strings = {"column", "dictionary", "dictionary index", "lucene"})
    @DisplayName("is discarded by an erasure that cannot rewrite it, and staging is erased")
    void discardedByAnErasure(String which, @TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        try (GraphTenant tenant = open(directory)) {
            stageAndBuild(tenant);
        }
        damage(directory, which);

        try (GraphTenant tenant = open(directory)) {
            tenant.erasureService().submit(new ErasureScope.Entity(
                tenant.staging().entityIdOf("Alice Quazzlethorpe")));

            GraphErasureService.ErasureOutcome outcome = tenant.erasureService().compactNow();

            assertThat(outcome.reason()).contains("discarded");
            assertThat(outcome.requestsSatisfied()).isEqualTo(1);
            assertThat(tenant.erasureService().outstandingCount()).isZero();
            assertThat(tenant.erasureService().servingWithheld()).isFalse();
            assertThat(Files.exists(directory)).as("the damaged graph is gone").isFalse();
            assertThat(tenant.holder().isPresent()).isFalse();

            assertThat(tenant.buildService().build().published())
                .as("a build publishes the graph again from what the erasure left")
                .isTrue();
            assertThat(tenant.holder().isPresent()).isTrue();
        }
        assertThat(anyFileContains(directory, ERASED_TERM))
            .as("the discarded graph cannot resurrect what was erased")
            .isFalse();
        assertThat(anyFileContains(root.resolve("graph-staging"), ERASED_TERM)).isFalse();
    }
}
