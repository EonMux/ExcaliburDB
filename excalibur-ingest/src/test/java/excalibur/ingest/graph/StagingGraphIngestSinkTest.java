package excalibur.ingest.graph;

import io.excalibur.graph.staging.ExtractedTriple;
import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.staging.GraphStagingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * What the sink counts when staging a fact does not work.
 *
 * <p>The class's own contract is that failures are counted rather than only logged, "because the
 * interesting failure is the steady one". A staging failure was the one that only got logged, so
 * a store refusing every append left a graph that quietly stopped growing with nothing to read
 * it off.</p>
 */
@DisplayName("StagingGraphIngestSink")
class StagingGraphIngestSinkTest {

    private static final ExtractedTriple FACT =
        ExtractedTriple.alwaysValid("kafka", "runs-on", "kubernetes", Provenance.UNKNOWN);

    @Test
    @DisplayName("counts a fact it could not stage")
    void countsAFactItCouldNotStage() throws IOException {
        GraphStagingStore store = mock(GraphStagingStore.class);
        doThrow(new IOException("disk full")).when(store).append(any());
        StagingGraphIngestSink sink =
            new StagingGraphIngestSink(store, RelationExtractor.none());

        int staged = sink.stage(List.of(FACT, FACT));

        assertThat(staged).isZero();
        assertThat(sink.stagingFailures()).isEqualTo(2);
        assertThat(sink.factsStaged()).isZero();
    }

    @Test
    @DisplayName("a clean run counts no staging failures")
    void aCleanRunCountsNoStagingFailures() {
        GraphStagingStore store = mock(GraphStagingStore.class);
        StagingGraphIngestSink sink =
            new StagingGraphIngestSink(store, RelationExtractor.none());

        assertThat(sink.stage(List.of(FACT))).isEqualTo(1);
        assertThat(sink.stagingFailures()).isZero();
    }

    @Test
    @DisplayName("reports the count where the other failure counts are reported")
    void reportsTheCountAlongsideTheOthers() throws IOException {
        GraphStagingStore store = mock(GraphStagingStore.class);
        doThrow(new IOException("disk full")).when(store).append(any());
        StagingGraphIngestSink sink =
            new StagingGraphIngestSink(store, RelationExtractor.none());

        sink.stage(List.of(FACT));

        assertThat(sink.toString()).contains("1 staging failures");
    }
}
