package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.api.endpoint.InMemorySourceRegistry;
import com.eonmux.excalibur.api.endpoint.SourceFactory;
import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.security.IngestTargetValidator;
import com.eonmux.excalibur.ingest.registry.SourceCatalog;
import com.eonmux.excalibur.ingest.source.DocumentLimits;
import com.eonmux.excalibur.ingest.source.FilesystemSource;
import com.eonmux.excalibur.ingest.source.SourceDescriptor;
import com.eonmux.excalibur.shard.worker.SegmentReindexScheduler;
import com.eonmux.excalibur.tenancy.NamespaceId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Bringing back the sources a previous run was crawling.
 *
 * <p>The catalog holds the registration requests, not the crawlers they produced, so a restart
 * replays each one through the same allow-lists a fresh {@code POST /v1/ingest} would meet. That
 * is the whole point of storing what the caller asked for rather than what it resolved to: an
 * operator who narrows {@code excalibur.ingest.allowed-roots} has narrowed it, and a root that was
 * allowed last week must not walk back in through a file the node wrote itself.</p>
 *
 * <p>A refused or unbuildable record is skipped, not deleted. The allow-list may be wrong rather
 * than the source, and a node that quietly dropped the registration would leave nothing to fix.</p>
 */
@DisplayName("Source restoration")
class SourceRestorationTest {

    private static SourceFactory factoryAllowing(Path... roots) {
        ApiSecurityConfig.Builder security = ApiSecurityConfig.builder();
        for (Path root : roots) {
            security.allowIngestRoot(root);
        }
        return new SourceFactory(new IngestTargetValidator(security.build()));
    }

    private static InMemorySourceRegistry registryWith(SegmentReindexScheduler scheduler) {
        InMemorySourceRegistry registry = new InMemorySourceRegistry();
        registry.setReindexScheduler(scheduler, 3_600_000L);
        return registry;
    }

    @Test
    @DisplayName("a recorded source is crawling again after a restart")
    void anAllowedSourceIsRestored(@TempDir Path tempDir) throws IOException {
        Path corpus = Files.createDirectory(tempDir.resolve("corpus"));
        SourceCatalog catalog = SourceCatalog.open(tempDir);
        catalog.record(new SourceDescriptor("filesystem", "my-docs",
            Map.of("root_path", corpus.toString())));
        SegmentReindexScheduler scheduler = mock(SegmentReindexScheduler.class);
        InMemorySourceRegistry registry = registryWith(scheduler);

        int restored = SourceRestoration.restore(catalog, factoryAllowing(corpus), registry);

        assertThat(restored).isEqualTo(1);
        assertThat(registry.isRegistered("my-docs")).isTrue();
        verify(scheduler).registerSource("my-docs", 3_600_000L, null);
    }

    @Test
    @DisplayName("a recorded file size past what can be indexed is restored at the limit")
    void anOversizedFileBoundIsLowered(@TempDir Path tempDir) throws IOException {
        // The record says what the caller asked for when a larger bound was accepted. Refusing
        // it now would stop the crawl altogether, over a setting that never let a document past
        // the engine's limit: a larger file was read only to be discarded.
        Path corpus = Files.createDirectory(tempDir.resolve("corpus"));
        SourceCatalog catalog = SourceCatalog.open(tempDir);
        catalog.record(new SourceDescriptor("filesystem", "big-files",
            Map.of("root_path", corpus.toString(), "max_file_bytes", "2147483648")));
        InMemorySourceRegistry registry = registryWith(mock(SegmentReindexScheduler.class));

        int restored = SourceRestoration.restore(catalog, factoryAllowing(corpus), registry);

        assertThat(restored).isEqualTo(1);
        assertThat(((FilesystemSource) registry.get("big-files")).getMaxFileSize())
            .isEqualTo(DocumentLimits.MAX_DOCUMENT_BYTES);
    }

