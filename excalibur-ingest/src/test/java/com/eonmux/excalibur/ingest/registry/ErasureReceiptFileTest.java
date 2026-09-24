package com.eonmux.excalibur.ingest.registry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The receipts a document erasure leaves, so its caller can ask later where it stands.
 */
@DisplayName("Erasure receipt file")
class ErasureReceiptFileTest {

    private static ErasureReceiptFile.Receipt receipt(String id) {
        return new ErasureReceiptFile.Receipt(id, 1_000L, "docs", "document", 2, 0, 1, 0, false,
            true, List.of(new ErasureReceiptFile.GraphRequests("shared", List.of(4L, 5L))));
    }

    @Test
    @DisplayName("a recorded receipt is found, and found again after the file is reopened")
    void survivesReopen(@TempDir Path dataDir) throws IOException {
        ErasureReceiptFile.Receipt written = new ErasureReceiptFile.Receipt("r-1", 1_234L,
            "we|ird\\source", "source", 3, 1, 2, 7, true, false,
            List.of(new ErasureReceiptFile.GraphRequests("acme", List.of(5L, 6L, 7L, 10L, 12L,
                    13L)),
                new ErasureReceiptFile.GraphRequests("shared", List.of(1L))));
        ErasureReceiptFile.open(dataDir).record(written);

        ErasureReceiptFile reopened = ErasureReceiptFile.open(dataDir);

        assertThat(reopened.find("r-1")).contains(written);
        assertThat(reopened.find("r-2")).isEmpty();
        assertThat(reopened.find(null)).isEmpty();
        assertThat(Files.readString(dataDir.resolve(ErasureReceiptFile.FILE)))
            .as("consecutive ids are kept as ranges")
            .contains("acme=5+3,10+1,12+2;shared=1+1");
    }

    @Test
    @DisplayName("a receipt with nothing submitted to a graph round-trips")
    void noGraph(@TempDir Path dataDir) throws IOException {
        ErasureReceiptFile.Receipt written = new ErasureReceiptFile.Receipt("r-0", 0L, "docs",
            "document", 0, 0, 4, 0, false, false, List.of());
        ErasureReceiptFile.open(dataDir).record(written);

        assertThat(ErasureReceiptFile.open(dataDir).find("r-0")).contains(written);
    }

    @Test
    @DisplayName("only the most recent receipts are kept")
    void retentionIsBounded(@TempDir Path dataDir) throws IOException {
        ErasureReceiptFile file = ErasureReceiptFile.open(dataDir);
        for (int i = 0; i <= ErasureReceiptFile.RETAINED_RECEIPTS; i++) {
            file.record(receipt("r-" + i));
        }

        ErasureReceiptFile reopened = ErasureReceiptFile.open(dataDir);
        assertThat(file.size()).isEqualTo(ErasureReceiptFile.RETAINED_RECEIPTS);
        assertThat(reopened.size()).isEqualTo(ErasureReceiptFile.RETAINED_RECEIPTS);
        assertThat(reopened.find("r-0")).isEmpty();
        assertThat(reopened.find("r-1")).isPresent();
        assertThat(reopened.find("r-" + ErasureReceiptFile.RETAINED_RECEIPTS)).isPresent();
    }

