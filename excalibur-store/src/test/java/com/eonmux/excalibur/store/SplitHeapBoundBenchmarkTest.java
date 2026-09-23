package com.eonmux.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ingests about 300 MB of embedded chunks into a store running in a 256 MB heap.
 *
 * <p>The routing directory lives on the heap, and the data does not: segments are mapped
 * files. So the heap a store needs is set by how wide its directory grows, and a directory that
 * grows with the number of segments - as extendible hashing's does - costs a few bytes per
 * megabyte stored. This test is the end-to-end form of that claim, under the workload that
 * disproved it once: forty thousand chunks of text and a 1536-dimensional embedding, written by
 * eight threads through the engine's default geometry, in a heap an eighth the size of the
 * data. A directory that doubled on every split exhausted a 2 GB heap on this workload before a
 * hundred megabytes had been written.</p>
 *
 * <p>The ingest runs in a JVM of its own, launched from this one's class path, because the
 * heap limit is the thing being tested and the build's own heap is not it.</p>
 */
@Tag("benchmark")
@DisplayName("Split heap bound")
class SplitHeapBoundBenchmarkTest {

    /** Chunks ingested: the workload that exhausted a 2 GB heap. */
    private static final int CHUNKS = 40_000;

    /** Concurrent writers. */
    private static final int WRITERS = 8;

    /** The heap the ingest must fit in. */
    private static final String HEAP = "-Xmx256m";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("300 MB of chunks ingest in a 256 MB heap, and the directory stays in step with the segments")
    void ingestFitsInASmallHeap() throws Exception {
        List<String> output = runIngest(tempDir.resolve("data"));

        String result = output.stream().filter(line -> line.startsWith("RESULT ")).findFirst()
            .orElseThrow(() -> new AssertionError("the ingest did not finish:\n"
                + String.join("\n", output.subList(Math.max(0, output.size() - 40), output.size()))));
        System.out.println(result);

        int slots = field(result, "directorySlots");
        int segments = field(result, "segments");
        assertThat(field(result, "written")).isEqualTo(CHUNKS);
        assertThat(slots)
            .as("the directory is at most a small multiple of the segments it routes to")
            .isLessThanOrEqualTo(8 * Integer.highestOneBit(segments) * 2);
    }

    /**
     * Runs {@link ChunkIngestDriver} in a child JVM and returns everything it printed.
     *
     * @param dataDir Where the child keeps its store
     * @return The child's output lines
     * @throws IOException          if the child cannot be started
     * @throws InterruptedException if interrupted while waiting for it
     */
    private static List<String> runIngest(Path dataDir) throws IOException, InterruptedException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, HEAP, "--enable-preview",
            "--enable-native-access=ALL-UNNAMED",
            "-cp", System.getProperty("java.class.path"),
            ChunkIngestDriver.class.getName(),
            dataDir.toString(), Integer.toString(CHUNKS), Integer.toString(WRITERS));
        builder.redirectErrorStream(true);
        Process process = builder.start();

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                if (line.startsWith("PROGRESS") || line.startsWith("RESULT")
                        || line.startsWith("FAILURE")) {
                    System.out.println(line);
                }
            }
        }
        if (!process.waitFor(20, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("the ingest did not finish in twenty minutes");
        }
        return lines;
    }

    /**
     * Reads an integer field from a {@code key=value} line.
     *
     * @param line The line
     * @param name The field
     * @return Its value
     */
    private static int field(String line, String name) {
        for (String token : line.split(" ")) {
            if (token.startsWith(name + "=")) {
                return Integer.parseInt(token.substring(name.length() + 1));
            }
        }
        throw new AssertionError("no " + name + " in " + line);
    }
}
