package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.graph.erasure.ErasureLog;
import com.eonmux.excalibur.graph.erasure.ErasureScope;
import com.eonmux.excalibur.tenancy.MappingBudget;
import com.eonmux.excalibur.tenancy.NamespaceId;
import com.eonmux.excalibur.tenancy.NamespaceRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The erasure backlog of every namespace on a node, totalled for its stats.
 *
 * <p>Each namespace's obligations were visible one at a time, through the namespace inventory,
 * and nowhere as a whole: a node that had stopped keeping its erasure deadline across a thousand
 * tenants reported nothing a scraper could alert on. The total has to cover the namespaces nobody
 * has open as well as the ones that are, because an evicted namespace's obligations are exactly
 * the ones most likely to be forgotten - and a log that cannot be read must be reported as such,
 * never folded into "owes nothing".</p>
 */
@DisplayName("The graph erasure backlog")
class GraphErasureBacklogTest {

    /** An hour, so "overdue" is expressed by dating a request rather than by waiting. */
    private static final Map<String, String> BOUND_ONE_HOUR =
        Map.of("excalibur.graph.erasure-max-latency-seconds", "3600");

    /** Long enough ago that the one-hour bound has passed. */
    private static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");

    private static GraphTenants tenants(Path root) throws IOException {
        Map<String, String> props = new HashMap<>(Map.of("excalibur.graph.ram-buffer-mb", "4.0"));
        props.putAll(BOUND_ONE_HOUR);
        return new GraphTenants(root, new Config(props), MappingBudget.detect(), 4);
    }

    private static Path erasureLog(Path root, NamespaceId id) {
        return root.resolve(id.value())
            .resolve("graph" + GraphTenant.STAGING_SUFFIX)
            .resolve(GraphTenant.ERASURE_LOG);
    }

    /** Creates a namespace and closes it again, leaving its directories on disk. */
    private static void createAndEvict(GraphTenants tenants, NamespaceId id) {
        tenants.borrow(id).close();
        assertThat(tenants.invalidate(id)).isTrue();
    }

    @Test
    @DisplayName("a node that owes nothing reports nothing, not an age of zero")
    void nothingOwed(@TempDir Path root) throws IOException {
        try (GraphTenants tenants = tenants(root)) {
            GraphTenants.ErasureBacklog backlog = tenants.erasureBacklog();

            assertThat(backlog.namespacesOwing()).isZero();
            assertThat(backlog.obligations()).isZero();
            assertThat(backlog.overdueNamespaces()).isZero();
            assertThat(backlog.unreadableNamespaces()).isZero();
            assertThat(backlog.oldestRequestedAt()).isEmpty();
            assertThat(backlog.oldestAge(Instant.now())).isEmpty();
            assertThat(backlog.maxLatency()).isEqualTo(Duration.ofHours(1));
        }
    }

    @Test
    @DisplayName("totals the resident namespaces and the evicted ones alike")
    void totalsResidentAndEvicted(@TempDir Path root) throws IOException {
        NamespaceId open = NamespaceId.of("open-tenant");
        NamespaceId evicted = NamespaceId.of("evicted-tenant");
        try (GraphTenants tenants = tenants(root)) {
            createAndEvict(tenants, evicted);
            try (ErasureLog log = ErasureLog.open(erasureLog(root, evicted))) {
                log.submit(new ErasureScope.Entity(1), LONG_AGO);
                log.submit(new ErasureScope.Entity(2), LONG_AGO.plusSeconds(1));
            }
            try (NamespaceRegistry.Lease<GraphTenant> lease = tenants.borrow(open)) {
                lease.resource().erasureService().submit(new ErasureScope.Entity(3));
            }

            GraphTenants.ErasureBacklog backlog = tenants.erasureBacklog();

            assertThat(backlog.namespacesOwing()).isEqualTo(2);
            assertThat(backlog.obligations()).isEqualTo(3);
            assertThat(backlog.overdueNamespaces())
                .as("only the evicted namespace's obligation is past the hour")
                .isEqualTo(1);
            assertThat(backlog.unreadableNamespaces()).isZero();
            assertThat(backlog.oldestRequestedAt()).contains(LONG_AGO);
            assertThat(tenants.borrowResident(evicted))
                .as("reporting on a namespace must not reopen it")
                .isEmpty();
        }
    }

    @Test
    @DisplayName("a log that cannot be read is counted as unreadable, never as owing nothing")
    void unreadableIsNotNothing(@TempDir Path root) throws IOException {
        NamespaceId damaged = NamespaceId.of("damaged");
        try (GraphTenants tenants = tenants(root)) {
            createAndEvict(tenants, damaged);
            Path log = erasureLog(root, damaged);
            Files.createDirectories(log.getParent());
            Files.write(log, new byte[64]);

            GraphTenants.ErasureBacklog backlog = tenants.erasureBacklog();

            assertThat(backlog.unreadableNamespaces()).isEqualTo(1);
            assertThat(backlog.namespacesOwing()).isZero();
        }
    }

    @Test
    @DisplayName("an obligation dated in the future has waited no time, not a negative one")
    void ageNeverNegative() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        GraphTenants.ErasureBacklog backlog = new GraphTenants.ErasureBacklog(1, 1, 0, 0,
            Optional.of(now.plusSeconds(5)), Duration.ofHours(1));

        assertThat(backlog.oldestAge(now)).contains(Duration.ZERO);
        assertThat(new GraphTenants.ErasureBacklog(1, 1, 0, 0,
            Optional.of(now.minusSeconds(5)), Duration.ofHours(1)).oldestAge(now))
            .contains(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("refuses negative counts and missing fields")
    void validatesItsFigures() {
        Duration bound = Duration.ofHours(1);
        assertThatThrownBy(() -> new GraphTenants.ErasureBacklog(-1, 0, 0, 0, Optional.empty(),
            bound)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphTenants.ErasureBacklog(0, -1, 0, 0, Optional.empty(),
            bound)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphTenants.ErasureBacklog(0, 0, -1, 0, Optional.empty(),
            bound)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphTenants.ErasureBacklog(0, 0, 0, -1, Optional.empty(),
            bound)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GraphTenants.ErasureBacklog(0, 0, 0, 0, null, bound))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GraphTenants.ErasureBacklog(0, 0, 0, 0, Optional.empty(),
            null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("reports the open bound it was configured with")
    void reportsTheOpenBound(@TempDir Path root) throws IOException {
        try (GraphTenants tenants = tenants(root)) {
            assertThat(tenants.maxOpenNamespaces()).isEqualTo(4);
        }
    }
}
