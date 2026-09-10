package excalibur.ingest.registry;

import excalibur.ingest.source.SourceDescriptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a node remembers about the sources it was asked to crawl.
 *
 * <p>Registration is the one ingest operation whose effect is meant to outlast the request: a
 * source is crawled once and then re-crawled on a schedule for as long as it stays registered.
 * Held only in memory that promise ends at the next restart, and it ends silently - the corpus is
 * still there and still queryable, so nothing about the node looks wrong until someone notices
 * that a directory changed weeks ago and the index never followed.</p>
 *
 * <p>Source ids and roots are caller-supplied text. The delimiters this format uses occur inside
 * both - a Windows path carries backslashes, a query string carries {@code =} - so the round trip
 * is tested with them rather than with well-behaved names.</p>
 */
class SourceCatalogTest {

    @Test
    @DisplayName("a recorded source is there for the next process to find")
    void aRecordedSourceSurvivesTheProcess(@TempDir Path dataDir) throws IOException {
        SourceCatalog catalog = SourceCatalog.open(dataDir);
        catalog.record(new SourceDescriptor("filesystem", "my-docs",
            Map.of("root_path", "/srv/corpus", "included_extensions", ".txt,.md")));

        SourceCatalog reopened = SourceCatalog.open(dataDir);

        assertThat(reopened.all()).containsExactly(new SourceDescriptor("filesystem", "my-docs",
            Map.of("root_path", "/srv/corpus", "included_extensions", ".txt,.md")));
    }

    @Test
    @DisplayName("a forgotten source is not restored on the next start")
    void aForgottenSourceStaysForgotten(@TempDir Path dataDir) throws IOException {
        SourceCatalog catalog = SourceCatalog.open(dataDir);
        catalog.record(new SourceDescriptor("filesystem", "keep", Map.of("root_path", "/a")));
        catalog.record(new SourceDescriptor("filesystem", "drop", Map.of("root_path", "/b")));

        assertThat(catalog.forget("drop")).isTrue();

        assertThat(SourceCatalog.open(dataDir).all())
            .extracting(SourceDescriptor::sourceId)
            .containsExactly("keep");
    }

    @Test
    @DisplayName("forgetting a source nobody registered changes nothing")
    void forgettingAnAbsentSourceIsNotAnError(@TempDir Path dataDir) throws IOException {
        SourceCatalog catalog = SourceCatalog.open(dataDir);
        catalog.record(new SourceDescriptor("http", "site", Map.of("base_url", "https://example.com")));

        assertThat(catalog.forget("never-registered")).isFalse();
        assertThat(catalog.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("re-registering the same id replaces what was recorded for it")
    void reRegistrationReplacesTheRecord(@TempDir Path dataDir) throws IOException {
        SourceCatalog catalog = SourceCatalog.open(dataDir);
        catalog.record(new SourceDescriptor("filesystem", "docs", Map.of("root_path", "/old")));
        catalog.record(new SourceDescriptor("filesystem", "docs", Map.of("root_path", "/new")));

        assertThat(SourceCatalog.open(dataDir).all())
            .singleElement()
            .extracting(d -> d.config().get("root_path"))
            .isEqualTo("/new");
    }

    @Test
    @DisplayName("delimiters inside a root path or a URL survive the round trip")
    void delimitersInConfigurationRoundTrip(@TempDir Path dataDir) throws IOException {
        SourceDescriptor awkward = new SourceDescriptor("http", "feed|1",
            Map.of("base_url", "https://example.com/x?a=1&b=2", "note", "C:\\share\\docs"));

        SourceCatalog.open(dataDir).record(awkward);

        assertThat(SourceCatalog.open(dataDir).all()).containsExactly(awkward);
    }

    @Test
    @DisplayName("a corrupt line costs one source, not the whole catalog")
    void aCorruptLineIsSkipped(@TempDir Path dataDir) throws IOException {
        SourceCatalog.open(dataDir).record(
            new SourceDescriptor("filesystem", "good", Map.of("root_path", "/a")));
        Path file = dataDir.resolve(SourceCatalog.FILE);
        Files.write(file, List.of(Files.readAllLines(file).get(0), "not-a-record"),
            StandardCharsets.UTF_8);

        assertThat(SourceCatalog.open(dataDir).all())
            .extracting(SourceDescriptor::sourceId)
            .containsExactly("good");
    }

    @Test
    @DisplayName("a catalog that cannot be read is not reported as an empty one")
    void anUnreadableCatalogIsFatal(@TempDir Path dataDir) throws IOException {
        // A directory where the file should be: readable as an entry, not as a record stream.
        Files.createDirectory(dataDir.resolve(SourceCatalog.FILE));

        assertThatThrownBy(() -> SourceCatalog.open(dataDir))
            .isInstanceOf(SourceCatalog.CatalogLoadException.class);
    }
}
