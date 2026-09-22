package com.eonmux.excalibur.core.codec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for all codec implementations.
 * Tests encoding, decoding, validation, and round-trip conversions.
 */
class CodecTests {

    private ByteCodec byteCodec;
    private LongCodec longCodec;
    private IntCodec intCodec;
    private StringCodec stringCodec;
    private FloatArrayCodec floatArrayCodec;
    private DoubleCodec doubleCodec;

    @BeforeEach
    void setUp() {
        byteCodec = ByteCodec.getInstance();
        longCodec = LongCodec.getInstance();
        intCodec = IntCodec.getInstance();
        stringCodec = StringCodec.getInstance();
        floatArrayCodec = FloatArrayCodec.getInstance();
        doubleCodec = DoubleCodec.getInstance();
    }

    // ==================== ByteCodec Tests ====================

    @Test
    void testByteCodecEncodeDecodeEmpty() {
        byte[] encoded = byteCodec.encode(new byte[0]);
        byte[] decoded = byteCodec.decode(encoded);
        assertArrayEquals(new byte[0], decoded);
    }

    @Test
    void testByteCodecEncodeDecodeSmall() {
        byte[] data = new byte[] {1, 2, 3, 4, 5};
        byte[] encoded = byteCodec.encode(data);
        byte[] decoded = byteCodec.decode(encoded);
        assertArrayEquals(data, decoded);
    }

