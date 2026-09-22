package com.eonmux.excalibur.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deciding where a segment index's files go when the volume set is not the whole story.
 *
 * <p>Two files named {@code segment_N.dat} on two volumes is a state nothing downstream can
 * resolve: the next start scans every volume, finds both, and cannot tell which holds the
 * newer data. The only useful moment to complain is the one where the second file is about to
 * be created, because that is the only moment at which the directories involved are still
 * something an operator can be handed.</p>
 */
@DisplayName("Segment locations")
class SegmentLocationsTest {

    @TempDir
    Path tempDir;

    private Path first;
    private Path second;

    /**
     * Creates two volume directories and returns a placement that sends everything to the first.
     *
     * @return A placement over both directories, preferring the first
     * @throws IOException if the directories cannot be created
     */
    private SegmentPlacement preferringTheFirst() throws IOException {
        first = Files.createDirectories(tempDir.resolve("volume-a"));
        second = Files.createDirectories(tempDir.resolve("volume-b"));
        return SegmentPlacement.across(List.of(first, second), index -> first);
    }

    @Test
    @DisplayName("an index with no file anywhere goes where the placement says")
    void anUnusedIndexFollowsThePlacement() throws IOException {
        SegmentPlacement placement = preferringTheFirst();

        assertThat(new SegmentLocations().segmentFile(9L, placement))
            .isEqualTo(first.resolve("segment_9.dat"));
    }

    @Test
    @DisplayName("an adopted index keeps the directory its file is in, whatever the ring prefers")
    void anAdoptedIndexOutranksThePlacement() throws IOException {
        SegmentPlacement placement = preferringTheFirst();
        SegmentLocations locations = new SegmentLocations();

        locations.adopt(9L, second.resolve("segment_9.dat"));

        assertThat(locations.segmentFile(9L, placement))
            .isEqualTo(second.resolve("segment_9.dat"));
    }

    @Test
    @DisplayName("creating a second file for an index another volume holds is refused by name")
    void aNameAlreadyTakenElsewhereIsRefused() throws IOException {
        SegmentPlacement placement = preferringTheFirst();
        Files.createFile(second.resolve("segment_9.dat"));

        assertThatThrownBy(() -> new SegmentLocations().segmentFile(9L, placement))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("segment_9.dat")
            .hasMessageContaining(first.toString())
            .hasMessageContaining(second.toString());
    }

    @Test
    @DisplayName("installing a placement discards what the previous one was asked")
    void forgettingDropsEveryAdoption() throws IOException {
        SegmentPlacement placement = preferringTheFirst();
        SegmentLocations locations = new SegmentLocations();
        locations.adopt(9L, second.resolve("segment_9.dat"));

        locations.forgetAll();

        assertThat(locations.segmentFile(9L, placement))
            .isEqualTo(first.resolve("segment_9.dat"));
    }
}