    @Test
    @DisplayName("a receipt id is recorded once")
    void duplicatesAreRefused(@TempDir Path dataDir) throws IOException {
        ErasureReceiptFile file = ErasureReceiptFile.open(dataDir);
        file.record(receipt("r-1"));

        assertThatThrownBy(() -> file.record(receipt("r-1")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a file whose bytes no longer match their checksum is ignored, then replaced")
    void damagedFileIsIgnored(@TempDir Path dataDir) throws IOException {
        ErasureReceiptFile.open(dataDir).record(receipt("r-1"));
        Path path = dataDir.resolve(ErasureReceiptFile.FILE);
        Files.writeString(path, Files.readString(path).replace("docs", "dogs"));

        ErasureReceiptFile reopened = ErasureReceiptFile.open(dataDir);
        assertThat(reopened.size()).isZero();

        reopened.record(receipt("r-2"));
        assertThat(ErasureReceiptFile.open(dataDir).find("r-2")).isPresent();
    }

    @Test
    @DisplayName("a file cut short, headerless, of an unknown version, empty or miscounted is "
        + "ignored")
    void unreadableShapesAreIgnored(@TempDir Path dataDir) throws IOException {
        Path path = dataDir.resolve(ErasureReceiptFile.FILE);
        String header = ErasureReceiptFile.HEADER_MARKER + "|1";
        String record = "r-1|1|docs|document|1|0|0|0|0|0|";

        Files.writeString(path, header + "\n" + record + "\n");
        assertThat(ErasureReceiptFile.open(dataDir).size()).as("cut short").isZero();

        Sealed.write(path, List.of("not-a-header", record), ErasureReceiptFile.END_MARKER, 1);
        assertThat(ErasureReceiptFile.open(dataDir).size()).as("headerless").isZero();

        Sealed.write(path, List.of(ErasureReceiptFile.HEADER_MARKER + "|9", record),
            ErasureReceiptFile.END_MARKER, 1);
        assertThat(ErasureReceiptFile.open(dataDir).size()).as("newer version").isZero();

        Sealed.write(path, List.of(ErasureReceiptFile.HEADER_MARKER + "|x", record),
            ErasureReceiptFile.END_MARKER, 1);
        assertThat(ErasureReceiptFile.open(dataDir).size()).as("unnumbered version").isZero();

        Sealed.write(path, List.of(header, record), ErasureReceiptFile.END_MARKER, 2);
        assertThat(ErasureReceiptFile.open(dataDir).size()).as("miscounted").isZero();

        Files.writeString(path, "");
        assertThat(ErasureReceiptFile.open(dataDir).size()).as("empty").isZero();

        Sealed.write(path, List.of(header, record), ErasureReceiptFile.END_MARKER, 1);
        assertThat(ErasureReceiptFile.open(dataDir).size()).as("well formed").isEqualTo(1);
    }

    @Test
    @DisplayName("a record that does not parse costs that receipt alone")
    void malformedRecordsAreSkipped(@TempDir Path dataDir) throws IOException {
        Path path = dataDir.resolve(ErasureReceiptFile.FILE);
        List<String> lines = List.of(ErasureReceiptFile.HEADER_MARKER + "|1",
            "good|1|docs|document|1|0|0|0|0|1|shared=3+2",
            "too|few|fields",
            "flag|1|docs|document|1|0|0|0|2|0|",
            "range|1|docs|document|1|0|0|0|0|0|shared=3",
            "length|1|docs|document|1|0|0|0|0|0|shared=3+0",
            "huge|1|docs|document|1|0|0|0|0|0|shared=3+99999999",
            "nameless|1|docs|document|1|0|0|0|0|0|=3+1",
            "neg|-1|docs|document|1|0|0|0|0|0|",
            "bad id!|1|docs|document|1|0|0|0|0|0|",
            "good|1|docs|document|1|0|0|0|0|0|");
        Sealed.write(path, lines, ErasureReceiptFile.END_MARKER, lines.size() - 1);

        ErasureReceiptFile file = ErasureReceiptFile.open(dataDir);

        assertThat(file.size()).isEqualTo(1);
        assertThat(file.find("good").orElseThrow().graph()).singleElement()
            .satisfies(requests -> assertThat(requests.requestIds()).containsExactly(3L, 4L));
    }

    @Test
    @DisplayName("a rewrite that cannot be published changes nothing, on disk or in memory")
    void failedWriteChangesNothing(@TempDir Path dataDir) throws IOException {
        Path path = dataDir.resolve(ErasureReceiptFile.FILE);
        ErasureReceiptFile.open(dataDir).record(receipt("r-1"));
        String before = Files.readString(path, StandardCharsets.UTF_8);
        ErasureReceiptFile failing = new ErasureReceiptFile(path,
            new SourceCatalog.CatalogWriter() {
                @Override
                public void force(Path file) throws IOException {
                    throw new IOException("disk full");
                }

                @Override
                public void publish(Path replacement, Path target) {
                    throw new AssertionError("never reached");
                }

                @Override
                public void forceDirectory(Path directory) {
                    throw new AssertionError("never reached");
                }
            });

        assertThatThrownBy(() -> failing.record(receipt("r-2"))).isInstanceOf(IOException.class);

        assertThat(failing.find("r-2")).isEmpty();
        assertThat(failing.find("r-1")).isPresent();
        assertThat(Files.readString(path, StandardCharsets.UTF_8)).isEqualTo(before);
        assertThat(dataDir.resolve(ErasureReceiptFile.FILE + ".tmp")).doesNotExist();
    }

    @Test
    @DisplayName("a receipt refuses what the file could not hold or a reply never said")
    void receiptsAreValidated() {
        assertThatThrownBy(() -> new ErasureReceiptFile.Receipt("has space", 0, "docs",
            "document", 0, 0, 0, 0, false, false, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErasureReceiptFile.Receipt("r", 0, "docs", "Doc|x", 0, 0,
            0, 0, false, false, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErasureReceiptFile.Receipt("r", 0, "docs", "document", -1,
            0, 0, 0, false, false, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErasureReceiptFile.Receipt(null, 0, "docs", "document", 0,
            0, 0, 0, false, false, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ErasureReceiptFile.GraphRequests("a=b", List.of(1L)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErasureReceiptFile.GraphRequests("acme", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErasureReceiptFile.GraphRequests("acme", List.of(0L)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
