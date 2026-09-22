package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An obligation owed by a namespace nobody is querying still has to be discharged.
 *
 * <p>The sweep that enforces {@code excalibur.graph.erasure-max-latency-seconds} visited the open
 * namespaces and no others, and defended that on cost: reopening every tenant on disk would spend
 * the mapping budget the eviction had just reclaimed. The cost argument is right. What it bought
 * was not acceptable - a request accepted with a 202 and a durable record, whose namespace is then
 * evicted and never queried again, was never discharged. The node had promised deletion within a
 * bound and quietly did not honour it, which is the worst thing this mechanism can do.</p>
 *
 * <p>The way out is that the question and the work have different costs. Whether a namespace owes
 * anything, and whether what it owes is overdue, is in its erasure log: one small append-only
 * file, not the thirteen memory mappings of a served store. So the sweep reads the file for every
 * namespace on disk and reopens only the tenants that turn out to owe something due.</p>
 */
@DisplayName("Erasure obligations owed by an evicted namespace")
class EvictedNamespaceErasureSweepTest {

    /** A term of the erased person's name, as the segment files hold it. */
    private static final String NAME_TERM = "quazzlethorpe";

    /**
     * The deadline these tests run against.
     *
     * <p>An hour, not a second. The obligation is written into the log with an arrival time of
     * its own, so "overdue" is expressed by dating the request rather than by making the bound
     * small - which also keeps the background sweep's own timer (a tenth of the bound) well out
     * of the way of what the test drives by hand.</p>
     */
    private static final Map<String, String> BOUND_ONE_HOUR =
        Map.of("excalibur.graph.erasure-max-latency-seconds", "3600");

    /** Long enough ago that any bound a deployment could configure has passed. */
    private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");

    private static GraphTenants tenants(Path root, int maxOpen, Map<String, String> extra)
            throws IOException {
        Map<String, String> props = new HashMap<>(Map.of("excalibur.graph.ram-buffer-mb", "4.0"));
        props.putAll(extra);
        return new GraphTenants(root, new Config(props), MappingBudget.detect(), maxOpen);
    }

    private static Provenance from(long sourceId) {
        return new Provenance(SourceType.DOCUMENT, sourceId, 90, 1);
    }

    /** The erasure log of a namespace, as it sits on disk beside the graph it belongs to. */
    private static Path erasureLog(Path root, String namespace) {
        return root.resolve(namespace)
            .resolve("graph" + GraphTenant.STAGING_SUFFIX)
            .resolve(GraphTenant.ERASURE_LOG);
    }

    /** Stages one person's facts and prose into a tenant and builds the graph it serves. */
    private static int stageAndBuild(GraphTenant tenant) throws IOException {
        GraphStagingStore staging = tenant.staging();
        int alice = staging.entityIdOf("Alice Quazzlethorpe");
        staging.append(ExtractedTriple.alwaysValid(
            "Alice Quazzlethorpe", "knows", "Bob Ordinary", from(42)));
        staging.appendEntityText(alice, "Alice Quazzlethorpe lectures on zymurgy", from(42));
        staging.flush();
        assertThat(tenant.buildService().build().published()).isTrue();
        return alice;
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

    /**
     * Builds a namespace, evicts it, and leaves one erasure obligation in its log on disk.
     *
     * <p>The obligation is written straight into the log rather than submitted through the
     * tenant, so the test decides when it arrived. That is the only way to express "this has
     * been outstanding past the bound" without either waiting out the bound or shrinking it to
     * the point where the node's own sweep timer starts racing the assertions.</p>
     *
     * @param tenants     The registry
     * @param root        Its root, for reaching the log directly
     * @param id          The namespace
     * @param requestedAt When the obligation is to look as though it arrived
     */
    private static void owesAndIsEvicted(GraphTenants tenants, Path root, NamespaceId id,
                                         Instant requestedAt) throws IOException {
        int alice;
        try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(id)) {
            alice = stageAndBuild(lease.resource());
        }
        assertThat(tenants.invalidate(id)).isTrue();
        assertThat(tenants.borrowResident(id))
            .as("the namespace has to actually be closed for this test to mean anything")
            .isEmpty();

        try (ErasureLog log = ErasureLog.open(erasureLog(root, id.value()))) {
            log.submit(new ErasureScope.Entity(alice), requestedAt);
        }
    }

