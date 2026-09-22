package com.eonmux.excalibur.retrieval.lexical;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.lucene.index.IndexNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An index opened to serve what something else wrote.
 *
 * <p>A published graph's indexes are read and never written, and opening them with a writer
 * cost a write lock that stopped a replacement opening at the same path, and created an empty
 * index wherever one was missing - which the node then served as though it were the graph's.</p>
 */
@DisplayName("Read-only lexical index")
class LexicalIndexReadOnlyTest {

    private static final double RAM_BUFFER_MB = 4.0;

    @Test
    @DisplayName("reads what was committed")
    void readsWhatWasWritten(@TempDir Path dir) throws IOException {
        try (LexicalIndex writable = LexicalIndex.open(dir, RAM_BUFFER_MB)) {
            writable.put(7L, "analytical engine", "docs:ada");
            writable.refresh();
        }

        try (LexicalIndex index = LexicalIndex.openReadOnly(dir)) {
            assertThat(index.isReadOnly()).isTrue();
            assertThat(index.documentCount()).isEqualTo(1);
            assertThat(index.textOf(7L)).isEqualTo("analytical engine");
            assertThat(index.externalKeyOf(7L)).isEqualTo("docs:ada");
        }
    }

    @Test
    @DisplayName("an absent index is an error, not an empty index")
    void anAbsentIndexIsRefused(@TempDir Path dir) {
        assertThatThrownBy(() -> LexicalIndex.openReadOnly(dir.resolve("documents")))
            .isInstanceOf(IndexNotFoundException.class);
    }

    @Test
    @DisplayName("takes no write lock, so a writer can open the same index beside it")
    void takesNoLock(@TempDir Path dir) throws IOException {
        try (LexicalIndex writable = LexicalIndex.open(dir, RAM_BUFFER_MB)) {
            writable.put(1L, "first");
            writable.refresh();
        }
        try (LexicalIndex reader = LexicalIndex.openReadOnly(dir);
             LexicalIndex writer = LexicalIndex.open(dir, RAM_BUFFER_MB)) {
            writer.put(2L, "second");
            writer.refresh();

            assertThat(reader.documentCount()).as("a point-in-time view").isEqualTo(1);
            reader.refresh();
            assertThat(reader.documentCount()).isEqualTo(2);
            assertThat(reader.descriptors())
                .as("no lock file is held open")
                .isLessThan(writer.descriptors());
        }
    }

    @Test
    @DisplayName("refuses a write rather than accepting one it cannot keep")
    void refusesWrites(@TempDir Path dir) throws IOException {
        try (LexicalIndex writable = LexicalIndex.open(dir, RAM_BUFFER_MB)) {
            writable.refresh();
        }
        try (LexicalIndex index = LexicalIndex.openReadOnly(dir)) {
            assertThatThrownBy(() -> index.put(1L, "text"))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> index.delete(1L))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(index::commit).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(index::forceMergeDeletes)
                .isInstanceOf(IllegalStateException.class);
        }
    }
}