    @Test
    void testByteCodecEncodeDecodeLarge() {
        byte[] data = new byte[1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        byte[] encoded = byteCodec.encode(data);
        byte[] decoded = byteCodec.decode(encoded);
        assertArrayEquals(data, decoded);
    }

    @Test
    void testByteCodecEncodeNull() {
        byte[] encoded = byteCodec.encode(null);
        byte[] decoded = byteCodec.decode(encoded);
        assertArrayEquals(new byte[0], decoded);
    }

    @Test
    void testByteCodecIsValidEmpty() {
        assertTrue(byteCodec.isValid(new byte[0]));
    }

    @Test
    void testByteCodecIsValidInvalidLength() {
        assertFalse(byteCodec.isValid(new byte[2]));
        assertFalse(byteCodec.isValid(new byte[1]));
    }

    @Test
    void testByteCodecEncodedSize() {
        assertEquals(4, byteCodec.encodedSize(new byte[0]));
        assertEquals(4 + 100, byteCodec.encodedSize(new byte[100]));
    }

    // ==================== LongCodec Tests ====================

    @Test
    void testLongCodecEncodeDecodeZero() {
        byte[] encoded = longCodec.encode(0L);
        long decoded = longCodec.decode(encoded);
        assertEquals(0L, decoded);
    }

    @Test
    void testLongCodecEncodeDecodePositive() {
        long value = 123456789012345L;
        byte[] encoded = longCodec.encode(value);
        long decoded = longCodec.decode(encoded);
        assertEquals(value, decoded);
    }

    @Test
    void testLongCodecEncodeDecodeNegative() {
        long value = -123456789012345L;
        byte[] encoded = longCodec.encode(value);
        long decoded = longCodec.decode(encoded);
        assertEquals(value, decoded);
    }

    @Test
    void testLongCodecEncodeDecodeMax() {
        byte[] encoded = longCodec.encode(Long.MAX_VALUE);
        long decoded = longCodec.decode(encoded);
        assertEquals(Long.MAX_VALUE, decoded);
    }

    @Test
    void testLongCodecEncodeDecodeMin() {
        byte[] encoded = longCodec.encode(Long.MIN_VALUE);
        long decoded = longCodec.decode(encoded);
        assertEquals(Long.MIN_VALUE, decoded);
    }

    @Test
    void testLongCodecEncodeNull() {
        byte[] encoded = longCodec.encode(null);
        long decoded = longCodec.decode(encoded);
        assertEquals(0L, decoded);
    }

    @Test
    void testLongCodecIsValidEmpty() {
        assertTrue(longCodec.isValid(new byte[0]));
    }

    @Test
    void testLongCodecIsValidTooShort() {
        assertFalse(longCodec.isValid(new byte[7]));
    }

    @Test
    void testLongCodecSizeOf() {
        assertEquals(8, longCodec.sizeOf());
    }

    // ==================== IntCodec Tests ====================

    @Test
    void testIntCodecEncodeDecodeZero() {
        byte[] encoded = intCodec.encode(0);
        int decoded = intCodec.decode(encoded);
        assertEquals(0, decoded);
    }

    @Test
    void testIntCodecEncodeDecodePositive() {
        int value = 123456789;
        byte[] encoded = intCodec.encode(value);
        int decoded = intCodec.decode(encoded);
        assertEquals(value, decoded);
    }

    @Test
    void testIntCodecEncodeDecodeNegative() {
        int value = -123456789;
        byte[] encoded = intCodec.encode(value);
        int decoded = intCodec.decode(encoded);
        assertEquals(value, decoded);
    }

    @Test
    void testIntCodecEncodeDecodeMax() {
        byte[] encoded = intCodec.encode(Integer.MAX_VALUE);
        int decoded = intCodec.decode(encoded);
        assertEquals(Integer.MAX_VALUE, decoded);
    }

    @Test
    void testIntCodecEncodeDecodeMin() {
        byte[] encoded = intCodec.encode(Integer.MIN_VALUE);
        int decoded = intCodec.decode(encoded);
        assertEquals(Integer.MIN_VALUE, decoded);
    }

    @Test
    void testIntCodecEncodeNull() {
        byte[] encoded = intCodec.encode(null);
        int decoded = intCodec.decode(encoded);
        assertEquals(0, decoded);
    }

    @Test
    void testIntCodecIsValidEmpty() {
        assertTrue(intCodec.isValid(new byte[0]));
    }

    @Test
    void testIntCodecIsValidTooShort() {
        assertFalse(intCodec.isValid(new byte[3]));
    }

    @Test
    void testIntCodecSizeOf() {
        assertEquals(4, intCodec.sizeOf());
    }

    // ==================== StringCodec Tests ====================

    @Test
    void testStringCodecEncodeDecodeEmpty() {
        byte[] encoded = stringCodec.encode("");
        String decoded = stringCodec.decode(encoded);
        assertEquals("", decoded);
    }

    @Test
    void testStringCodecEncodeDecodeAscii() {
        String text = "Hello, World!";
        byte[] encoded = stringCodec.encode(text);
        String decoded = stringCodec.decode(encoded);
        assertEquals(text, decoded);
    }

    @Test
    void testStringCodecEncodeDecodeUtf8() {
        String text = "Hello 世界! Café ñoño 🎉";
        byte[] encoded = stringCodec.encode(text);
        String decoded = stringCodec.decode(encoded);
        assertEquals(text, decoded);
    }

    @Test
    void testStringCodecEncodeNull() {
        byte[] encoded = stringCodec.encode(null);
        String decoded = stringCodec.decode(encoded);
        assertEquals("", decoded);
    }

    @Test
    void testStringCodecIsValidEmpty() {
        assertTrue(stringCodec.isValid(new byte[0]));
    }

    @Test
    void testStringCodecIsValidTruncated() {
        byte[] data = stringCodec.encode("Hello");
        // Create truncated data - fewer bytes than needed for length prefix
        assertFalse(stringCodec.isValid(new byte[3]));
        // Also test with partial payload (length says 5 bytes but only 2 present)
        byte[] truncated = new byte[6]; // 4 bytes prefix + 2 bytes data (but length says 5)
        truncated[0] = 0; truncated[1] = 0; truncated[2] = 0; truncated[3] = 5; // length = 5
        truncated[4] = 'H'; truncated[5] = 'e'; // only 2 bytes of payload
        assertFalse(stringCodec.isValid(truncated));
    }

    @Test
    void testStringCodecEncodedSize() {
        assertEquals(4, stringCodec.encodedSize(""));
        assertEquals(4 + "Hello".getBytes().length, stringCodec.encodedSize("Hello"));
    }

    // ==================== DoubleCodec Tests ====================

    @Test
    void testDoubleCodecEncodeDecodeZero() {
        byte[] encoded = doubleCodec.encode(0.0);
        double decoded = doubleCodec.decode(encoded);
        assertEquals(0.0, decoded, 0.000000001);
    }

    @Test
    void testDoubleCodecEncodeDecodePositive() {
        double value = 123.456789012345;
        byte[] encoded = doubleCodec.encode(value);
        double decoded = doubleCodec.decode(encoded);
        assertEquals(value, decoded, 0.0000000000001);
    }

    @Test
    void testDoubleCodecEncodeDecodeNegative() {
        double value = -123.456789012345;
        byte[] encoded = doubleCodec.encode(value);
        double decoded = doubleCodec.decode(encoded);
        assertEquals(value, decoded, 0.0000000000001);
    }

    @Test
    void testDoubleCodecEncodeDecodeMax() {
        byte[] encoded = doubleCodec.encode(Double.MAX_VALUE);
        double decoded = doubleCodec.decode(encoded);
        assertEquals(Double.MAX_VALUE, decoded, 0.000000001);
    }

    @Test
    void testDoubleCodecEncodeNull() {
        byte[] encoded = doubleCodec.encode(null);
        double decoded = doubleCodec.decode(encoded);
        assertTrue(Double.isNaN(decoded), "Null should decode to NaN");
    }

    @Test
    void testDoubleCodecIsValidEmpty() {
        assertTrue(doubleCodec.isValid(new byte[0]));
    }

    @Test
    void testDoubleCodecIsValidTooShort() {
        assertFalse(doubleCodec.isValid(new byte[7]));
    }

    @Test
    void testDoubleCodecSizeOf() {
        assertEquals(8, doubleCodec.sizeOf());
    }

    // ==================== FloatArrayCodec Tests ====================

    @Test
    void testFloatArrayCodecEncodeDecodeEmpty() {
        float[] data = new float[0];
        byte[] encoded = floatArrayCodec.encode(data);
        float[] decoded = floatArrayCodec.decode(encoded);
        assertArrayEquals(data, decoded);
    }

    @Test
    void testFloatArrayCodecEncodeDecodeSmall() {
        float[] data = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
        byte[] encoded = floatArrayCodec.encode(data);
        float[] decoded = floatArrayCodec.decode(encoded);
        assertArrayEquals(data, decoded);
    }

    @Test
    void testFloatArrayCodecEncodeDecodeMedium() {
        float[] data = new float[1000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (float) (i + 1) / 10000.0f;
        }
        byte[] encoded = floatArrayCodec.encode(data);
        float[] decoded = floatArrayCodec.decode(encoded);
        for (int i = 0; i < data.length; i++) {
            assertEquals(data[i], decoded[i], 0.0000001f);
        }
    }

    @Test
    void testFloatArrayCodecEncodeNull() {
        byte[] encoded = floatArrayCodec.encode(null);
        float[] decoded = floatArrayCodec.decode(encoded);
        assertArrayEquals(new float[0], decoded);
    }

    @Test
    void testFloatArrayCodecIsValidEmpty() {
        assertTrue(floatArrayCodec.isValid(new byte[0]));
    }

    @Test
    void testFloatArrayCodecEncodedSize() {
        assertEquals(4, floatArrayCodec.encodedSize(new float[0]));
        assertEquals(4 + 100 * 4, floatArrayCodec.encodedSize(new float[100]));
    }

    @Test
    void testFloatArrayCodecSizeOf() {
        assertEquals(4, floatArrayCodec.sizeOf());
    }

    // ==================== Round-Trip Tests ====================

    @Test
    void testAllCodecsRoundTrip() {
        // Test with various values
        byte[] byteData = new byte[]{1, 2, 3, 4, 5};
        long longValue = 123456789012345L;
        int intValue = 987654321;
        String stringValue = "Test string with Unicode: café ñoño";
        float[] floatData = {1.0f, 2.5f, 3.14159f, Float.MAX_VALUE, Float.MIN_VALUE};
        double doubleValue = 123.456789012345;

        // Round-trip each type
        byte[] encodedByte = byteCodec.encode(byteData);
        byte[] decodedByte = byteCodec.decode(encodedByte);
        assertArrayEquals(byteData, decodedByte);

        byte[] encodedLong = longCodec.encode(longValue);
        long decodedLong = longCodec.decode(encodedLong);
        assertEquals(longValue, decodedLong);

        byte[] encodedInt = intCodec.encode(intValue);
        int decodedInt = intCodec.decode(encodedInt);
        assertEquals(intValue, decodedInt);

        byte[] encodedString = stringCodec.encode(stringValue);
        String decodedString = stringCodec.decode(encodedString);
        assertEquals(stringValue, decodedString);

        byte[] encodedFloat = floatArrayCodec.encode(floatData);
        float[] decodedFloat = floatArrayCodec.decode(encodedFloat);
        for (int i = 0; i < floatData.length; i++) {
            assertEquals(floatData[i], decodedFloat[i], 0.0000001f);
        }

        byte[] encodedDouble = doubleCodec.encode(doubleValue);
        double decodedDouble = doubleCodec.decode(encodedDouble);
        assertEquals(doubleValue, decodedDouble, 0.0000000000001);
    }

    // ==================== Offset Decoding Tests ====================

    @Test
    void testLongDecodeOffset() {
        long value = 0x123456789ABCDEF0L;
        byte[] encoded = longCodec.encode(value);
        long decoded = longCodec.decode(encoded, 0);
        assertEquals(value, decoded);
    }

    @Test
    void testIntDecodeOffset() {
        int value = 0x12345678;
        byte[] encoded = intCodec.encode(value);
        int decoded = intCodec.decode(encoded, 0);
        assertEquals(value, decoded);
    }

    @Test
    void testStringDecodeOffset() {
        String value = "Hello, World!";
        byte[] encoded = stringCodec.encode(value);
        String decoded = stringCodec.decode(encoded, 0);
        assertEquals(value, decoded);
    }

    @Test
    void testFloatArrayDecodeOffset() {
        float[] value = {1.0f, 2.0f, 3.0f};
        byte[] encoded = floatArrayCodec.encode(value);
        float[] decoded = floatArrayCodec.decode(encoded, 0);
        assertArrayEquals(value, decoded);
    }

    // ==================== Instance/Singleton Tests ====================

    @Test
    void testByteCodecSingleton() {
        ByteCodec c1 = ByteCodec.getInstance();
        ByteCodec c2 = ByteCodec.getInstance();
        assertSame(c1, c2);
    }

    @Test
    void testLongCodecSingleton() {
        LongCodec c1 = LongCodec.getInstance();
        LongCodec c2 = LongCodec.getInstance();
        assertSame(c1, c2);
    }

    @Test
    void testIntCodecSingleton() {
        IntCodec c1 = IntCodec.getInstance();
        IntCodec c2 = IntCodec.getInstance();
        assertSame(c1, c2);
    }

    @Test
    void testStringCodecSingleton() {
        StringCodec c1 = StringCodec.getInstance();
        StringCodec c2 = StringCodec.getInstance();
        assertSame(c1, c2);
    }

    @Test
    void testFloatArrayCodecSingleton() {
        FloatArrayCodec c1 = FloatArrayCodec.getInstance();
        FloatArrayCodec c2 = FloatArrayCodec.getInstance();
        assertSame(c1, c2);
    }

    @Test
    void testDoubleCodecSingleton() {
        DoubleCodec c1 = DoubleCodec.getInstance();
        DoubleCodec c2 = DoubleCodec.getInstance();
        assertSame(c1, c2);
    }

    // ==================== Exception Tests ====================

    @Test
    void testLongDecodeInvalidData() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            longCodec.decode(new byte[7]));
        assertTrue(e.getMessage().contains("insufficient bytes"));
    }

    @Test
    void testIntDecodeInvalidData() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            intCodec.decode(new byte[3]));
        assertTrue(e.getMessage().contains("insufficient bytes"));
    }

    @Test
    void testStringDecodeInvalidData() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
            stringCodec.decode(new byte[2]));
        assertTrue(e.getMessage().contains("Invalid encoded data"));
    }

    // ==================== Test Vector Verification ====================

    @Test
    void testLongCodecTestVectors() {
        Object[][] testVectors = {
            {0L, 0L},
            {1L, 1L},
            {Long.MAX_VALUE, Long.MAX_VALUE},
            {Long.MIN_VALUE, Long.MIN_VALUE},
            {-12345L, -12345L}
        };

        for (Object[] vector : testVectors) {
            long input = (Long) vector[0];
            long expected = (Long) vector[1];

            byte[] encoded = longCodec.encode((Long) input);
            long decoded = longCodec.decode(encoded);
            assertEquals(expected, decoded, "Failed for input " + input);
        }
    }

    @Test
    void testIntCodecTestVectors() {
        Object[][] testVectors = {
            {0, 0},
            {1, 1},
            {Integer.MAX_VALUE, Integer.MAX_VALUE},
            {Integer.MIN_VALUE, Integer.MIN_VALUE},
            {-12345, -12345}
        };

        for (Object[] vector : testVectors) {
            int input = (Integer) vector[0];
            int expected = (Integer) vector[1];

            byte[] encoded = intCodec.encode((Integer) input);
            int decoded = intCodec.decode(encoded);
            assertEquals(expected, decoded, "Failed for input " + input);
        }
    }

    /**
     * Main method for running tests from command line.
     */
    public static void main(String[] args) {
        System.out.println("Running Codec Tests...");
        System.out.println("======================");

        new CodecTests().testAll();
    }

    private void testAll() {
        // ByteCodec tests
        System.out.println("\nTesting ByteCodec...");
        assertByteCodecTests();

        // LongCodec tests
        System.out.println("\nTesting LongCodec...");
        assertLongCodecTests();

        // IntCodec tests
        System.out.println("\nTesting IntCodec...");
        assertIntCodecTests();

        // StringCodec tests
        System.out.println("\nTesting StringCodec...");
        assertStringCodecTests();

        // DoubleCodec tests
        System.out.println("\nTesting DoubleCodec...");
        assertDoubleCodecTests();

        // FloatArrayCodec tests
        System.out.println("\nTesting FloatArrayCodec...");
        assertFloatArrayCodecTests();

        // Round-trip tests
        System.out.println("\nTesting Round-Trips...");
        assertRoundTripTests();

        System.out.println("\n======================");
        System.out.println("All Codec Tests Passed!");
    }

    private void assertByteCodecTests() {
        ByteCodec codec = ByteCodec.getInstance();
        
        byte[] empty = codec.encode(new byte[0]);
        assertArrayEquals(new byte[4], empty);
        
        byte[] data1 = codec.encode(new byte[]{1, 2, 3});
        byte[] data2 = codec.encode(new byte[]{1, 2, 3});
        assertArrayEquals(data1, data2);
        
        byte[] decoded = codec.decode(data1);
        assertArrayEquals(new byte[]{1, 2, 3}, decoded);
    }

    private void assertLongCodecTests() {
        LongCodec codec = LongCodec.getInstance();
        
        byte[] encoded = codec.encode(123456789012345L);
        long decoded = codec.decode(encoded);
        assertEquals(123456789012345L, decoded);
        
        byte[] encodedNeg = codec.encode(-123456789012345L);
        long decodedNeg = codec.decode(encodedNeg);
        assertEquals(-123456789012345L, decodedNeg);
    }

    private void assertIntCodecTests() {
        IntCodec codec = IntCodec.getInstance();
        
        byte[] encoded = codec.encode(123456789);
        int decoded = codec.decode(encoded);
        assertEquals(123456789, decoded);
        
        byte[] encodedNeg = codec.encode(-123456789);
        int decodedNeg = codec.decode(encodedNeg);
        assertEquals(-123456789, decodedNeg);
    }

    private void assertStringCodecTests() {
        StringCodec codec = StringCodec.getInstance();
        
        byte[] encoded = codec.encode("Hello, World!");
        String decoded = codec.decode(encoded);
        assertEquals("Hello, World!", decoded);
        
        byte[] encodedUtf8 = codec.encode("Café ñoño 世界");
        String decodedUtf8 = codec.decode(encodedUtf8);
        assertEquals("Café ñoño 世界", decodedUtf8);
    }

    private void assertDoubleCodecTests() {
        DoubleCodec codec = DoubleCodec.getInstance();
        
        byte[] encoded = codec.encode(123.456789012345);
        double decoded = codec.decode(encoded);
        assertEquals(123.456789012345, decoded, 0.0000000000001);
    }

    private void assertFloatArrayCodecTests() {
        FloatArrayCodec codec = FloatArrayCodec.getInstance();
        
        float[] data = {1.0f, 2.5f, 3.14159f, 0.0f, Float.MAX_VALUE};
        byte[] encoded = codec.encode(data);
        float[] decoded = codec.decode(encoded);
        
        for (int i = 0; i < data.length; i++) {
            assertEquals(data[i], decoded[i], 0.0000001f);
        }
    }

    private void assertRoundTripTests() {
        // Various test values
        byte[] byteData = "Hello".getBytes();
        long longVal = 0x123456789ABCDEF0L;
        int intVal = 0x12345678;
        String stringVal = "Unicode: café ñoño 🎉";
        float[] floatVal = {1.0f, 2.5f, 3.14159f};
        double doubleVal = 123.456789012345;

        // Round-trip each
        byte[] b1 = byteCodec.encode(byteData);
        assertArrayEquals(byteData, byteCodec.decode(b1));

        byte[] l1 = longCodec.encode(longVal);
        assertEquals(longVal, longCodec.decode(l1));

        byte[] i1 = intCodec.encode(intVal);
        assertEquals(intVal, intCodec.decode(i1));

        byte[] s1 = stringCodec.encode(stringVal);
        assertEquals(stringVal, stringCodec.decode(s1));

        byte[] f1 = floatArrayCodec.encode(floatVal);
        assertArrayEquals(floatVal, floatArrayCodec.decode(f1));

        byte[] d1 = doubleCodec.encode(doubleVal);
        assertEquals(doubleVal, doubleCodec.decode(d1), 0.0000000000001);
    }
}
