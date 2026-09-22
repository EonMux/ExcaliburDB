package com.eonmux.excalibur.retrieval.lexical;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a forced merge removes the text of a deleted document, however little of the index it is.
 *
 * <p>Lucene's {@code TieredMergePolicy} declines to rewrite a segment whose deleted share is under
 * {@code forceMergeDeletesPctAllowed}, a tenth by default. A caller reclaiming space is right to
 * want that; a caller under an erasure deadline is destroyed by it, because one erased document in
 * a large index is under the threshold from the moment it is erased. The force-merge would return
 * having rewritten nothing, report success, and leave the text readable by anyone holding the
 * segment file.
 *
 * <p>The witness is bytes on disk rather than a query returning nothing. A deleted document is
 * already invisible to search, and an erasure obligation is about the other thing.
 */
@DisplayName("Lucene forced merge")
class LexicalIndexForceMergeTest {

    private static final double RAM_BUFFER_MB = 4.0;

    /** A term no other document in the corpus produces, as the terms dictionary holds it. */
    private static final String MARKER = "zzquazzlethorpe";

    /** Enough documents that the erased one is well under any volume threshold. */
    private static final int FILLER = 200;

    private static boolean anyFileContains(Path dir, String needle) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("one deleted document in two hundred still leaves disk")
    void aSingleDeletionIsReclaimed(@TempDir Path dir) throws IOException {
        try (LexicalIndex index = LexicalIndex.open(dir, RAM_BUFFER_MB)) {
            index.put(1L, MARKER);
            for (int i = 0; i < FILLER; i++) {
                index.put(100L + i, "routine capacity planning note " + i);
            }
            index.refresh();
            index.delete(1L);
            index.refresh();

            assertThat(anyFileContains(dir, MARKER))
                .as("a delete hides the document; it does not remove it")
                .isTrue();

            int reclaimed = index.forceMergeDeletes();
            index.refresh();

            assertThat(reclaimed).isEqualTo(1);
            assertThat(anyFileContains(dir, MARKER))
                .as("under a tenth of the index is deleted, which is exactly when the default "
                    + "merge policy would have declined to rewrite the segment")
                .isFalse();
            assertThat(index.documentCount())
                .as("the surviving documents are rewritten, not lost with it")
                .isEqualTo(FILLER);
            assertThat(index.textOf(100L)).isEqualTo("routine capacity planning note 0");
        }
    }
}
