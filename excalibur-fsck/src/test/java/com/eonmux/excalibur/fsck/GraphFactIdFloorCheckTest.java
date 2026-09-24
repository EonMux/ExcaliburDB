package com.eonmux.excalibur.fsck;

import com.eonmux.excalibur.fsck.check.CheckStatus;
import com.eonmux.excalibur.fsck.check.FileCheck;
import com.eonmux.excalibur.fsck.check.FileClassifier;
import com.eonmux.excalibur.fsck.check.FileKind;
import com.eonmux.excalibur.fsck.check.GraphStagingCheck;
import com.eonmux.excalibur.graph.staging.AssertedFact;
import com.eonmux.excalibur.graph.staging.FactIdFloor;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record of the highest fact id a namespace handed out is read and judged, rather than passed
 * over as a file nothing recognises.
 */
class GraphFactIdFloorCheckTest {

    private static final String RELATIVE = "shared/graph-staging/" + FactIdFloor.FILE_NAME;

    @Test
    void theRecordIsClassifiedWithTheStagingLogs() {
        assertThat(FileClassifier.classify(RELATIVE)).isEqualTo(FileKind.GRAPH_STAGING_LOG);
        assertThat(GraphStagingCheck.matches(FactIdFloor.FILE_NAME)).isTrue();
        assertThat(GraphStagingCheck.isUnfinishedRewrite(
            "shared/graph-staging/" + FactIdFloor.FILE_NAME + ".writing")).isTrue();
    }

    @Test
    void aRecordTheNodeWroteIsIntactAndNamesTheLastId(@TempDir Path dir) throws IOException {
        long last;
        try (GraphStagingStore staging = GraphStagingStore.open(dir)) {
            last = staging.assertFacts(List.of(AssertedFact.always("A", "knows", "B"))).get(0);
        }

        FileCheck result = GraphStagingCheck.check(dir.resolve(FactIdFloor.FILE_NAME), RELATIVE);

        assertThat(result.status()).isEqualTo(CheckStatus.INTACT);
        assertThat(result.format()).isEqualTo(GraphStagingCheck.FACT_ID_FORMAT);
        assertThat(result.detail()).contains(Long.toString(last));
    }

    @Test
    void aDamagedRecordIsPutToAnOperator(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(FactIdFloor.FILE_NAME);
        Files.write(file, "not a fact id floor".getBytes());

        FileCheck result = GraphStagingCheck.check(file, RELATIVE);

        assertThat(result.status()).isEqualTo(CheckStatus.CORRUPT);
        assertThat(result.needsOperator()).isTrue();
    }
}
