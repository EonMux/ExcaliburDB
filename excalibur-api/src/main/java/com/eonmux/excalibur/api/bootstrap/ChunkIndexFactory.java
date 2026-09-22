package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.ingest.lexical.ChunkIndex;
import com.eonmux.excalibur.ingest.lexical.ChunkLexicalIndex;
import com.eonmux.excalibur.ingest.lexical.NativeChunkIndex;

import com.eonmux.excalibur.config.Config;
import com.eonmux.excalibur.config.IngestConfig;
import com.eonmux.excalibur.graph.erasure.CompactionPolicy;
import com.eonmux.excalibur.retrieval.lexical.Bm25Parameters;
import com.eonmux.excalibur.retrieval.lexical.native_.CompactionPlanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Opens the chunk index the configuration calls for.
 *
 * <h2>One or the other</h2>
 * <p>The two engines write to different directories and neither reads the other's, so only the
 * configured one is populated. Opening both would put a second copy of every chunk's text on disk
 * to answer one question, which is the thing the native engine exists to stop doing.</p>
 *
 * <p>Separate from the application so the choice can be tested. Getting it wrong is not a loud
 * failure: a node that opened the other engine's empty directory answers every query with nothing,
 * which is indistinguishable from a corpus that simply does not match.</p>
 */
public final class ChunkIndexFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkIndexFactory.class);

    /** Where the native engine keeps its store, under the data directory. */
    public static final String NATIVE_DIRECTORY = "chunk-index";

    /** Where the Lucene engine keeps its index, under the data directory. */
    public static final String LUCENE_DIRECTORY = "lucene-chunks";

    private ChunkIndexFactory() {
    }

    /**
     * Opens the configured chunk index.
     *
     * @param config        The node's configuration
     * @param dataDirectory The node's data directory
     * @param epochs        The node's current visibility epoch
     */
    public static ChunkIndex open(Config config, Path dataDirectory, LongSupplier epochs)
            throws IOException {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(dataDirectory, "dataDirectory must not be null");
        Objects.requireNonNull(epochs, "epochs must not be null");
        Bm25Parameters bm25 = new Bm25Parameters(
            config.ingest().getBm25K1(), config.ingest().getBm25B());
        if (IngestConfig.LEXICAL_ENGINE_LUCENE.equals(config.ingest().getLexicalEngine())) {
            // The same policy the native engine gets. The configured bound is a statement about
            // this node's erasures rather than about one engine's, and a Lucene index left to
            // Lucene's own merge schedule would honour it only by coincidence.
            return ChunkLexicalIndex.open(dataDirectory.resolve(LUCENE_DIRECTORY),
                config.graph().getRamBufferMb(), bm25, erasurePolicy(config), Clock.systemUTC());
        }
        long lag = readerLagEpochs(config);
        LOGGER.info("Chunk index compaction floor is {} epoch(s) behind current "
            + "({}ms query timeout at a {}ms tick)",
            lag, config.visibility().getQueryTimeoutMs(), config.visibility().getEpochTickMs());
        return NativeChunkIndex.open(dataDirectory.resolve(NATIVE_DIRECTORY), epochs, lag,
            CompactionPlanner.standard(
                config.ingest().getLexicalTombstoneRatio(),
                Duration.ofSeconds(config.ingest().getLexicalMaxLatencySeconds()),
                config.ingest().getLexicalMaxSegments()),
            bm25);
    }

    /** When an outstanding erasure stops being worth deferring, whichever engine is holding it. */
    private static CompactionPolicy erasurePolicy(Config config) {
        return CompactionPolicy.standard(
            config.ingest().getLexicalTombstoneRatio(),
            Duration.ofSeconds(config.ingest().getLexicalMaxLatencySeconds()),
            config.ingest().getLexicalMaxSegments());
    }

    /**
     * How far behind the current epoch a live reader can be.
     *
     * <p>Derived, not configured. A query cannot outlive its own timeout, so the timeout divided
     * by the epoch tick bounds how stale the oldest reader's snapshot can be, and segments older
     * than that can be merged without any reader's view changing underneath it. A number an
     * operator set separately would be wrong exactly when they raised the timeout - and wrong in
     * the direction that makes a long query silently lose results.</p>
     */
    public static long readerLagEpochs(Config config) {
        Objects.requireNonNull(config, "config must not be null");
        long tick = Math.max(1L, config.visibility().getEpochTickMs());
        // At least one: a floor equal to the current epoch would let a merge retire a segment a
        // query captured a moment ago.
        return Math.max(1L, config.visibility().getQueryTimeoutMs() / tick);
    }
}
