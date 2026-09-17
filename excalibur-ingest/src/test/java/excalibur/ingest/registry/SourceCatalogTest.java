package excalibur.ingest.registry;

import excalibur.ingest.source.SourceDescriptor;

import io.excalibur.tenancy.NamespaceId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    /** The last line of a whole catalog, less the count it ends with. */
    private static final String END_MARKER = "\\#end|";

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
        List<String> lines = new ArrayList<>(readRecords(file));
        lines.add("not-a-record");
        lines.add(END_MARKER + (lines.size() - 1));
        Files.write(file, lines, StandardCharsets.UTF_8);

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

    @Test
    @DisplayName("a catalog cut short by a crash is not read as a shorter catalog")
    void aTruncatedCatalogIsNotReadAsAShorterOne(@TempDir Path dataDir) throws IOException {
        SourceCatalog catalog = SourceCatalog.open(dataDir);
        catalog.record(new SourceDescriptor("filesystem", "one", Map.of("root_path", "/a")));
        catalog.record(new SourceDescriptor("filesystem", "two", Map.of("root_path", "/b")));
        catalog.record(new SourceDescriptor("filesystem", "three", Map.of("root_path", "/c")));

        // What a host crash leaves when the rename reached the disk and the tail of the contents
        // did not: whole records, correctly named file, and one source simply gone.
        Path file = dataDir.resolve(SourceCatalog.FILE);
        List<String> whole = Files.readAllLines(file);
        Files.write(file, whole.subList(0, whole.size() - 2), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> SourceCatalog.open(dataDir))
            .isInstanceOf(SourceCatalog.CatalogLoadException.class);
    }

    @Test
    @DisplayName("a rewrite is forced before it is renamed, and the directory after")
    void theRewriteIsForcedBeforeItIsPublished(@TempDir Path dataDir) throws IOException {
        // A crash cannot be staged, so what is pinned here is the order the three steps happen
        // in. Renaming an unforced replacement publishes a correctly named catalog whose contents
        // may never have left the page cache, and forcing the file without forcing the directory
        // leaves the rename itself free to be lost.
        List<String> steps = new ArrayList<>();
        Path file = dataDir.resolve(SourceCatalog.FILE);
        SourceCatalog catalog = new SourceCatalog(file, new RecordingWriter(steps));

        catalog.record(new SourceDescriptor("filesystem", "docs", Map.of("root_path", "/a")));

        assertThat(steps).containsExactly(
            "force " + file.getFileName() + ".tmp",
            "publish",
            "forceDirectory " + dataDir.getFileName());
    }

    /**
     * Performs each step for real and notes that it happened, so the order is observable.
     */
    private record RecordingWriter(List<String> steps) implements SourceCatalog.CatalogWriter {

        private static final SourceCatalog.CatalogWriter REAL = SourceCatalog.fileSystemWriter();

        @Override
        public void force(Path path) throws IOException {
            steps.add("force " + path.getFileName());
            REAL.force(path);
        }

        @Override
        public void publish(Path replacement, Path target) throws IOException {
            steps.add("publish");
            REAL.publish(replacement, target);
        }

        @Override
        public void forceDirectory(Path directory) {
            steps.add("forceDirectory " + directory.getFileName());
            REAL.forceDirectory(directory);
        }
    }

    @Test
    @DisplayName("a catalog whose end marker disagrees with what follows it is not loaded")
    void aCountThatDisagreesIsFatal(@TempDir Path dataDir) throws IOException {
        SourceCatalog catalog = SourceCatalog.open(dataDir);
        catalog.record(new SourceDescriptor("filesystem", "one", Map.of("root_path", "/a")));
        catalog.record(new SourceDescriptor("filesystem", "two", Map.of("root_path", "/b")));

        Path file = dataDir.resolve(SourceCatalog.FILE);
        List<String> lines = new ArrayList<>(readRecords(file));
        lines.add(END_MARKER + lines.size());
        Files.write(file, lines, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> SourceCatalog.open(dataDir))
            .isInstanceOf(SourceCatalog.CatalogLoadException.class)
            .hasMessageContaining("declares 3 record(s) and carries 2");
    }

    @Test
    @DisplayName("a source whose type reads like the end marker cannot forge one")
    void aSourceCannotForgeTheEndMarker(@TempDir Path dataDir) throws IOException {
        // The marker is a line that escaping can never produce, so a caller cannot register a
        // source that cuts the catalog short for every source recorded after it.
        SourceDescriptor impostor = new SourceDescriptor("\\#end", "1", Map.of("root_path", "/a"));
        SourceCatalog catalog = SourceCatalog.open(dataDir);
        catalog.record(impostor);
        catalog.record(new SourceDescriptor("filesystem", "after", Map.of("root_path", "/b")));

        assertThat(SourceCatalog.open(dataDir).all())
            .containsExactlyInAnyOrder(impostor,
                new SourceDescriptor("filesystem", "after", Map.of("root_path", "/b")));
    }

    @Test
    @DisplayName("a catalog written by a newer build is refused rather than reinterpreted")
    void aCatalogFromANewerBuildIsRefused(@TempDir Path dataDir) throws IOException {
        SourceCatalog.open(dataDir).record(
            new SourceDescriptor("filesystem", "docs", Map.of("root_path", "/a")));

        Path file = dataDir.resolve(SourceCatalog.FILE);
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        lines.set(0, "\\#source-catalog|99");
        Files.write(file, lines, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> SourceCatalog.open(dataDir))
            .as("a format this build does not know is not one to guess at")
            .isInstanceOf(SourceCatalog.CatalogLoadException.class)
            .hasMessageContaining("99");
    }

    @Test
    @DisplayName("a catalog from a build that wrote no version is still openable")
    void aCatalogWithoutAVersionStillOpens(@TempDir Path dataDir) throws IOException {
        // What the build that introduced the end marker left behind: records, marker, no header.
        Path file = dataDir.resolve(SourceCatalog.FILE);
        Files.write(file, List.of("filesystem|legacy|root_path=/a", "\\#end|1"),
            StandardCharsets.UTF_8);

        assertThat(SourceCatalog.open(dataDir).all())
            .extracting(SourceDescriptor::sourceId)
            .containsExactly("legacy");
    }

    @Test
    @DisplayName("the namespace a source was registered in survives the process")
    void aNamespaceSurvivesTheProcess(@TempDir Path dataDir) throws IOException {
        SourceCatalog.open(dataDir).record(new SourceDescriptor("filesystem", "acme-docs",
            Map.of("root_path", "/srv/acme"), NamespaceId.of("acme")));

        assertThat(SourceCatalog.open(dataDir).all())
            .as("a re-crawl after a restart has to stage where the first crawl staged; the "
                + "caller who named the namespace is long gone by then")
            .extracting(SourceDescriptor::namespace)
            .containsExactly(NamespaceId.of("acme"));
    }

    @Test
    @DisplayName("a record written before the format carried a namespace loads as the shared one")
    void aRecordWithoutANamespaceIsShared(@TempDir Path dataDir) throws IOException {
        // Version 1: type, id and the configuration pairs, with no namespace between them. The
        // build that wrote this staged every document into the shared namespace, so that is
        // what the record means - and refusing it would leave a node unable to start on the
        // catalog it was running on before the upgrade.
        Path file = dataDir.resolve(SourceCatalog.FILE);
        Files.write(file, List.of("\\#source-catalog|1", "filesystem|legacy|root_path=/a",
            "\\#end|1"), StandardCharsets.UTF_8);

        assertThat(SourceCatalog.open(dataDir).all())
            .containsExactly(new SourceDescriptor("filesystem", "legacy",
                Map.of("root_path", "/a"), NamespaceId.SHARED));
    }

    @Test
    @DisplayName("a record from before the version header also loads as the shared namespace")
    void aHeaderlessRecordIsShared(@TempDir Path dataDir) throws IOException {
        Path file = dataDir.resolve(SourceCatalog.FILE);
        Files.write(file, List.of("filesystem|legacy|root_path=/a", "\\#end|1"),
            StandardCharsets.UTF_8);

        assertThat(SourceCatalog.open(dataDir).all())
            .extracting(SourceDescriptor::namespace)
            .containsExactly(NamespaceId.SHARED);
    }

    @Test
    @DisplayName("an old record is rewritten with its namespace at the next registration")
    void anOldCatalogIsRewrittenWithNamespaces(@TempDir Path dataDir) throws IOException {
        Path file = dataDir.resolve(SourceCatalog.FILE);
        Files.write(file, List.of("\\#source-catalog|1", "filesystem|legacy|root_path=/a",
            "\\#end|1"), StandardCharsets.UTF_8);

        SourceCatalog catalog = SourceCatalog.open(dataDir);
        catalog.record(new SourceDescriptor("filesystem", "acme-docs",
            Map.of("root_path", "/srv/acme"), NamespaceId.of("acme")));

        assertThat(Files.readAllLines(file).get(0))
            .as("the rewrite states the format it is in, or the next start reads three fields "
                + "as two")
            .isEqualTo("\\#source-catalog|" + SourceCatalog.FORMAT_VERSION);
        assertThat(SourceCatalog.open(dataDir).all())
            .extracting(SourceDescriptor::sourceId, SourceDescriptor::namespace)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("legacy", NamespaceId.SHARED),
                org.assertj.core.groups.Tuple.tuple("acme-docs", NamespaceId.of("acme")));
    }

    @Test
    @DisplayName("a record naming a namespace that is not one costs that source, not the catalog")
    void aCorruptNamespaceCostsOneSource(@TempDir Path dataDir) throws IOException {
        Path file = dataDir.resolve(SourceCatalog.FILE);
        Files.write(file, List.of("\\#source-catalog|2",
            "filesystem|bad|Not A Namespace|root_path=/a",
            "filesystem|good|acme|root_path=/b", "\\#end|2"), StandardCharsets.UTF_8);

        assertThat(SourceCatalog.open(dataDir).all())
            .as("a namespace that did not survive names no tenant to stage into, and staging "
                + "that source anywhere else would put one tenant's documents in another's index")
            .extracting(SourceDescriptor::sourceId)
            .containsExactly("good");
    }

    /**
     * Returns a catalog's header and record lines, dropping the end marker so a test can rewrite
     * the file. The count a test then writes into a new marker counts the records alone, which is
     * one less than the lines returned here.
     */
    private static List<String> readRecords(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        return lines.subList(0, lines.size() - 1);
    }
}
