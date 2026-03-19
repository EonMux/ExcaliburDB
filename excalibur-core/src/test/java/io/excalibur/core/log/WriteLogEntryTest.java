package io.excalibur.core.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for WriteLogEntry.
 */
@DisplayName("WriteLogEntry Tests")
class WriteLogEntryTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create PUT entry")
        void shouldCreatePutEntry() {
            byte[] payload = "test payload".getBytes();

            WriteLogEntry entry = new WriteLogEntry(
                (byte) WriteLogEntry.EntryType.PUT.value(),
                1L,
                payload,
                0L
            );

            assertThat(entry.entryType()).isEqualTo((byte) 1);
            assertThat(entry.entryId()).isEqualTo(1L);
            assertThat(entry.isPut()).isTrue();
            assertThat(entry.isDelete()).isFalse();
            assertThat(entry.isCheckpoint()).isFalse();
        }

        @Test
        @DisplayName("Should create DELETE entry")
        void shouldCreateDeleteEntry() {
            byte[] payload = "key to delete".getBytes();

            WriteLogEntry entry = new WriteLogEntry(
                (byte) WriteLogEntry.EntryType.DELETE.value(),
                2L,
                payload,
                100L
            );

            assertThat(entry.isDelete()).isTrue();
            assertThat(entry.entryId()).isEqualTo(2L);
            assertThat(entry.fileOffset()).isEqualTo(100L);
        }

        @Test
        @DisplayName("Should create CHECKPOINT entry")
        void shouldCreateCheckpointEntry() {
            byte[] payload = new byte[0];

            WriteLogEntry entry = new WriteLogEntry(
                (byte) WriteLogEntry.EntryType.CHECKPOINT.value(),
                3L,
                payload,
                200L
            );

            assertThat(entry.isCheckpoint()).isTrue();
        }

        @Test
        @DisplayName("Should reject null payload")
        void shouldRejectNullPayload() {
            assertThatThrownBy(() -> new WriteLogEntry((byte) 1, 1L, null, 0L))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessageContaining("cannot be null");
        }

        @Test
        @DisplayName("Should reject invalid entry type")
        void shouldRejectInvalidEntryType() {
            assertThatThrownBy(() -> new WriteLogEntry((byte) -1, 1L, new byte[0], 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid entry type");
        }
    }

    @Nested
    @DisplayName("Entry Property Tests")
    class PropertyTests {

        @Test
        @DisplayName("Should return correct entry size")
        void shouldReturnCorrectEntrySize() {
            byte[] payload = new byte[100];
            WriteLogEntry entry = new WriteLogEntry((byte) 1, 1L, payload, 0L);
            // Header (13 bytes) + payload (100 bytes) = 113 bytes
            assertThat(entry.size()).isEqualTo(113);
        }

        @Test
        @DisplayName("Should return payload")
        void shouldReturnPayload() {
            byte[] payload = "test data".getBytes();
            WriteLogEntry entry = new WriteLogEntry((byte) 1, 1L, payload, 0L);
            assertThat(entry.payload()).containsExactly(payload);
        }

        @Test
        @DisplayName("Should return checksum")
        void shouldReturnChecksum() {
            byte[] payload = "checksum test".getBytes();
            WriteLogEntry entry = new WriteLogEntry((byte) 1, 1L, payload, 0L);
            assertThat(entry.checksum()).isNotZero();
        }
    }

    @Nested
    @DisplayName("Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("Should validate correct checksum")
        void shouldValidateCorrectChecksum() {
            byte[] payload = "valid data".getBytes();
            WriteLogEntry entry = new WriteLogEntry((byte) 1, 1L, payload, 0L);
            assertThat(entry.validate()).isTrue();
        }

        @Test
        @DisplayName("Should fail validation with wrong checksum")
        void shouldFailValidationWithWrongChecksum() {
            byte[] payload = "data".getBytes();
            WriteLogEntry entry = new WriteLogEntry((byte) 1, 1L, payload, 99999L, 0L);
            assertThat(entry.validate()).isFalse();
        }
    }

    @Nested
    @DisplayName("ToString Tests")
    class ToStringTests {

        @Test
        @DisplayName("Should include all fields in toString")
        void shouldIncludeAllFields() {
            WriteLogEntry entry = new WriteLogEntry((byte) 1, 12345L, "data".getBytes(), 0L);
            String str = entry.toString();
            assertThat(str).contains("entryType");
            assertThat(str).contains("entryId=12345");
            assertThat(str).contains("checksum");
        }
    }

    @Nested
    @DisplayName("EntryType Tests")
    class EntryTypeTests {

        @Test
        @DisplayName("Should convert PUT type from byte")
        void shouldConvertPutFromByte() {
            WriteLogEntry.EntryType type = WriteLogEntry.EntryType.from((byte) 1);
            assertThat(type).isEqualTo(WriteLogEntry.EntryType.PUT);
        }

        @Test
        @DisplayName("Should convert DELETE type from byte")
        void shouldConvertDeleteFromByte() {
            WriteLogEntry.EntryType type = WriteLogEntry.EntryType.from((byte) 2);
            assertThat(type).isEqualTo(WriteLogEntry.EntryType.DELETE);
        }

        @Test
        @DisplayName("Should convert CHECKPOINT type from byte")
        void shouldConvertCheckpointFromByte() {
            WriteLogEntry.EntryType type = WriteLogEntry.EntryType.from((byte) 3);
            assertThat(type).isEqualTo(WriteLogEntry.EntryType.CHECKPOINT);
        }

        @Test
        @DisplayName("Should reject invalid entry type byte")
        void shouldRejectInvalidTypeByte() {
            assertThatThrownBy(() -> WriteLogEntry.EntryType.from((byte) 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown entry type");
        }

        @Test
        @DisplayName("Should validate entry type bytes")
        void shouldValidateEntryTypeBytes() {
            assertThat(WriteLogEntry.EntryType.isValid((byte) 1)).isTrue();
            assertThat(WriteLogEntry.EntryType.isValid((byte) 2)).isTrue();
            assertThat(WriteLogEntry.EntryType.isValid((byte) 3)).isTrue();
            assertThat(WriteLogEntry.EntryType.isValid((byte) 0)).isFalse();
            assertThat(WriteLogEntry.EntryType.isValid((byte) 4)).isFalse();
        }

        @Test
        @DisplayName("Should return correct value")
        void shouldReturnCorrectValue() {
            assertThat(WriteLogEntry.EntryType.PUT.value()).isEqualTo(1);
            assertThat(WriteLogEntry.EntryType.DELETE.value()).isEqualTo(2);
            assertThat(WriteLogEntry.EntryType.CHECKPOINT.value()).isEqualTo(3);
        }
    }
}
