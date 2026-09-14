package excalibur.ingest.lsh;

import excalibur.filter.lsh.LshConfig;
import excalibur.filter.lsh.LshIndex;
import io.excalibur.store.Segment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for LshIngestionService domain correctness.
 */
@DisplayName("LshIngestionService")
class LshIngestionServiceTest {

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("creates service with valid vector dimensions")
        void createsServiceWithValidDimensions() {
            LshIngestionService service = new LshIngestionService(128);

            assertThat(service.getVectorDimensions()).isEqualTo(128);
            assertThat(service.getNumHashTables()).isEqualTo(LshConfig.DEFAULT_NUM_HASH_TABLES);
            assertThat(service.getNumHashFunctions()).isEqualTo(LshConfig.DEFAULT_NUM_HASH_FUNCTIONS);
            assertThat(service.getNumProbes()).isEqualTo(LshConfig.DEFAULT_NUM_PROBES);
        }

        @Test
        @DisplayName("creates service with custom parameters")
        void createsServiceWithCustomParameters() {
            LshIngestionService service = new LshIngestionService(256, 20, 12, 5);

            assertThat(service.getVectorDimensions()).isEqualTo(256);
            assertThat(service.getNumHashTables()).isEqualTo(20);
            assertThat(service.getNumHashFunctions()).isEqualTo(12);
            assertThat(service.getNumProbes()).isEqualTo(5);
        }

