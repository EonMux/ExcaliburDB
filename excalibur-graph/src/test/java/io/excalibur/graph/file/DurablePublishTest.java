package io.excalibur.graph.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.excalibur.graph.format.EdgeColumn;
import io.excalibur.graph.format.EdgeStoreDescriptor;
import io.excalibur.graph.staging.GraphBuilder;
import io.excalibur.graph.store.EdgeStoreWriter;
import io.excalibur.graph.store.MappedEdgeStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a graph build owes the device before it says a graph has been published.
 *
 * <p>The build's fallback is the staging log, which is why most of what it writes is left to the
 * page cache. Publishing is where that stops being true: the last act of a publish is to delete
 * the graph it replaced, so from that point the new directory is the only copy there is. A rename
 * that reached the platter ahead of the bytes it names leaves a present descriptor over short or
 * empty columns, which {@code MappedColumns.open} refuses - a graph that will not open, with the
 * previous one already gone.
 */
@DisplayName("Durable publishing")
class DurablePublishTest {

    @Nested
    @DisplayName("edge store")
    class Store {

        @Test
        @DisplayName("every column is on the device before the descriptor that names them is")
        void columnsAreForcedBeforeTheDescriptor(@TempDir Path dir) throws IOException {
            // The format's central crash claim is that the descriptor is written last, so a
            // build killed halfway leaves something that refuses to open rather than something
            // that opens and answers wrongly. Nothing forced anything, so the ordering held
            // against the page cache and not against the device, where it is the only thing
            // standing between a crash and a store that opens over truncated columns.
            EdgeStoreWriter writer = new EdgeStoreWriter().add(0, 0, 1).add(0, 0, 2);
            RecordingDurability durability = new RecordingDurability();

            writer.build(dir, 3, durability);

            int descriptor = durability.indexOfFile(EdgeStoreDescriptor.FILE_NAME + ".tmp");
            assertThat(descriptor)
                .as("the descriptor's contents reach the device before its name does")
                .isNotNegative();
            for (EdgeColumn column : EdgeColumn.values()) {
                assertThat(durability.indexOfFile(column.fileName()))
                    .as("%s is forced, and before the descriptor", column.fileName())
                    .isBetween(0, descriptor - 1);
            }
            assertThat(durability.directories)
                .as("and the directory entries, or the files come back unreferenced")
                .contains(dir);
        }

        @Test
        @DisplayName("the descriptor leaves no temporary behind and still opens")
        void theDescriptorIsSwappedCleanly(@TempDir Path dir) throws IOException {
            new EdgeStoreWriter().add(0, 0, 1).build(dir, 2, new RecordingDurability());

            assertThat(dir.resolve(EdgeStoreDescriptor.FILE_NAME + ".tmp")).doesNotExist();
            try (MappedEdgeStore store = MappedEdgeStore.open(dir)) {
                assertThat(store.edgeCount()).isEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("publishing")
    class Publishing {

        @Test
        @DisplayName("the assembled graph is on the device before the name is moved onto it")
        void theBuildIsForcedBeforeItIsPublished(@TempDir Path parent) throws IOException {
            // publish() deletes the previous graph once the new one is in place. Everything the
            // caller assembled - the Lucene indexes and the entity vectors included - has to be
            // durable before that, because afterwards there is nothing to fall back to.
            Path target = parent.resolve("graph");
            Path scratch = GraphBuilder.scratchFor(target);
            Files.createDirectories(scratch.resolve("documents"));
            Files.writeString(scratch.resolve("documents").resolve("segments_1"), "segment");
            Files.writeString(scratch.resolve("entities.dat"), "Jane Doe");

            RecordingDurability durability = new RecordingDurability();
            GraphBuilder.publish(scratch, target, durability);

            assertThat(durability.fileNames())
                .as("every file the caller assembled, not only the ones this module wrote")
                .contains("segments_1", "entities.dat");
            assertThat(durability.directories)
                .as("and the parent, so the published name survives the crash too")
                .contains(parent);
            assertThat(durability.directories.indexOf(parent))
                .as("the parent is forced after the rename, not before it")
                .isEqualTo(durability.directories.size() - 1);
            assertThat(target.resolve("documents").resolve("segments_1")).hasContent("segment");
        }
    }
}
