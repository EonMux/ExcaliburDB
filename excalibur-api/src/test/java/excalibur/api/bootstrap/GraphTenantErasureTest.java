package excalibur.api.bootstrap;

import io.excalibur.config.Config;
import io.excalibur.graph.erasure.ErasureScope;
import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.provenance.SourceType;
import io.excalibur.graph.staging.ExtractedTriple;
import io.excalibur.graph.staging.GraphStagingStore;
import io.excalibur.tenancy.NamespaceId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An erasure a tenant accepts has to reach the bytes that tenant is serving.
 *
 * <p>Everything below this exists and did nothing, because nothing supplied the node's
 * {@code ServedArtifactEraser}s: the compaction rewrote the edge store, reported the two Lucene
 * indexes beside it as unreached, and left every request outstanding. The node was telling the
 * truth - the name and the prose really were still being served - and the obligation could never
 * be discharged. These tests are about the wiring that closes that.
 */
@DisplayName("Graph tenant erasure")
class GraphTenantErasureTest {

    /** A term of the erased person's name, as the segment files hold it. */
    private static final String NAME_TERM = "quazzlethorpe";

    /** A term of the prose copied out of the withdrawn document, and of nothing else. */
    private static final String WITHDRAWN_TERM = "zymurgic";

    /** A term of a document nobody withdrew. */
    private static final String KEPT_TERM = "thaumaturgy";

    /** The withdrawn document's provenance id. */
    private static final long WITHDRAWN = 42;

    private static Config config() {
        return new Config(new HashMap<>(Map.of("excalibur.graph.ram-buffer-mb", "4.0")));
    }

    private static Provenance from(long sourceId) {
        return new Provenance(SourceType.DOCUMENT, sourceId, 90, 1);
    }

    /** Whether any file at or under {@code root} holds {@code text} as bytes. */
    private static boolean anyFileContains(Path root, String text) throws IOException {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        if (!Files.exists(root)) {
            return false;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                byte[] haystack = Files.readAllBytes(path);
                outer:
                for (int i = 0; i + needle.length <= haystack.length; i++) {
                    for (int j = 0; j < needle.length; j++) {
                        if (haystack[i + j] != needle[j]) {
                            continue outer;
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** Stages two documents' facts and prose and builds the graph the tenant serves. */
    private static void stageAndBuild(GraphTenant tenant) throws IOException {
        GraphStagingStore staging = tenant.staging();
        int alice = staging.entityIdOf("Alice Quazzlethorpe");
        int bob = staging.entityIdOf("Bob Ordinary");
        staging.append(ExtractedTriple.alwaysValid(
            "Alice Quazzlethorpe", "knows", "Bob Ordinary", from(WITHDRAWN)));
        staging.appendEntityText(alice,
            "Alice Quazzlethorpe was treated for zymurgic fatigue", from(WITHDRAWN));
        staging.appendEntityText(bob, "Bob Ordinary lectures on thaumaturgy", from(77));
        staging.flush();

        assertThat(tenant.buildService().build().published()).isTrue();
    }

    @Test
    @DisplayName("erasing a person takes their name and prose out of the served directory")
    void anEntityErasureReachesTheServedIndexes(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config())) {
            stageAndBuild(tenant);
            assertThat(anyFileContains(directory, NAME_TERM))
                .as("the name starts out in the served indexes")
                .isTrue();

            tenant.erasureService()
                .submit(new ErasureScope.Entity(tenant.staging().entityIdOf("Alice "
                    + "Quazzlethorpe")));
            GraphErasureService.ErasureOutcome outcome = tenant.erasureService().compactNow();

            assertThat(outcome.requestsWithheld())
                .as("every artifact in the directory is now claimed by something")
                .isZero();
            assertThat(outcome.requestsSatisfied()).isEqualTo(1);
            assertThat(tenant.erasureService().outstandingCount()).isZero();
        }

        assertThat(anyFileContains(directory, NAME_TERM))
            .as("the name has to stop existing, not stop being served")
            .isFalse();
        assertThat(anyFileContains(directory, WITHDRAWN_TERM))
            .as("and so does the prose the snippets were copied out of")
            .isFalse();
        assertThat(anyFileContains(directory, KEPT_TERM))
            .as("nobody else's text moves")
            .isTrue();
    }

    @Test
    @DisplayName("withdrawing a document takes the prose it contributed, and only that")
    void aSourceErasureReachesTheDerivedProse(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config())) {
            stageAndBuild(tenant);

            tenant.erasureService()
                .submit(new ErasureScope.Source(SourceType.DOCUMENT, WITHDRAWN));
            GraphErasureService.ErasureOutcome outcome = tenant.erasureService().compactNow();

            assertThat(outcome.requestsWithheld()).isZero();
            assertThat(outcome.requestsSatisfied()).isEqualTo(1);
        }

        assertThat(anyFileContains(directory, WITHDRAWN_TERM))
            .as("the withdrawn document's prose is what the request covered")
            .isFalse();
        assertThat(anyFileContains(directory, NAME_TERM))
            .as("and the person it mentioned keeps their name: they did not ask to be forgotten")
            .isTrue();
        assertThat(anyFileContains(directory, KEPT_TERM)).isTrue();
    }

    @Test
    @DisplayName("the graph is still queryable after an erasure has rewritten it")
    void theStackReopensOverTheRewrittenDirectory(@TempDir Path root) throws IOException {
        Path directory = root.resolve("graph");
        try (GraphTenant tenant = GraphTenant.open(NamespaceId.SHARED, directory, config())) {
            stageAndBuild(tenant);

            tenant.erasureService()
                .submit(new ErasureScope.Entity(tenant.staging().entityIdOf("Alice "
                    + "Quazzlethorpe")));
            tenant.erasureService().compactNow();

            assertThat(tenant.holder().isPresent())
                .as("a node serving a graph before an erasure serves one after it")
                .isTrue();
        }
    }
}
