package io.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A segment's description has to survive being asked for.
 *
 * <p>{@code toString} is reached from log statements, assertion messages and debugger views -
 * overwhelmingly on paths that are already going wrong. One that throws replaces the problem
 * being reported with its own, and does it at the moment there is least to go on.</p>
 */
@DisplayName("Segment metadata description")
class SegmentMetadataDescriptionTest {

    @Test
    @DisplayName("describing a segment does not throw, whatever its counters hold")
    void describingASegmentDoesNotThrow(@TempDir Path tempDir) {
        SegmentCatalog catalog = new SegmentCatalog(tempDir.resolve("catalog"));
        SegmentCatalog.SegmentMetadata metadata =
            catalog.create("a-source", tempDir.resolve("segment_0.dat"));
        metadata.setEntryCount(2);
        metadata.addTombstoneCount(1);

        assertThatCode(metadata::toString).doesNotThrowAnyException();

        String described = metadata.toString();
        assertThat(described)
            .as("the counters have to be read out of their holders, not formatted as numbers")
            .contains("entries=2")
            .contains("tombstones=1");
        assertThat(described)
            .as("the state a caller reasons about is the lifecycle state")
            .contains("state=" + metadata.getLifecycleState());
        assertThat(described)
            .as("and the source it belongs to, which is what identifies it to an operator")
            .contains("a-source");
    }
}
