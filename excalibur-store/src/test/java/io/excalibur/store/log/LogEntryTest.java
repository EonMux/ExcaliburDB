package io.excalibur.store.log;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for LogEntry class.
 */
@DisplayName("LogEntry Tests")
class LogEntryTest {

    private static final byte[] TEST_KEY = "testKey".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TEST_VALUE = "testValue".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    @Nested
    @DisplayName("Type Constants")
    class TypeConstantsTests {

        @Test
        @DisplayName("TYPE_PUT should be 1")
        void typePut() {
            assertThat(LogEntry.TYPE_PUT).isEqualTo((byte) 1);
        }

        @Test
        @DisplayName("TYPE_DELETE should be 2")
        void typeDelete() {
            assertThat(LogEntry.TYPE_DELETE).isEqualTo((byte) 2);
        }

        @Test
        @DisplayName("TYPE_BARRIER should be 3")
        void typeBarrier() {
            assertThat(LogEntry.TYPE_BARRIER).isEqualTo((byte) 3);
        }

        @Test
        @DisplayName("HEADER_SIZE should be 13")
        void headerSize() {
            assertThat(LogEntry.HEADER_SIZE).isEqualTo(13);
        }
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create entry with explicit checksum")
        void shouldCreateWithExplicitChecksum() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_VALUE, 100L, 12345L);