    @Test
    @DisplayName("an obligation whose namespace was evicted is still discharged by a sweep")
    void anEvictedNamespaceStillMeetsItsDeadline(@TempDir Path root) throws IOException {
        try (GraphTenants tenants = tenants(root, 4, BOUND_ONE_HOUR)) {
            NamespaceId acme = NamespaceId.of("acme");
            owesAndIsEvicted(tenants, root, acme, LONG_AGO);
            assertThat(ErasureLog.inspect(erasureLog(root, "acme")).count()).isEqualTo(1);

            assertThat(tenants.compactDueErasures())
                .as("the sweep has to find the namespace that owes something, not only the ones "
                    + "that happen to be resident")
                .isEqualTo(1);

            assertThat(ErasureLog.inspect(erasureLog(root, "acme")).count())
                .as("visiting is not discharging: the obligation must be gone from the log")
                .isZero();
            assertThat(anyFileContains(root.resolve("acme").resolve("graph"), NAME_TERM))
                .as("and the data the request named must be gone from the store")
                .isFalse();
        }
    }

    @Test
    @DisplayName("a namespace that owes nothing is not reopened to find that out")
    void aNamespaceThatOwesNothingIsNotReopened(@TempDir Path root) throws IOException {
        try (GraphTenants tenants = tenants(root, 4, BOUND_ONE_HOUR)) {
            NamespaceId quiet = NamespaceId.of("quiet");
            tenants.borrow(quiet).close();
            assertThat(tenants.invalidate(quiet)).isTrue();

            assertThat(tenants.compactDueErasures()).isZero();

            assertThat(tenants.openNamespaces())
                .as("reopening a tenant to learn it owes nothing spends exactly the mapping "
                    + "budget the eviction reclaimed")
                .containsExactly(NamespaceId.SHARED);
        }
    }

    @Test
    @DisplayName("an obligation inside the bound is left alone until its deadline arrives")
    void anObligationInsideTheBoundIsNotYetDue(@TempDir Path root) throws IOException {
        try (GraphTenants tenants = tenants(root, 4, BOUND_ONE_HOUR)) {
            NamespaceId acme = NamespaceId.of("acme");
            owesAndIsEvicted(tenants, root, acme, Instant.now());

            assertThat(tenants.compactDueErasures())
                .as("the bound is what makes this affordable: a fresh obligation waits for the "
                    + "namespace's next real request rather than forcing a reopen now")
                .isZero();
            assertThat(tenants.openNamespaces()).containsExactly(NamespaceId.SHARED);
            assertThat(ErasureLog.inspect(erasureLog(root, "acme")).count()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("one pass reopens a bounded number of tenants, oldest obligation first")
    void onePassIsBounded(@TempDir Path root) throws IOException {
        int owing = GraphTenants.MAX_REOPENS_PER_SWEEP + 2;
        try (GraphTenants tenants = tenants(root, owing + 2, BOUND_ONE_HOUR)) {
            for (int i = 0; i < owing; i++) {
                // Staggered, so "oldest obligation first" is a defined order rather than a tie.
                owesAndIsEvicted(tenants, root, NamespaceId.of("tenant-" + i),
                    LONG_AGO.plusSeconds(i));
            }

            assertThat(tenants.compactDueErasures())
                .as("a node with a thousand offboarded tenants must not reopen a thousand graphs "
                    + "in one pass")
                .isEqualTo(GraphTenants.MAX_REOPENS_PER_SWEEP);

            assertThat(tenants.compactDueErasures())
                .as("and the ones it could not reach are reached on the next pass; the ones it "
                    + "did reach are resident now and owe nothing, so the budget goes to the "
                    + "backlog rather than back to the same tenants")
                .isEqualTo(owing - GraphTenants.MAX_REOPENS_PER_SWEEP);

            for (int i = 0; i < owing; i++) {
                assertThat(ErasureLog.inspect(erasureLog(root, "tenant-" + i)).count())
                    .as("every obligation is eventually discharged")
                    .isZero();
            }
        }
    }

    @Test
    @DisplayName("an unreadable erasure log stops neither the sweep nor the other namespaces")
    void anUnreadableLogDoesNotStopTheSweep(@TempDir Path root) throws IOException {
        try (GraphTenants tenants = tenants(root, 6, BOUND_ONE_HOUR)) {
            owesAndIsEvicted(tenants, root, NamespaceId.of("broken"), LONG_AGO);
            owesAndIsEvicted(tenants, root, NamespaceId.of("healthy"), LONG_AGO);

            // A log written by a build that understood more than this one. Read as "nothing
            // outstanding" it would discharge the obligation by forgetting it.
            Files.write(erasureLog(root, "broken"),
                java.nio.ByteBuffer.allocate(ErasureLog.HEADER_BYTES)
                    .putLong(ErasureLog.MAGIC)
                    .putInt(ErasureLog.VERSION + 1)
                    .putInt(0)
                    .array());

            assertThat(tenants.compactDueErasures())
                .as("the healthy namespace is swept regardless of its neighbour")
                .isEqualTo(1);

            assertThat(ErasureLog.inspect(erasureLog(root, "healthy")).count()).isZero();
            assertThat(anyFileContains(root.resolve("broken").resolve("graph"), NAME_TERM))
                .as("nothing was concluded about the namespace whose log could not be read")
                .isTrue();
        }
    }
}
