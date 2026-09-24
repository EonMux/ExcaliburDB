package com.eonmux.excalibur.api.server;

import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.security.TestKeystore;
import com.eonmux.excalibur.api.security.TlsConfig;
import com.eonmux.excalibur.core.datadir.DataDirectoryLock;
import com.eonmux.excalibur.query.pipeline.QueryPipeline;
import com.eonmux.excalibur.shard.EpochTickScheduler;
import com.eonmux.excalibur.shard.IndexOrchestrator;
import com.eonmux.excalibur.store.SegmentCatalog;
import com.eonmux.excalibur.store.StorageEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Runs the container image's health check, {@code docker/excalibur-healthcheck}, against a real
 * listener.
 *
 * <p>The script is what Docker runs every fifteen seconds, and what it has to get right is
 * exactly what a fixed {@code curl http://127.0.0.1:8080} got wrong: the scheme when TLS is on,
 * the port however it was configured, and the address when the node is bound to one interface.
 * It learns all three from the endpoint the node advertises in its lock file, so these tests
 * advertise the way the node does - from the bound socket - and run the script as Docker would,
 * with only the environment to go on.</p>
 */
@DisplayName("excalibur-healthcheck")
class HealthcheckScriptTest {

    private static final String PASSWORD = "keystore-password";

    private static Path script;

    @TempDir
    Path dataDirectory;

    private IndexOrchestrator indexOrchestrator;
    private StorageEngine storageEngine;
    private QueryPipeline queryPipeline;
    private ExcaliburDBServer server;
    private DataDirectoryLock lock;

    @BeforeAll
    static void locateScript() throws Exception {
        script = repositoryFile("docker/excalibur-healthcheck");
        assumeTrue(commandAvailable("sh") && commandAvailable("curl"),
            "the health check needs sh and curl, which the image installs");
    }

    @BeforeEach
    void setUp() {
        SegmentCatalog segmentCatalog = new SegmentCatalog();
        indexOrchestrator = mock(IndexOrchestrator.class);
        storageEngine = mock(StorageEngine.class);
        queryPipeline = mock(QueryPipeline.class);
        var epochTickScheduler = mock(EpochTickScheduler.class);
        when(indexOrchestrator.getVisibilityTracker())
            .thenReturn(new com.eonmux.excalibur.shard.VisibilityTracker(segmentCatalog));
        when(indexOrchestrator.getEpochTickScheduler()).thenReturn(epochTickScheduler);
        when(epochTickScheduler.getTickIntervalMs()).thenReturn(100L);
        when(storageEngine.getCatalog()).thenReturn(segmentCatalog);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.stop(0);
        }
        if (lock != null) {
            lock.close();
        }
    }

    @Test
    @DisplayName("passes against a TLS listener on a port nobody told it about")
    void tlsOnAnEphemeralPort(@TempDir Path keys) throws Exception {
        Path keystore = TestKeystore.create(keys, "server.p12", "PKCS12", PASSWORD, "server");
        start(ApiSecurityConfig.builder().authToken("token")
            .tls(TlsConfig.fromProperties(Map.of(
                TlsConfig.KEY_KEYSTORE, keystore.toString(),
                TlsConfig.KEY_KEYSTORE_PASSWORD, PASSWORD)))
            .build());
        advertise();

        assertThat(server.livenessProbeUrl()).startsWith("https://");
        assertThat(run().exitCode()).isZero();
    }

    @Test
    @DisplayName("passes against a plaintext listener")
    void plaintext() throws Exception {
        start(ApiSecurityConfig.builder().build());
        advertise();

        assertThat(run().exitCode()).isZero();
    }

    @Test
    @DisplayName("probes liveness rather than readiness, so a drained node is not reported dead")
    void probesLiveness() throws Exception {
        start(ApiSecurityConfig.builder().build());
        advertise();
        assertThat(lock.holder().healthUrl()).hasValueSatisfying(url -> assertThat(url)
            .endsWith("/health").doesNotContain("/ready"));

        assertThat(run().exitCode()).isZero();
    }

    @Test
    @DisplayName("fails once the listener is gone")
    void failsWhenNothingListens() throws Exception {
        start(ApiSecurityConfig.builder().build());
        advertise();
        server.stop(0);
        server = null;

        assertThat(run().exitCode()).isNotZero();
    }

    @Test
    @DisplayName("fails, and says why, while the node has not yet bound its socket")
    void failsBeforeTheEndpointIsAdvertised() throws Exception {
        lock = DataDirectoryLock.acquire(dataDirectory, "excalibur node");

        Result result = run();
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("health-url");
    }

    @Test
    @DisplayName("fails, and says why, where no node has ever run")
    void failsWithoutALockFile() throws Exception {
        Result result = run();
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains(DataDirectoryLock.FILE_NAME);
    }

    private void start(ApiSecurityConfig security) throws IOException {
        server = new ExcaliburDBServer(0, indexOrchestrator, storageEngine, queryPipeline);
        server.withSecurity(security);
        server.start();
    }

    private void advertise() throws IOException {
        lock = DataDirectoryLock.acquire(dataDirectory, "excalibur node");
        lock.advertise(server.baseUrl(), server.livenessProbeUrl());
    }

    private Result run() throws Exception {
        ProcessBuilder builder = new ProcessBuilder("sh", script.toString())
            .redirectErrorStream(true);
        builder.environment().put("EXCALIBUR_DATA_DIR", dataDirectory.toString());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Result(process.exitValue(), output);
    }

    private record Result(int exitCode, String output) {
    }

    private static boolean commandAvailable(String command) throws InterruptedException {
        try {
            Process probe = new ProcessBuilder("sh", "-c", "command -v " + command)
                .redirectErrorStream(true).start();
            probe.getInputStream().readAllBytes();
            return probe.waitFor() == 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path repositoryFile(String name) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new AssertionError(name + " not found above " + Path.of("").toAbsolutePath());
    }
}
