package io.excalibur.graph.vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One vector per entity, addressed by id.
 *
 * <p>This is what makes reranking against uncompressed vectors possible: BM25 says which entities
 * are worth considering, and these say how close each of them actually is. The properties that
 * matter are that a vector comes back exactly as it went in, that an entity without one is
 * distinguishable from an entity whose vector happens to be zeros, and that a file describing
 * something other than what it contains is refused rather than read as plausible nonsense.</p>
 */
@DisplayName("Entity vectors")
class EntityVectorsTest {

    private static float[] vector(int dimension, float seed) {
        float[] values = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            values[i] = seed + i * 0.25f;
        }
        return values;
    }

    private static Path write(Path dir, int dimension, float[]... vectors) throws IOException {
        Path path = dir.resolve(EntityVectors.FILE_NAME);
        try (EntityVectorWriter writer =
                 new EntityVectorWriter(path, dimension, vectors.length)) {
            for (float[] vector : vectors) {
                writer.put(vector);
            }
            writer.finish();
        }
        return path;
    }

    @Test
    @DisplayName("a vector comes back exactly as it went in")
    void vectorsRoundTrip(@TempDir Path dir) throws IOException {
        float[] first = vector(8, 1.0f);
        float[] second = vector(8, -3.5f);
        Path path = write(dir, 8, first, second);

        try (EntityVectors vectors = EntityVectors.open(path)) {
            assertThat(vectors.dimension()).isEqualTo(8);
            assertThat(vectors.count()).isEqualTo(2);
            assertThat(vectors.vectorOf(0)).containsExactly(first);
            assertThat(vectors.vectorOf(1)).containsExactly(second);
        }
    }

    @Test
    @DisplayName("an entity with no vector is not confused with one that is all zeros")
    void missingIsNotZero(@TempDir Path dir) throws IOException {
        // Scoring against an all-zero vector would put the entity at a fixed, meaningless distance
        // from every query - which is worse than admitting there is nothing to score it by.
        float[] zeros = new float[4];
        Path path = write(dir, 4, zeros, null, vector(4, 2.0f));

        try (EntityVectors vectors = EntityVectors.open(path)) {
            assertThat(vectors.vectorOf(0)).isNotNull().containsExactly(zeros);
            assertThat(vectors.vectorOf(1)).isNull();
            assertThat(vectors.vectorOf(2)).isNotNull();
            assertThat(vectors.presentCount()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("an id outside the file yields nothing rather than a wild read")
    void outOfRangeIsNull(@TempDir Path dir) throws IOException {
        Path path = write(dir, 4, vector(4, 1.0f));

        try (EntityVectors vectors = EntityVectors.open(path)) {
            assertThat(vectors.vectorOf(-1)).isNull();
            assertThat(vectors.vectorOf(1)).isNull();
            assertThat(vectors.vectorOf(Long.MAX_VALUE)).isNull();
        }
    }

    @Test
    @DisplayName("an absent file is a configuration, not a failure")
    void anAbsentFileOpensAsNull(@TempDir Path dir) throws IOException {
        assertThat(EntityVectors.openIfPresent(dir)).isNull();
    }

    @Test
    @DisplayName("a present file is opened by the directory it lives in")
    void aPresentFileIsFound(@TempDir Path dir) throws IOException {
        write(dir, 4, vector(4, 1.0f));

        try (EntityVectors vectors = EntityVectors.openIfPresent(dir)) {
            assertThat(vectors).isNotNull();
            assertThat(vectors.count()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a file that is not one of these is refused")
    void aForeignFileIsRefused(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(EntityVectors.FILE_NAME);
        Files.write(path, new byte[EntityVectors.HEADER_BYTES + 16]);

        assertThatThrownBy(() -> EntityVectors.open(path))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("not an entity vector file");
    }

    @Test
    @DisplayName("a file whose length disagrees with its header is refused")
    void aTruncatedFileIsRefused(@TempDir Path dir) throws IOException {
        Path path = write(dir, 8, vector(8, 1.0f), vector(8, 2.0f));
        byte[] bytes = Files.readAllBytes(path);
        Files.write(path, java.util.Arrays.copyOf(bytes, bytes.length - 12));

        // Reading it anyway would compute an offset past the end for the last entity.
        assertThatThrownBy(() -> EntityVectors.open(path))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("header describes");
    }

    @Test
    @DisplayName("reads after close are refused rather than reading freed memory")
    void readsAfterCloseAreRefused(@TempDir Path dir) throws IOException {
        Path path = write(dir, 4, vector(4, 1.0f));
        EntityVectors vectors = EntityVectors.open(path);
        vectors.close();

        assertThatThrownBy(() -> vectors.vectorOf(0))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("closing twice is harmless")
    void closingTwiceIsHarmless(@TempDir Path dir) throws IOException {
        Path path = write(dir, 4, vector(4, 1.0f));
        EntityVectors vectors = EntityVectors.open(path);
        vectors.close();
        vectors.close();
    }

    @Test
    @DisplayName("the writer refuses a vector of the wrong dimension")
    void theWriterRefusesAWrongDimension(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(EntityVectors.FILE_NAME);
        try (EntityVectorWriter writer = new EntityVectorWriter(path, 4, 1)) {
            assertThatThrownBy(() -> writer.put(new float[8]))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("the writer refuses to finish a file it did not fill")
    void theWriterRefusesAnUnderfilledFile(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(EntityVectors.FILE_NAME);
        try (EntityVectorWriter writer = new EntityVectorWriter(path, 4, 3)) {
            writer.put(vector(4, 1.0f));

            // A short file would leave every entity after the gap at the wrong offset.
            assertThatThrownBy(writer::finish).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("the writer refuses more vectors than it declared")
    void theWriterRefusesOverfilling(@TempDir Path dir) throws IOException {
        Path path = dir.resolve(EntityVectors.FILE_NAME);
        try (EntityVectorWriter writer = new EntityVectorWriter(path, 4, 1)) {
            writer.put(vector(4, 1.0f));

            assertThatThrownBy(() -> writer.put(vector(4, 2.0f)))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("a file of many entities is addressed correctly throughout")
    void offsetsHoldAcrossManyEntities(@TempDir Path dir) throws IOException {
        int entities = 500;
        int dimension = 16;
        Path path = dir.resolve(EntityVectors.FILE_NAME);

        try (EntityVectorWriter writer = new EntityVectorWriter(path, dimension, entities)) {
            for (int id = 0; id < entities; id++) {
                // Every third entity has none, so the presence flags and the offsets have to stay
                // in step over a long run rather than only at the first record.
                writer.put(id % 3 == 0 ? null : vector(dimension, id));
            }
            writer.finish();
        }

        try (EntityVectors vectors = EntityVectors.open(path)) {
            for (int id = 0; id < entities; id++) {
                if (id % 3 == 0) {
                    assertThat(vectors.vectorOf(id)).as("entity %s", id).isNull();
                } else {
                    assertThat(vectors.vectorOf(id)).as("entity %s", id)
                        .containsExactly(vector(dimension, id));
                }
            }
        }
    }
}