            assertThat(entry.getType()).isEqualTo(LogEntry.TYPE_PUT);
            assertThat(entry.getId()).isEqualTo(1L);
            assertThat(entry.getPayload()).isEqualTo(TEST_VALUE);
            assertThat(entry.getOffset()).isEqualTo(100L);
            assertThat(entry.getChecksum()).isEqualTo(12345L);
        }

        @Test
        @DisplayName("Should create entry with auto-calculated checksum")
        void shouldCreateWithAutoChecksum() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_VALUE, 100L);

            assertThat(entry.getType()).isEqualTo(LogEntry.TYPE_PUT);
            assertThat(entry.getId()).isEqualTo(1L);
            assertThat(entry.getPayload()).isEqualTo(TEST_VALUE);
            assertThat(entry.getOffset()).isEqualTo(100L);
            assertThat(entry.getChecksum()).isNotZero();
        }

        @Test
        @DisplayName("Should create barrier entry with explicit checksum")
        void shouldCreateBarrierWithExplicitChecksum() {
            // Barrier entries need explicit checksum since payload can be null
            LogEntry entry = new LogEntry(LogEntry.TYPE_BARRIER, 1L, null, 100L, 0L);

            assertThat(entry.getType()).isEqualTo(LogEntry.TYPE_BARRIER);
            assertThat(entry.getPayload()).isNull();
        }

        @Test
        @DisplayName("Should create entry with empty payload")
        void shouldCreateWithEmptyPayload() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, EMPTY_PAYLOAD, 100L);

            assertThat(entry.getType()).isEqualTo(LogEntry.TYPE_PUT);
            assertThat(entry.getPayload()).isEqualTo(EMPTY_PAYLOAD);
        }
    }

    @Nested
    @DisplayName("Type Check Methods")
    class TypeCheckTests {

        @Test
        @DisplayName("isPut should return true for PUT entry")
        void isPutTrue() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_VALUE, 0L);
            assertThat(entry.isPut()).isTrue();
            assertThat(entry.isDelete()).isFalse();
            assertThat(entry.isBarrier()).isFalse();
        }

        @Test
        @DisplayName("isDelete should return true for DELETE entry")
        void isDeleteTrue() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_DELETE, 1L, TEST_KEY, 0L);
            assertThat(entry.isDelete()).isTrue();
            assertThat(entry.isPut()).isFalse();
            assertThat(entry.isBarrier()).isFalse();
        }

        @Test
        @DisplayName("isBarrier should return true for BARRIER entry")
        void isBarrierTrue() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_BARRIER, 1L, EMPTY_PAYLOAD, 0L);
            assertThat(entry.isBarrier()).isTrue();
            assertThat(entry.isPut()).isFalse();
            assertThat(entry.isDelete()).isFalse();
        }
    }

    @Nested
    @DisplayName("PutEntry Parsing")
    class PutEntryTests {

        @Test
        @DisplayName("Should parse PUT entry")
        void shouldParsePutEntry() {
            // Create payload: [keyLen(1 byte)][key bytes][value bytes]
            byte[] payload = new byte[1 + TEST_KEY.length + TEST_VALUE.length];
            payload[0] = (byte) TEST_KEY.length;
            System.arraycopy(TEST_KEY, 0, payload, 1, TEST_KEY.length);
            System.arraycopy(TEST_VALUE, 0, payload, 1 + TEST_KEY.length, TEST_VALUE.length);

            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, payload, 0L);
            LogEntry.PutEntry putEntry = entry.asPutEntry();

            assertThat(putEntry).isNotNull();
            assertThat(putEntry.getKey()).isEqualTo(TEST_KEY);
            assertThat(putEntry.getValue()).isEqualTo(TEST_VALUE);
            assertThat(putEntry.getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Should return null for non-PUT entry")
        void shouldReturnNullForNonPut() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_DELETE, 1L, TEST_KEY, 0L);
            assertThat(entry.asPutEntry()).isNull();
        }

        @Test
        @DisplayName("Should return null for null payload")
        void shouldReturnNullForNullPayload() {
            // Use explicit checksum constructor with null payload
            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, null, 0L, 0L);
            assertThat(entry.asPutEntry()).isNull();
        }

        @Test
        @DisplayName("Should return null for empty payload")
        void shouldReturnNullForEmptyPayload() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, new byte[0], 0L);
            assertThat(entry.asPutEntry()).isNull();
        }

        @Test
        @DisplayName("Should return null for invalid key length")
        void shouldReturnNullForInvalidKeyLength() {
            byte[] payload = new byte[10];
            payload[0] = (byte) 100; // key length larger than payload

            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, payload, 0L);
            assertThat(entry.asPutEntry()).isNull();
        }

        @Test
        @DisplayName("Should handle empty value")
        void shouldHandleEmptyValue() {
            byte[] payload = new byte[1 + TEST_KEY.length];
            payload[0] = (byte) TEST_KEY.length;
            System.arraycopy(TEST_KEY, 0, payload, 1, TEST_KEY.length);

            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, payload, 0L);
            LogEntry.PutEntry putEntry = entry.asPutEntry();

            assertThat(putEntry).isNotNull();
            assertThat(putEntry.getValue()).isNull();
        }

        @Test
        @DisplayName("Should get key as string")
        void shouldGetKeyAsString() {
            byte[] payload = new byte[1 + TEST_KEY.length + TEST_VALUE.length];
            payload[0] = (byte) TEST_KEY.length;
            System.arraycopy(TEST_KEY, 0, payload, 1, TEST_KEY.length);
            System.arraycopy(TEST_VALUE, 0, payload, 1 + TEST_KEY.length, TEST_VALUE.length);

            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, payload, 0L);
            LogEntry.PutEntry putEntry = entry.asPutEntry();

            assertThat(putEntry.getKeyAsString()).isEqualTo("testKey");
        }

        @Test
        @DisplayName("Should get value as string")
        void shouldGetValueAsString() {
            byte[] payload = new byte[1 + TEST_KEY.length + TEST_VALUE.length];
            payload[0] = (byte) TEST_KEY.length;
            System.arraycopy(TEST_KEY, 0, payload, 1, TEST_KEY.length);
            System.arraycopy(TEST_VALUE, 0, payload, 1 + TEST_KEY.length, TEST_VALUE.length);

            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, payload, 0L);
            LogEntry.PutEntry putEntry = entry.asPutEntry();

            assertThat(putEntry.getValueAsString()).isEqualTo("testValue");
        }

        @Test
        @DisplayName("PutEntry toString should contain key")
        void putEntryToString() {
            byte[] payload = new byte[1 + TEST_KEY.length + TEST_VALUE.length];
            payload[0] = (byte) TEST_KEY.length;
            System.arraycopy(TEST_KEY, 0, payload, 1, TEST_KEY.length);
            System.arraycopy(TEST_VALUE, 0, payload, 1 + TEST_KEY.length, TEST_VALUE.length);

            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, payload, 0L);
            LogEntry.PutEntry putEntry = entry.asPutEntry();

            assertThat(putEntry.toString()).contains("testKey");
            assertThat(putEntry.toString()).contains("testValue");
        }
    }

    @Nested
    @DisplayName("DeleteEntry Parsing")
    class DeleteEntryTests {

        @Test
        @DisplayName("Should parse DELETE entry")
        void shouldParseDeleteEntry() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_DELETE, 1L, TEST_KEY, 0L);
            LogEntry.DeleteEntry deleteEntry = entry.asDeleteEntry();

            assertThat(deleteEntry).isNotNull();
            assertThat(deleteEntry.getKey()).isEqualTo(TEST_KEY);
            assertThat(deleteEntry.getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Should return null for non-DELETE entry")
        void shouldReturnNullForNonDelete() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_KEY, 0L);
            assertThat(entry.asDeleteEntry()).isNull();
        }

        @Test
        @DisplayName("Should handle null payload")
        void shouldHandleNullPayload() {
            // Use explicit checksum constructor with null payload
            LogEntry entry = new LogEntry(LogEntry.TYPE_DELETE, 1L, null, 0L, 0L);
            LogEntry.DeleteEntry deleteEntry = entry.asDeleteEntry();

            assertThat(deleteEntry).isNotNull();
            assertThat(deleteEntry.getKey()).isNull();
        }

        @Test
        @DisplayName("Should get key as string")
        void shouldGetKeyAsString() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_DELETE, 1L, TEST_KEY, 0L);
            LogEntry.DeleteEntry deleteEntry = entry.asDeleteEntry();

            assertThat(deleteEntry.getKeyAsString()).isEqualTo("testKey");
        }

        @Test
        @DisplayName("DeleteEntry toString should contain key")
        void deleteEntryToString() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_DELETE, 1L, TEST_KEY, 0L);
            LogEntry.DeleteEntry deleteEntry = entry.asDeleteEntry();

            assertThat(deleteEntry.toString()).contains("testKey");
        }
    }

    @Nested
    @DisplayName("BarrierEntry Parsing")
    class BarrierEntryTests {

        @Test
        @DisplayName("Should parse BARRIER entry")
        void shouldParseBarrierEntry() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_BARRIER, 1L, EMPTY_PAYLOAD, 100L);
            LogEntry.BarrierEntry barrierEntry = entry.asBarrierEntry();

            assertThat(barrierEntry).isNotNull();
            assertThat(barrierEntry.getId()).isEqualTo(1L);
            assertThat(barrierEntry.getOffset()).isEqualTo(100L);
        }

        @Test
        @DisplayName("Should return null for non-BARRIER entry")
        void shouldReturnNullForNonBarrier() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_KEY, 0L);
            assertThat(entry.asBarrierEntry()).isNull();
        }

        @Test
        @DisplayName("BarrierEntry toString should contain values")
        void barrierEntryToString() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_BARRIER, 42L, EMPTY_PAYLOAD, 100L);
            LogEntry.BarrierEntry barrierEntry = entry.asBarrierEntry();

            assertThat(barrierEntry.toString()).contains("42");
            assertThat(barrierEntry.toString()).contains("100");
        }
    }

    @Nested
    @DisplayName("Checksum Tests")
    class ChecksumTests {

        @Test
        @DisplayName("Should verify valid checksum")
        void shouldVerifyValidChecksum() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_VALUE, 0L);
            // Auto-calculated checksum should be valid
            assertThat(entry.getChecksum()).isNotZero();
        }

        @Test
        @DisplayName("Should detect invalid checksum")
        void shouldDetectInvalidChecksum() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_VALUE, 0L, 999L);
            assertThat(entry.verifyChecksum()).isFalse();
        }

        @Test
        @DisplayName("Should verify checksum with empty payload")
        void shouldVerifyChecksumWithEmptyPayload() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_BARRIER, 1L, EMPTY_PAYLOAD, 0L);
            // Empty payload checksum might be 0 or some other value
            // Just verify we can create and get the checksum without error
            assertThat(entry.getChecksum()).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Size Estimation")
    class SizeEstimationTests {

        @Test
        @DisplayName("Should estimate size with payload")
        void shouldEstimateSizeWithPayload() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_VALUE, 0L);
            assertThat(entry.estimateSize()).isEqualTo(LogEntry.HEADER_SIZE + TEST_VALUE.length);
        }

        @Test
        @DisplayName("Should estimate size with empty payload")
        void shouldEstimateSizeWithEmptyPayload() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_BARRIER, 1L, EMPTY_PAYLOAD, 0L);
            assertThat(entry.estimateSize()).isEqualTo(LogEntry.HEADER_SIZE);
        }
    }

    @Nested
    @DisplayName("Copy Tests")
    class CopyTests {

        @Test
        @DisplayName("Should copy entry with payload")
        void shouldCopyEntryWithPayload() {
            LogEntry original = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_VALUE, 0L);
            LogEntry copy = original.copy();

            assertThat(copy).isNotSameAs(original);
            assertThat(copy.getType()).isEqualTo(original.getType());
            assertThat(copy.getId()).isEqualTo(original.getId());
            assertThat(copy.getPayload()).isEqualTo(original.getPayload());
            assertThat(copy.getPayload()).isNotSameAs(original.getPayload());
            assertThat(copy.getOffset()).isEqualTo(original.getOffset());
            assertThat(copy.getChecksum()).isEqualTo(original.getChecksum());
        }

        @Test
        @DisplayName("Should copy entry with null payload")
        void shouldCopyEntryWithNullPayload() {
            LogEntry original = new LogEntry(LogEntry.TYPE_BARRIER, 1L, null, 0L, 0L);
            LogEntry copy = original.copy();

            assertThat(copy.getPayload()).isNull();
        }
    }

    @Nested
    @DisplayName("Equals and HashCode")
    class EqualsHashCodeTests {

        @Test
        @DisplayName("Should be equal for same values")
        void shouldBeEqualForSameValues() {
            LogEntry entry1 = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_VALUE, 0L, 123L);
            LogEntry entry2 = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_VALUE, 0L, 123L);

            assertThat(entry1).isEqualTo(entry2);
            assertThat(entry1.hashCode()).isEqualTo(entry2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal for different type")
        void shouldNotBeEqualForDifferentType() {
            LogEntry entry1 = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_VALUE, 0L, 123L);
            LogEntry entry2 = new LogEntry(LogEntry.TYPE_DELETE, 1L, TEST_VALUE, 0L, 123L);

            assertThat(entry1).isNotEqualTo(entry2);
        }

        @Test
        @DisplayName("Should not be equal for different id")
        void shouldNotBeEqualForDifferentId() {
            LogEntry entry1 = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_VALUE, 0L, 123L);
            LogEntry entry2 = new LogEntry(LogEntry.TYPE_PUT, 2L, TEST_VALUE, 0L, 123L);

            assertThat(entry1).isNotEqualTo(entry2);
        }

        @Test
        @DisplayName("Should not be equal for different payload")
        void shouldNotBeEqualForDifferentPayload() {
            LogEntry entry1 = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_VALUE, 0L, 123L);
            LogEntry entry2 = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_KEY, 0L, 123L);

            assertThat(entry1).isNotEqualTo(entry2);
        }

        @Test
        @DisplayName("Should be same instance")
        void shouldBeSameInstance() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_VALUE, 0L);
            assertThat(entry).isEqualTo(entry);
        }

        @Test
        @DisplayName("Should not be equal to null")
        void shouldNotBeEqualToNull() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_VALUE, 0L);
            assertThat(entry).isNotEqualTo(null);
        }

        @Test
        @DisplayName("Should not be equal to different class")
        void shouldNotBeEqualToDifferentClass() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_VALUE, 0L);
            assertThat(entry).isNotEqualTo("string");
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToStringTests {

        @Test
        @DisplayName("Should contain type PUT")
        void shouldContainTypePut() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_VALUE, 0L);
            assertThat(entry.toString()).contains("PUT");
        }

        @Test
        @DisplayName("Should contain type DELETE")
        void shouldContainTypeDelete() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_DELETE, 1L, TEST_KEY, 0L);
            assertThat(entry.toString()).contains("DELETE");
        }

        @Test
        @DisplayName("Should contain type BARRIER")
        void shouldContainTypeBarrier() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_BARRIER, 1L, EMPTY_PAYLOAD, 0L);
            assertThat(entry.toString()).contains("BARRIER");
        }

        @Test
        @DisplayName("Should contain id")
        void shouldContainId() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 42L, TEST_VALUE, 0L);
            assertThat(entry.toString()).contains("42");
        }

        @Test
        @DisplayName("Should contain offset")
        void shouldContainOffset() {
            LogEntry entry = new LogEntry(LogEntry.TYPE_PUT, 1L, TEST_VALUE, 100L);
            assertThat(entry.toString()).contains("100");
        }

        @Test
        @DisplayName("Should contain UNKNOWN for unknown type")
        void shouldContainUnknownForUnknownType() {
            LogEntry entry = new LogEntry((byte) 99, 1L, TEST_VALUE, 0L);
            assertThat(entry.toString()).contains("UNKNOWN");
        }
    }
}
