package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.config.Config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That {@code excalibur.graph.path} names the directory the node's graphs actually live under.
 *
 * <p>It did not. The tenant root was taken as the <em>parent</em> of the configured path, so the
 * value's last segment was discarded: {@code /mnt/graphs/alpha} and {@code /mnt/graphs/beta}
 * both served {@code /mnt/graphs/shared/graph}, and two nodes pointed at what read as separate
 * stores shared one. A single-segment relative value lost the whole path.</p>
 */
@DisplayName("Graph tenant root")
class GraphTenantRootTest {

    @Test
    @DisplayName("is the data directory when nothing is configured")
    void defaultsToTheDataDirectory(@TempDir Path dataDir) {
        assertThat(GraphRagFactory.tenantRootFor(config(Map.of()), dataDir)).isEqualTo(dataDir);
    }

    @Test
    @DisplayName("is the configured path itself, not its parent")
    void isTheConfiguredPath(@TempDir Path dataDir) {
        Path configured = dataDir.resolve("graphs").resolve("alpha");
        Config config = config(Map.of("excalibur.graph.path", configured.toString()));

        assertThat(GraphRagFactory.tenantRootFor(config, dataDir)).isEqualTo(configured);
    }

    @Test
    @DisplayName("keeps two configured paths that share a parent apart")
    void keepsSiblingsApart(@TempDir Path dataDir) {
        Path alpha = dataDir.resolve("graphs").resolve("alpha");
        Path beta = dataDir.resolve("graphs").resolve("beta");

        assertThat(GraphRagFactory.tenantRootFor(
                config(Map.of("excalibur.graph.path", alpha.toString())), dataDir))
            .isNotEqualTo(GraphRagFactory.tenantRootFor(
                config(Map.of("excalibur.graph.path", beta.toString())), dataDir));
    }

    @Test
    @DisplayName("keeps a relative single-segment path rather than dropping it")
    void keepsARelativeSegment(@TempDir Path dataDir) {
        assertThat(GraphRagFactory.tenantRootFor(
                config(Map.of("excalibur.graph.path", "graphs")), dataDir))
            .isEqualTo(Path.of("graphs"));
    }

    @Test
    @DisplayName("treats a blank value as unconfigured")
    void blankIsUnconfigured(@TempDir Path dataDir) {
        assertThat(GraphRagFactory.tenantRootFor(
                config(Map.of("excalibur.graph.path", "   ")), dataDir))
            .isEqualTo(dataDir);
    }

    private Config config(Map<String, String> props) {
        return new Config(props);
    }
}
