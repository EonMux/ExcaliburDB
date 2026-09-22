package com.eonmux.excalibur.store.hash;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for XxHash64 class.
 */
@DisplayName("XxHash64 Tests")
class XxHash64Test {

    @Nested
    @DisplayName("Static Hash Methods")
    class StaticHashMethodsTests {

        @Test
        @DisplayName("Should hash empty array")
        void shouldHashEmptyArray() {
            byte[] empty = new byte[0];
            long hash = XxHash64.hash(empty);

            assertThat(hash).isNotNegative();
            assertThat(hash).isEqualTo(XxHash64.normalizeToPositive(hash));
        }

        @Test
        @DisplayName("Should hash byte array")
        void shouldHashByteArray() {
            byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
            long hash = XxHash64.hash(data);

            assertThat(hash).isNotNegative();
        }

        @Test
        @DisplayName("Should hash byte array with seed")
        void shouldHashByteArrayWithSeed() {
            byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
            long hash0 = XxHash64.hash(data, 0L);
            long hash1 = XxHash64.hash(data, 1L);

            assertThat(hash0).isNotNegative();
            assertThat(hash1).isNotNegative();
            assertThat(hash0).isNotEqualTo(hash1);
        }

        @Test
        @DisplayName("Should hash byte array portion")
        void shouldHashByteArrayPortion() {
            byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
            long hash1 = XxHash64.hash(data, 0, 5);
            long hash2 = XxHash64.hash(data, 6, 5);

            assertThat(hash1).isNotNegative();
            assertThat(hash2).isNotNegative();
            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("Should hash byte array portion with seed")
        void shouldHashByteArrayPortionWithSeed() {
            byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
            long hash = XxHash64.hash(data, 0, 5, 123L);

            assertThat(hash).isNotNegative();
        }

        @Test
        @DisplayName("Should reject null data")
        void shouldRejectNullData() {
            assertThatThrownBy(() -> XxHash64.hash((byte[]) null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject invalid offset/length")
        void shouldRejectInvalidOffsetLength() {
            byte[] data = "hello".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> XxHash64.hash(data, -1, 5))
                .isInstanceOf(IndexOutOfBoundsException.class);

            assertThatThrownBy(() -> XxHash64.hash(data, 0, -1))
                .isInstanceOf(IndexOutOfBoundsException.class);

            assertThatThrownBy(() -> XxHash64.hash(data, 0, 100))
                .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    @DisplayName("String Hash")
    class StringHashTests {

        @Test
        @DisplayName("Should hash string")
        void shouldHashString() {
            long hash = XxHash64.hash("hello");

            assertThat(hash).isNotNegative();
        }

        @Test
        @DisplayName("Should hash string with seed")
        void shouldHashStringWithSeed() {
            long hash0 = XxHash64.hash("hello", 0L);
            long hash1 = XxHash64.hash("hello", 1L);

            assertThat(hash0).isNotEqualTo(hash1);
        }

        @Test
        @DisplayName("Should reject null string")
        void shouldRejectNullString() {
            assertThatThrownBy(() -> XxHash64.hash((String) null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should hash empty string")
        void shouldHashEmptyString() {
            long hash = XxHash64.hash("");

            assertThat(hash).isNotNegative();
        }
    }

    @Nested
    @DisplayName("ByteBuffer Hash")
    class ByteBufferHashTests {

        @Test
        @DisplayName("Should hash ByteBuffer")
        void shouldHashByteBuffer() {
            ByteBuffer buffer = ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8));
            long hash = XxHash64.hash(buffer);

            assertThat(hash).isNotNegative();
        }

        @Test
        @DisplayName("Should hash ByteBuffer with seed")
        void shouldHashByteBufferWithSeed() {
            ByteBuffer buffer = ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8));
            long hash = XxHash64.hash(buffer, 123L);

            assertThat(hash).isNotNegative();
        }

        @Test
        @DisplayName("Should reject null ByteBuffer")
        void shouldRejectNullByteBuffer() {
            assertThatThrownBy(() -> XxHash64.hash((ByteBuffer) null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should hash direct ByteBuffer")
        void shouldHashDirectByteBuffer() {
            ByteBuffer buffer = ByteBuffer.allocateDirect(5).order(ByteOrder.LITTLE_ENDIAN);
            buffer.put("hello".getBytes(StandardCharsets.UTF_8));
            buffer.flip();

            long hash = XxHash64.hash(buffer);

            assertThat(hash).isNotNegative();
        }
    }

    @Nested
    @DisplayName("Long Hash")
    class LongHashTests {

        @Test
        @DisplayName("Should hash long value")
        void shouldHashLongValue() {
            long hash = XxHash64.hash(12345L);

            assertThat(hash).isNotNegative();
        }

        @Test
        @DisplayName("Should hash long value with seed")
        void shouldHashLongValueWithSeed() {
            long hash0 = XxHash64.hash(12345L, 0L);
            long hash1 = XxHash64.hash(12345L, 1L);

            assertThat(hash0).isNotEqualTo(hash1);
        }

        @Test
        @DisplayName("Should hash zero")
        void shouldHashZero() {
            long hash = XxHash64.hash(0L);

            assertThat(hash).isNotNegative();
        }

        @Test
        @DisplayName("Should hash max long")
        void shouldHashMaxLong() {
            long hash = XxHash64.hash(Long.MAX_VALUE);

            assertThat(hash).isNotNegative();
        }

        @Test
        @DisplayName("Should hash negative long")
        void shouldHashNegativeLong() {
            long hash = XxHash64.hash(-1L);

            assertThat(hash).isNotNegative();
        }
    }

    @Nested
    @DisplayName("Instance Methods")
    class InstanceMethodsTests {

        private XxHash64 hasher;

        @BeforeEach
        void setUp() {
            hasher = new XxHash64();
        }

        @Test
        @DisplayName("Should create with default seed")
        void shouldCreateWithDefaultSeed() {
            assertThat(hasher.seed()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Should create with custom seed")
        void shouldCreateWithCustomSeed() {
            XxHash64 custom = new XxHash64(42L);
            assertThat(custom.seed()).isEqualTo(42L);
        }

        @Test
        @DisplayName("Should update and get value")
        void shouldUpdateAndGetValue() {
            hasher.update("hello".getBytes(StandardCharsets.UTF_8));
            long hash = hasher.getValue();

            assertThat(hash).isNotNegative();
        }

        @Test
        @DisplayName("Should reset state")
        void shouldResetState() {
            hasher.update("hello".getBytes(StandardCharsets.UTF_8));
            long hash1 = hasher.getValue();

            hasher.reset();
            hasher.update("hello".getBytes(StandardCharsets.UTF_8));
            long hash2 = hasher.getValue();

            assertThat(hash1).isEqualTo(hash2);
        }

        @Test
        @DisplayName("Should update with portion of array")
        void shouldUpdateWithPortion() {
            byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
            hasher.update(data, 0, 5);
            long hash = hasher.getValue();

            assertThat(hash).isNotNegative();
        }

        @Test
        @DisplayName("Should reject null data in update")
        void shouldRejectNullDataInUpdate() {
            assertThatThrownBy(() -> hasher.update(null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should reject invalid offset/length in update")
        void shouldRejectInvalidOffsetLengthInUpdate() {
            byte[] data = "hello".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> hasher.update(data, -1, 5))
                .isInstanceOf(IndexOutOfBoundsException.class);
        }

        @Test
        @DisplayName("Should update and hash in one call")
        void shouldUpdateAndHash() {
            long hash = hasher.updateAndHash("hello".getBytes(StandardCharsets.UTF_8));

            assertThat(hash).isNotNegative();
        }

        @Test
        @DisplayName("Should return chaining on update")
        void shouldReturnChainingOnUpdate() {
            XxHash64 result = hasher.update("hello".getBytes(StandardCharsets.UTF_8));

            assertThat(result).isSameAs(hasher);
        }

        @Test
        @DisplayName("Should return chaining on reset")
        void shouldReturnChainingOnReset() {
            XxHash64 result = hasher.reset();

            assertThat(result).isSameAs(hasher);
        }
    }

    @Nested
    @DisplayName("Streaming API")
    class StreamingApiTests {

        @Test
        @DisplayName("Should produce same hash as static method")
        void shouldProduceSameHashAsStaticMethod() {
            byte[] data = "hello world test".getBytes(StandardCharsets.UTF_8);

            long staticHash = XxHash64.hash(data);

            XxHash64 streaming = new XxHash64();
            streaming.update(data);
            long streamingHash = streaming.getValue();

            assertThat(staticHash).isEqualTo(streamingHash);
        }

        @Test
        @DisplayName("Should handle chunked updates")
        void shouldHandleChunkedUpdates() {
            byte[] data = "hello world this is a test".getBytes(StandardCharsets.UTF_8);

            XxHash64 streaming1 = new XxHash64();
            streaming1.update(data);

            XxHash64 streaming2 = new XxHash64();
            streaming2.update(data, 0, 5);
            streaming2.update(data, 5, 5);
            streaming2.update(data, 10, data.length - 10);

            assertThat(streaming1.getValue()).isEqualTo(streaming2.getValue());
        }
    }

    @Nested
    @DisplayName("Positive Hash Guarantee")
    class PositiveHashGuaranteeTests {

        @Test
        @DisplayName("Should always return positive hash for bytes")
        void shouldAlwaysReturnPositiveHashForBytes() {
            for (int i = 0; i < 100; i++) {
                byte[] data = ("test" + i).getBytes(StandardCharsets.UTF_8);
                long hash = XxHash64.hash(data);
                assertThat(hash).isNotNegative();
            }
        }

        @Test
        @DisplayName("Should always return positive hash for strings")
        void shouldAlwaysReturnPositiveHashForStrings() {
            for (int i = 0; i < 100; i++) {
                long hash = XxHash64.hash("test" + i);
                assertThat(hash).isNotNegative();
            }
        }

        @Test
        @DisplayName("Should normalize negative values")
        void shouldNormalizeNegativeValues() {
            long negative = -1L;
            long normalized = XxHash64.normalizeToPositive(negative);

            assertThat(normalized).isNotNegative();
            assertThat(normalized).isEqualTo(negative & XxHash64.POSITIVE_HASH_MASK);
        }

        @Test
        @DisplayName("Positive hash mask should be Long.MAX_VALUE")
        void positiveHashMaskShouldBeLongMaxValue() {
            assertThat(XxHash64.POSITIVE_HASH_MASK).isEqualTo(Long.MAX_VALUE);
        }
    }

    @Nested
    @DisplayName("Test Vectors")
    class TestVectorsTests {

        @Test
        @DisplayName("Should verify test vectors")
        void shouldVerifyTestVectors() {
            assertThat(XxHash64.verifyTestVectors()).isTrue();
        }

        @Test
        @DisplayName("Should hash empty string to expected value")
        void shouldHashEmptyStringToExpectedValue() {
            long hash = XxHash64.hash("");
            // Expected normalized value for empty string with seed 0
            assertThat(hash).isEqualTo(0x6F46DB3751D8E999L);
        }

        @Test
        @DisplayName("Should hash 'a' to expected value")
        void shouldHashAToExpectedValue() {
            long hash = XxHash64.hash("a");
            assertThat(hash).isEqualTo(0x524EC4F1A98C6E5BL);
        }

        @Test
        @DisplayName("Should hash 'abc' to expected value")
        void shouldHashAbcToExpectedValue() {
            long hash = XxHash64.hash("abc");
            assertThat(hash).isEqualTo(0x44BC2CF5AD770999L);
        }

        @Test
        @DisplayName("Should hash 'message digest' to expected value")
        void shouldHashMessageDigestToExpectedValue() {
            long hash = XxHash64.hash("message digest");
            assertThat(hash).isEqualTo(0x066ED728FCEEB3BEL);
        }

        @Test
        @DisplayName("Should hash alphabet to expected value")
        void shouldHashAlphabetToExpectedValue() {
            long hash = XxHash64.hash("abcdefghijklmnopqrstuvwxyz");
            assertThat(hash).isEqualTo(0x4FE1F278FA89835CL);
        }
    }

    @Nested
    @DisplayName("Determinism")
    class DeterminismTests {

        @Test
        @DisplayName("Should produce consistent hashes")
        void shouldProduceConsistentHashes() {
            byte[] data = "consistent test data".getBytes(StandardCharsets.UTF_8);

            long hash1 = XxHash64.hash(data);
            long hash2 = XxHash64.hash(data);
            long hash3 = XxHash64.hash(data);

            assertThat(hash1).isEqualTo(hash2);
            assertThat(hash2).isEqualTo(hash3);
        }

        @Test
        @DisplayName("Should produce different hashes for different inputs")
        void shouldProduceDifferentHashesForDifferentInputs() {
            long hash1 = XxHash64.hash("input1");
            long hash2 = XxHash64.hash("input2");

            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("Should produce different hashes for different seeds")
        void shouldProduceDifferentHashesForDifferentSeeds() {
            byte[] data = "test data".getBytes(StandardCharsets.UTF_8);

            long hash0 = XxHash64.hash(data, 0L);
            long hash1 = XxHash64.hash(data, 1L);
            long hash2 = XxHash64.hash(data, 2L);

            assertThat(hash0).isNotEqualTo(hash1);
            assertThat(hash1).isNotEqualTo(hash2);
        }
    }

    @Nested
    @DisplayName("ToString")
    class ToStringTests {

        @Test
        @DisplayName("Should contain seed in toString")
        void shouldContainSeedInToString() {
            XxHash64 hasher = new XxHash64(0x12345678L);
            String str = hasher.toString();

            assertThat(str).contains("12345678");
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle large data")
        void shouldHandleLargeData() {
            byte[] data = new byte[10000];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (i % 256);
            }

            long hash = XxHash64.hash(data);

            assertThat(hash).isNotNegative();
        }

        @Test
        @DisplayName("Should handle exactly 32 bytes")
        void shouldHandleExactly32Bytes() {
            byte[] data = new byte[32];
            for (int i = 0; i < 32; i++) {
                data[i] = (byte) i;
            }

            long hash = XxHash64.hash(data);

            assertThat(hash).isNotNegative();
        }

        @Test
        @DisplayName("Should handle data larger than 32 bytes")
        void shouldHandleDataLargerThan32Bytes() {
            byte[] data = new byte[100];
            for (int i = 0; i < 100; i++) {
                data[i] = (byte) i;
            }

            long hash = XxHash64.hash(data);

            assertThat(hash).isNotNegative();
        }

        @Test
        @DisplayName("Should handle single byte")
        void shouldHandleSingleByte() {
            byte[] data = new byte[] {0x42};

            long hash = XxHash64.hash(data);

            assertThat(hash).isNotNegative();
        }

        @Test
        @DisplayName("Should handle streaming with large chunks")
        void shouldHandleStreamingWithLargeChunks() {
            XxHash64 hasher = new XxHash64();
            byte[] chunk = new byte[100];
            for (int i = 0; i < 100; i++) {
                chunk[i] = (byte) i;
            }

            hasher.update(chunk);
            hasher.update(chunk);
            hasher.update(chunk);

            long hash = hasher.getValue();

            assertThat(hash).isNotNegative();
        }
    }
}
