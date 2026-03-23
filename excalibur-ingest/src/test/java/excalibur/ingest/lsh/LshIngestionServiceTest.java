package excalibur.ingest.lsh;

import io.excalibur.store.Segment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
            assertThat(service.getNumHashTables()).isEqualTo(LshIngestionService.DEFAULT_NUM_HASH_TABLES);
            assertThat(service.getNumHashFunctions()).isEqualTo(LshIngestionService.DEFAULT_NUM_HASH_FUNCTIONS);
            assertThat(service.getNumProbes()).isEqualTo(LshIngestionService.DEFAULT_NUM_PROBES);
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
    @DisplayName("Default constants")
    class DefaultConstants {

        @Test
        @DisplayName("default constants are reasonable values")
        void defaultConstantsAreReasonable() {
            assertThat(LshIngestionService.DEFAULT_NUM_HASH_TABLES).isGreaterThan(0);
            assertThat(LshIngestionService.DEFAULT_NUM_HASH_FUNCTIONS).isGreaterThan(0);
            assertThat(LshIngestionService.DEFAULT_NUM_PROBES).isGreaterThan(0);
        }
    }
}