        @Test
        @DisplayName("rejects non-positive vector dimensions")
        void rejectsNonPositiveVectorDimensions() {
            assertThatThrownBy(() -> new LshIngestionService(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vectorDimensions must be positive");

            assertThatThrownBy(() -> new LshIngestionService(-128))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vectorDimensions must be positive");
        }

        @Test
        @DisplayName("rejects non-positive numHashTables")
        void rejectsNonPositiveNumHashTables() {
            assertThatThrownBy(() -> new LshIngestionService(128, 0, 12, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numHashTables must be positive");
        }

        @Test
        @DisplayName("rejects non-positive numHashFunctions")
        void rejectsNonPositiveNumHashFunctions() {
            assertThatThrownBy(() -> new LshIngestionService(128, 15, 0, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numHashFunctions must be positive");
        }

        @Test
        @DisplayName("rejects non-positive numProbes")
        void rejectsNonPositiveNumProbes() {
            assertThatThrownBy(() -> new LshIngestionService(128, 15, 12, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numProbes must be positive");
        }
    }

    @Nested
    @DisplayName("insertVector")
    class InsertVector {

        @Test
        @DisplayName("rejects null segment")
        void rejectsNullSegment() {
            LshIngestionService service = new LshIngestionService(128);
            float[] vector = new float[128];

            assertThatThrownBy(() -> service.insertVector(null, vector, 1L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("segment must not be null");
        }

        @Test
        @DisplayName("rejects null vector")
        void rejectsNullVector() {
            LshIngestionService service = new LshIngestionService(128);
            Segment segment = mock(Segment.class);

            assertThatThrownBy(() -> service.insertVector(segment, null, 1L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("vector must not be null");
        }

        @Test
        @DisplayName("rejects negative entryId")
        void rejectsNegativeEntryId() {
            LshIngestionService service = new LshIngestionService(128);
            Segment segment = mock(Segment.class);
            float[] vector = new float[128];

            assertThatThrownBy(() -> service.insertVector(segment, vector, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entryId must be non-negative");
        }

        @Test
        @DisplayName("accepts zero entryId")
        void acceptsZeroEntryId() {
            LshIngestionService service = new LshIngestionService(128);
            Segment segment = mock(Segment.class);
            when(segment.segmentId()).thenReturn(1L);
            when(segment.lshIndex()).thenReturn(null);
            float[] vector = new float[128];

            // Will try to initialize LSH index, which may fail in mock environment
            // but we're testing that the method accepts zero entryId
            service.insertVector(segment, vector, 0L);
            // No exception thrown is the test
        }
    }

    @Nested
    @DisplayName("removeEntry")
    class RemoveEntry {

        @Test
        @DisplayName("rejects null segment")
        void rejectsNullSegment() {
            LshIngestionService service = new LshIngestionService(128);

            assertThatThrownBy(() -> service.removeEntry(null, 1L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("segment must not be null");
        }

        @Test
        @DisplayName("rejects negative entryId")
        void rejectsNegativeEntryId() {
            LshIngestionService service = new LshIngestionService(128);
            Segment segment = mock(Segment.class);

            assertThatThrownBy(() -> service.removeEntry(segment, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entryId must be non-negative");
        }

        @Test
        @DisplayName("returns false when segment has no LSH index")
        void returnsFalseWhenNoLshIndex() {
            LshIngestionService service = new LshIngestionService(128);
            Segment segment = mock(Segment.class);
            when(segment.lshIndex()).thenReturn(null);
            when(segment.segmentId()).thenReturn(1L);

            boolean result = service.removeEntry(segment, 1L);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("initializeLshIndex")
    class InitializeLshIndex {

        @Test
        @DisplayName("rejects null segment")
        void rejectsNullSegment() {
            LshIngestionService service = new LshIngestionService(128);

            assertThatThrownBy(() -> service.initializeLshIndex(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("segment must not be null");
        }
    }

    @Nested
    @DisplayName("getLshConfig")
    class GetLshConfig {

        @Test
        @DisplayName("returns config with service parameters")
        void returnsConfigWithServiceParameters() {
            LshIngestionService service = new LshIngestionService(256, 20, 12, 5);

            var config = service.getLshConfig();

            assertThat(config).isNotNull();
            assertThat(config.dimensions()).isEqualTo(256);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToString {

        @Test
        @DisplayName("contains all parameters")
        void containsAllParameters() {
            LshIngestionService service = new LshIngestionService(128, 15, 16, 3);

            String str = service.toString();

            assertThat(str).contains("dimensions=128");
            assertThat(str).contains("tables=15");
            assertThat(str).contains("functions=16");
            assertThat(str).contains("probes=3");
        }
    }

    @Nested
    @DisplayName("Per-segment configuration")
    class PerSegmentConfiguration {

        private static final long SEGMENT_ID = 7L;

        @Test
        @DisplayName("a segment's index is built with the parameters the service was given")
        void segmentIndexCarriesTheConfiguredParameters() {
            LshIngestionService service = new LshIngestionService(64, 20, 12, 5);

            LshConfig config = configOfIndexAttachedBy(service);

            assertThat(config.dimensions()).isEqualTo(64);
            assertThat(config.numHashTables()).isEqualTo(20);
            assertThat(config.numHashFunctions()).isEqualTo(12);
            assertThat(config.numProbes()).isEqualTo(5);
            assertThat(config.seed())
                .as("the seed is the segment id, so segments opened together do not share hyperplanes")
                .isEqualTo(SEGMENT_ID);
        }

        @Test
        @DisplayName("the dimensions-only constructor builds a segment on the shared LSH defaults")
        void dimensionsOnlyConstructorUsesTheSharedDefaults() {
            LshIngestionService service = new LshIngestionService(64);

            LshConfig config = configOfIndexAttachedBy(service);

            assertThat(config.dimensions()).isEqualTo(64);
            assertThat(config.numHashTables()).isEqualTo(LshConfig.DEFAULT_NUM_HASH_TABLES);
            assertThat(config.numHashFunctions()).isEqualTo(LshConfig.DEFAULT_NUM_HASH_FUNCTIONS);
            assertThat(config.numProbes()).isEqualTo(LshConfig.DEFAULT_NUM_PROBES);
        }

        private LshConfig configOfIndexAttachedBy(LshIngestionService service) {
            Segment segment = mock(Segment.class);
            when(segment.segmentId()).thenReturn(SEGMENT_ID);
            when(segment.lshIndex()).thenReturn(null);
            when(segment.path()).thenReturn(null);

            service.initializeLshIndex(segment);

            ArgumentCaptor<LshIndex> attached = ArgumentCaptor.forClass(LshIndex.class);
            Mockito.verify(segment).attachLshIndex(attached.capture());
            return attached.getValue().config();
        }
    }

    @Nested
    @DisplayName("Configuration errors against index errors")
    class ConfigurationErrorsAgainstIndexErrors {

        private static final long SEGMENT_ID = 11L;

        @Test
        @DisplayName("a hash-function count no index will accept is refused against its property")
        void anImpossibleHashFunctionCountIsRefusedAgainstItsProperty() {
            // A bucket key is packed into a long, so 64 concatenated hash functions is one past
            // what any LSH index can represent. The value is the operator's, not the disk's, and
            // a service that takes it and then blames the index on disk sends them to delete a
            // corpus that is fine.
            assertThatThrownBy(() -> new LshIngestionService(128, 15, 64, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("excalibur.shard.lsh-hash-functions")
                .hasMessageContaining("64");
        }

        @Test
        @DisplayName("every service that can be built can build an index for a fresh segment")
        void anAcceptedConfigurationAlwaysYieldsAnIndex(@TempDir Path directory) {
            LshIngestionService service =
                new LshIngestionService(8, 2, LshConfig.MAX_HASH_FUNCTIONS, 1);
            Segment segment = segmentAt(directory.resolve("segment-1.dat"));

            service.initializeLshIndex(segment);

            Mockito.verify(segment).attachLshIndex(Mockito.any(LshIndex.class));
        }

        @Test
        @DisplayName("an index on disk of another shape is tolerated, not thrown")
        void aGenuineOnDiskMismatchIsStillTolerated(@TempDir Path directory) throws IOException {
            Path data = directory.resolve("segment-1.dat");
            Path tables = directory.resolve("segment-1.lsh");
            LshIndex existing = LshIndex.open(
                LshConfig.builder(8).numHashTables(2).numHashFunctions(12).numProbes(1)
                    .seed(SEGMENT_ID).build(),
                tables);
            existing.close();

            // Same segment, a hash-function count the tables on disk were not written with.
            LshIngestionService service = new LshIngestionService(8, 2, 14, 1);
            Segment segment = segmentAt(data);

            assertThatCode(() -> service.initializeLshIndex(segment)).doesNotThrowAnyException();
            Mockito.verify(segment, Mockito.never()).attachLshIndex(Mockito.any());
        }

        private Segment segmentAt(Path dataPath) {
            Segment segment = mock(Segment.class);
            when(segment.segmentId()).thenReturn(SEGMENT_ID);
            when(segment.lshIndex()).thenReturn(null);
            when(segment.path()).thenReturn(dataPath);
            return segment;
        }
    }
}