    @Test
    @DisplayName("a root the allow-list no longer covers is not crawled again")
    void aNarrowedAllowListRefusesTheSource(@TempDir Path tempDir) throws IOException {
        Path stillAllowed = Files.createDirectory(tempDir.resolve("allowed"));
        Path noLongerAllowed = Files.createDirectory(tempDir.resolve("revoked"));
        SourceCatalog catalog = SourceCatalog.open(tempDir);
        catalog.record(new SourceDescriptor("filesystem", "revoked",
            Map.of("root_path", noLongerAllowed.toString())));
        SegmentReindexScheduler scheduler = mock(SegmentReindexScheduler.class);
        InMemorySourceRegistry registry = registryWith(scheduler);

        int restored = SourceRestoration.restore(catalog, factoryAllowing(stillAllowed), registry);

        assertThat(restored).isZero();
        assertThat(registry.isRegistered("revoked"))
            .as("a root the operator revoked must not come back through the node's own file")
            .isFalse();
        verify(scheduler, never()).registerSource(anyString(), anyLong());
        verify(scheduler, never()).registerSource(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("a refused source is kept on file, so fixing the allow-list brings it back")
    void aRefusedSourceIsNotDeleted(@TempDir Path tempDir) throws IOException {
        Path revoked = Files.createDirectory(tempDir.resolve("revoked"));
        SourceCatalog catalog = SourceCatalog.open(tempDir);
        catalog.record(new SourceDescriptor("filesystem", "revoked",
            Map.of("root_path", revoked.toString())));

        SourceRestoration.restore(catalog, factoryAllowing(), registryWith(
            mock(SegmentReindexScheduler.class)));

        assertThat(SourceCatalog.open(tempDir).all())
            .as("the allow-list may be the thing that is wrong; deleting the record leaves "
                + "nothing to correct")
            .hasSize(1);
    }

    @Test
    @DisplayName("one unbuildable record does not cost the others")
    void oneBadRecordDoesNotStopTheRest(@TempDir Path tempDir) throws IOException {
        Path corpus = Files.createDirectory(tempDir.resolve("corpus"));
        SourceCatalog catalog = SourceCatalog.open(tempDir);
        catalog.record(new SourceDescriptor("s3", "from-a-newer-build",
            Map.of("bucket", "somewhere")));
        catalog.record(new SourceDescriptor("filesystem", "my-docs",
            Map.of("root_path", corpus.toString())));
        InMemorySourceRegistry registry = registryWith(mock(SegmentReindexScheduler.class));

        int restored = SourceRestoration.restore(catalog, factoryAllowing(corpus), registry);

        assertThat(restored).isEqualTo(1);
        assertThat(registry.isRegistered("my-docs")).isTrue();
        assertThat(registry.isRegistered("from-a-newer-build")).isFalse();
    }

    @Test
    @DisplayName("a restored source is crawling again in the namespace it was registered in")
    void aRestoredSourceKeepsItsNamespace(@TempDir Path tempDir) throws IOException {
        Path corpus = Files.createDirectory(tempDir.resolve("corpus"));
        SourceCatalog catalog = SourceCatalog.open(tempDir);
        catalog.record(new SourceDescriptor("filesystem", "acme-docs",
            Map.of("root_path", corpus.toString()), NamespaceId.of("acme")));
        InMemorySourceRegistry registry = registryWith(mock(SegmentReindexScheduler.class));

        SourceRestoration.restore(catalog, factoryAllowing(corpus), registry);

        assertThat(registry.namespaceOf("acme-docs"))
            .as("the re-crawl this restore schedules must stage where the first crawl staged; "
                + "dropping the namespace would move one tenant's facts into the shared index "
                + "at the first restart after they were registered")
            .contains(NamespaceId.of("acme"));
    }

    @Test
    @DisplayName("a source recorded before namespaces existed is restored into the shared one")
    void aSourceWithNoRecordedNamespaceIsShared(@TempDir Path tempDir) throws IOException {
        Path corpus = Files.createDirectory(tempDir.resolve("corpus"));
        SourceCatalog catalog = SourceCatalog.open(tempDir);
        catalog.record(new SourceDescriptor("filesystem", "my-docs",
            Map.of("root_path", corpus.toString())));
        InMemorySourceRegistry registry = registryWith(mock(SegmentReindexScheduler.class));

        SourceRestoration.restore(catalog, factoryAllowing(corpus), registry);

        assertThat(registry.namespaceOf("my-docs")).contains(NamespaceId.SHARED);
    }

    @Test
    @DisplayName("an empty catalog restores nothing and reports nothing")
    void anEmptyCatalogIsNotAnEvent(@TempDir Path tempDir) {
        int restored = SourceRestoration.restore(SourceCatalog.open(tempDir), factoryAllowing(),
            registryWith(mock(SegmentReindexScheduler.class)));

        assertThat(restored).isZero();
    }
}
