package com.eonmux.excalibur.llm.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for {@link JsonWriter}.
 *
 * <h2>Domain Correctness Verification</h2>
 *
 * <h3>UTF-8 Encoding</h3>
 * <ul>
 *   <li>ASCII characters (0x00-0x7F) are encoded correctly</li>
 *   <li>Latin-1 Supplement characters (0x80-0xFF) are encoded correctly</li>
 *   <li>BMP Unicode characters are preserved</li>
 *   <li>Surrogate pairs (characters beyond BMP) are handled correctly</li>
 *   <li>Round-trip encoding: string → UTF-8 bytes → string is lossless</li>
 * </ul>
 *
 * <h3>String Escaping</h3>
 * <ul>
 *   <li>Quotation mark and backslash are escaped</li>
 *   <li>Control characters (0x00-0x1F) are escaped as \\uXXXX</li>
 *   <li>Common escapes (\b, \f, \n, \r, \t) use shorthand</li>
 *   <li>DEL character (0x7F) is escaped</li>
 *   <li>JSON line terminators (U+2028, U+2029) are escaped</li>
 *   <li>Unicode characters pass through unmodified</li>
 * </ul>
 *
 * <h3>JSON Structure</h3>
 * <ul>
 *   <li>Objects have balanced braces</li>
 *   <li>Arrays have balanced brackets</li>
 *   <li>Strings have balanced quotes</li>
 *   <li>Numbers are formatted without locale separators</li>
 *   <li>Booleans and null are correct literals</li>
 * </ul>
 */
@Timeout(value = 10)
class JsonWriterTest {

    // ============= UTF-8 Encoding Tests =============

    @Test
    @DisplayName("ASCII text encodes correctly to UTF-8")
    void encodeUtf8_asciiText_preservesContent() {
        String input = "Hello, World! 123";

        byte[] encoded = JsonWriter.encodeUtf8(input);
        String decoded = JsonWriter.decodeUtf8(encoded);

        assertEquals(input, decoded);
        // ASCII should be 1 byte per character
        assertEquals(input.length(), encoded.length);
    }

    @Test
    @DisplayName("Latin-1 characters encode correctly to UTF-8")
    void encodeUtf8_latin1Characters_preservesContent() {
        String input = "café résumé naïve €";

        byte[] encoded = JsonWriter.encodeUtf8(input);
        String decoded = JsonWriter.decodeUtf8(encoded);

        assertEquals(input, decoded);
        // Latin-1 chars are 1-2 bytes in UTF-8, € is 3 bytes
        assertTrue(encoded.length > input.length());
    }

    @Test
    @DisplayName("CJK characters encode correctly to UTF-8")
    void encodeUtf8_cjkCharacters_preservesContent() {
        String input = "日本語 中文 한국어";

        byte[] encoded = JsonWriter.encodeUtf8(input);
        String decoded = JsonWriter.decodeUtf8(encoded);

        assertEquals(input, decoded);
        // CJK chars are 3 bytes each in UTF-8
        assertTrue(encoded.length > input.length());
    }

    @Test
    @DisplayName("Emoji encode correctly to UTF-8")
    void encodeUtf8_emoji_preservesContent() {
        String input = "😀🎉🚀💻";

        byte[] encoded = JsonWriter.encodeUtf8(input);
        String decoded = JsonWriter.decodeUtf8(encoded);

        assertEquals(input, decoded);
        // Emoji (surrogate pairs) are 4 bytes each in UTF-8
        assertTrue(encoded.length > input.length());
    }

    @Test
    @DisplayName("Mixed Unicode content encodes correctly")
    void encodeUtf8_mixedUnicode_preservesContent() {
        String input = "Hello 世界 🌍 café \u00E9\u00E8\u00EA";

        byte[] encoded = JsonWriter.encodeUtf8(input);
        String decoded = JsonWriter.decodeUtf8(encoded);

        assertEquals(input, decoded);
    }

    @Test
    @DisplayName("Null input returns empty bytes")
    void encodeUtf8_nullInput_returnsEmpty() {
        byte[] result = JsonWriter.encodeUtf8(null);

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Null bytes returns empty string")
    void decodeUtf8_nullBytes_returnsEmpty() {
        String result = JsonWriter.decodeUtf8(null);

        assertNotNull(result);
        assertEquals("", result);
    }

    // ============= String Escaping Tests - Required Characters =============

    @Test
    @DisplayName("Quotation mark is escaped")
    void escapeString_quotationMark_escaped() {
        String input = "Say \"hello\"";
        String expected = "Say \\\"hello\\\"";

        assertEquals(expected, JsonWriter.escapeString(input));
    }

    @Test
    @DisplayName("Backslash is escaped")
    void escapeString_backslash_escaped() {
        String input = "path\\to\\file";
        String expected = "path\\\\to\\\\file";

        assertEquals(expected, JsonWriter.escapeString(input));
    }

    @Test
    @DisplayName("Mixed quotes and backslashes are escaped")
    void escapeString_mixedQuotesAndBackslashes_escaped() {
        String input = "\"path\\to\\file\"";
        String expected = "\\\"path\\\\to\\\\file\\\"";

        assertEquals(expected, JsonWriter.escapeString(input));
    }

    // ============= String Escaping Tests - Control Characters =============

    @Test
    @DisplayName("Backspace is escaped as \\b")
    void escapeString_backspace_escaped() {
        String input = "hello\bworld";
        String expected = "hello\\bworld";

        assertEquals(expected, JsonWriter.escapeString(input));
    }

    @Test
    @DisplayName("Form feed is escaped as \\f")
    void escapeString_formFeed_escaped() {
        String input = "hello\fworld";
        String expected = "hello\\fworld";

        assertEquals(expected, JsonWriter.escapeString(input));
    }

    @Test
    @DisplayName("Newline is escaped as \\n")
    void escapeString_newline_escaped() {
        String input = "hello\nworld";
        String expected = "hello\\nworld";

        assertEquals(expected, JsonWriter.escapeString(input));
    }

    @Test
    @DisplayName("Carriage return is escaped as \\r")
    void escapeString_carriageReturn_escaped() {
        String input = "hello\rworld";
        String expected = "hello\\rworld";

        assertEquals(expected, JsonWriter.escapeString(input));
    }

    @Test
    @DisplayName("Tab is escaped as \\t")
    void escapeString_tab_escaped() {
        String input = "hello\tworld";
        String expected = "hello\\tworld";

        assertEquals(expected, JsonWriter.escapeString(input));
    }

    @Test
    @DisplayName("Null character (0x00) is escaped as \\u0000")
    void escapeString_nullCharacter_escaped() {
        String input = "hello\u0000world";
        String expected = "hello\\u0000world";

        assertEquals(expected, JsonWriter.escapeString(input));
    }

    @Test
    @DisplayName("All control characters (0x01-0x1F) are escaped")
    void escapeString_allControlCharacters_escaped() {
        // Test all control characters except those with shorthand escapes
        int[] noShorthand = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
                             0x0B, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14,
                             0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C,
                             0x1D, 0x1E, 0x1F};

        for (int code : noShorthand) {
            String input = "a" + (char) code + "b";
            String expected = "a\\u" + String.format("%04x", code) + "b";

            assertEquals(expected, JsonWriter.escapeString(input),
                "Control character 0x" + String.format("%02x", code) + " should be escaped");
        }
    }

    @Test
    @DisplayName("DEL character (0x7F) is escaped")
    void escapeString_delCharacter_escaped() {
        String input = "hello\u007Fworld";
        String expected = "hello\\u007fworld";

        assertEquals(expected, JsonWriter.escapeString(input));
    }

    // ============= String Escaping Tests - JSON Line Terminators =============

    @Test
    @DisplayName("Line separator (U+2028) is escaped")
    void escapeString_lineSeparator_escaped() {
        String input = "hello\u2028world";
        String expected = "hello\\u2028world";

        assertEquals(expected, JsonWriter.escapeString(input));
    }

    @Test
    @DisplayName("Paragraph separator (U+2029) is escaped")
    void escapeString_paragraphSeparator_escaped() {
        String input = "hello\u2029world";
        String expected = "hello\\u2029world";

        assertEquals(expected, JsonWriter.escapeString(input));
    }

    // ============= String Escaping Tests - Unicode Preservation =============

    @Test
    @DisplayName("BMP Unicode characters pass through unmodified")
    void escapeString_bmpUnicode_preserved() {
        String input = "日本語 한국어 العربية עברית";

        assertEquals(input, JsonWriter.escapeString(input));
    }

    @Test
    @DisplayName("Emoji (surrogate pairs) pass through unmodified")
    void escapeString_emoji_preserved() {
        String input = "Hello 😀 World 🎉";

        assertEquals(input, JsonWriter.escapeString(input));
    }

    @Test
    @DisplayName("Extended Latin characters pass through unmodified")
    void escapeString_extendedLatin_preserved() {
        String input = "café résumé naïve Øresund";

        assertEquals(input, JsonWriter.escapeString(input));
    }

    @Test
    @DisplayName("Mathematical symbols pass through unmodified")
    void escapeString_mathSymbols_preserved() {
        String input = "∑ ∫ π √ ∞ ≠ ≤ ≥";

        assertEquals(input, JsonWriter.escapeString(input));
    }

    // ============= String Escaping Tests - Edge Cases =============

    @Test
    @DisplayName("Null input returns empty string")
    void escapeString_nullInput_returnsEmpty() {
        assertEquals("", JsonWriter.escapeString(null));
    }

    @Test
    @DisplayName("Empty string returns empty string")
    void escapeString_emptyInput_returnsEmpty() {
        assertEquals("", JsonWriter.escapeString(""));
    }

    @Test
    @DisplayName("String with no special characters is unchanged")
    void escapeString_noSpecialChars_unchanged() {
        String input = "Hello World 123";

        assertEquals(input, JsonWriter.escapeString(input));
        // Should return same instance for optimization
        assertSame(input, JsonWriter.escapeString(input));
    }

    @Test
    @DisplayName("String with only special characters is fully escaped")
    void escapeString_onlySpecialChars_fullyEscaped() {
        String input = "\"\\\n\t";
        String expected = "\\\"\\\\\\n\\t";

        assertEquals(expected, JsonWriter.escapeString(input));
    }

    @Test
    @DisplayName("Long string with mixed content escapes correctly")
    void escapeString_longMixedContent_escapesCorrectly() {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            input.append("text").append(i).append("\n\"");
        }

        String result = JsonWriter.escapeString(input.toString());

        // Verify newlines and quotes are escaped
        assertFalse(result.contains("\n"));
        assertFalse(result.contains("\"") && !result.contains("\\\""));
        assertTrue(result.contains("\\n"));
        assertTrue(result.contains("\\\""));
    }

    // ============= JSON Object Writing Tests =============

    @Test
    @DisplayName("Empty object produces {}")
    void writeObject_empty_returnsEmptyObject() {
        String result = JsonWriter.writeObject(Map.of());

        assertEquals("{}", result);
    }

    @Test
    @DisplayName("Simple object with string values")
    void writeObject_stringValues_producesValidJson() {
        Map<String, Object> map = Map.of(
            "name", "test",
            "value", "hello"
        );

        String result = JsonWriter.writeObject(map);

        assertTrue(result.startsWith("{"));
        assertTrue(result.endsWith("}"));
        assertTrue(result.contains("\"name\":\"test\""));
        assertTrue(result.contains("\"value\":\"hello\""));
    }

    @Test
    @DisplayName("Object with special characters in values escapes correctly")
    void writeObject_specialCharacters_escapesCorrectly() {
        Map<String, Object> map = Map.of(
            "text", "Say \"hello\"\nWorld"
        );

        String result = JsonWriter.writeObject(map);

        assertTrue(result.contains("\\\"hello\\\""));
        assertTrue(result.contains("\\n"));
    }

    @Test
    @DisplayName("Object with numeric values")
    void writeObject_numericValues_producesValidJson() {
        Map<String, Object> map = Map.of(
            "int", 42,
            "double", 3.14,
            "long", 1234567890123456789L
        );

        String result = JsonWriter.writeObject(map);

        assertTrue(result.contains("\"int\":42"));
        assertTrue(result.contains("\"double\":3.14"));
        assertTrue(result.contains("\"long\":1234567890123456789"));
    }

    @Test
    @DisplayName("Object with boolean values")
    void writeObject_booleanValues_producesValidJson() {
        Map<String, Object> map = Map.of(
            "yes", true,
            "no", false
        );

        String result = JsonWriter.writeObject(map);

        assertTrue(result.contains("\"yes\":true"));
        assertTrue(result.contains("\"no\":false"));
    }

    @Test
    @DisplayName("Object with null value")
    void writeObject_nullValue_producesNull() {
        // Map.of() doesn't allow null values, so use HashMap
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("empty", null);

        String result = JsonWriter.writeObject(map);

        assertTrue(result.contains("\"empty\":null"));
    }

    @Test
    @DisplayName("Nested object produces correct JSON")
    void writeObject_nestedObject_producesValidJson() {
        Map<String, Object> inner = Map.of("key", "value");
        Map<String, Object> outer = Map.of("nested", inner);

        String result = JsonWriter.writeObject(outer);

        assertTrue(result.contains("\"nested\":"));
        assertTrue(result.contains("{\"key\":\"value\"}"));
    }

    // ============= JSON Array Writing Tests =============

    @Test
    @DisplayName("Empty array produces []")
    void writeArray_empty_returnsEmptyArray() {
        String result = JsonWriter.writeArray(List.of());

        assertEquals("[]", result);
    }

    @Test
    @DisplayName("Array with string values")
    void writeArray_stringValues_producesValidJson() {
        List<Object> list = List.of("a", "b", "c");

        String result = JsonWriter.writeArray(list);

        assertEquals("[\"a\",\"b\",\"c\"]", result);
    }

    @Test
    @DisplayName("Array with numeric values")
    void writeArray_numericValues_producesValidJson() {
        List<Object> list = List.of(1, 2, 3.14);

        String result = JsonWriter.writeArray(list);

        assertEquals("[1,2,3.14]", result);
    }

    @Test
    @DisplayName("Array with mixed types")
    void writeArray_mixedTypes_producesValidJson() {
        // List.of() doesn't allow null values, so use ArrayList
        List<Object> list = new java.util.ArrayList<>();
        list.add("text");
        list.add(42);
        list.add(true);
        list.add(null);

        String result = JsonWriter.writeArray(list);

        assertEquals("[\"text\",42,true,null]", result);
    }

    // ============= JSON Number Writing Tests =============

    @Test
    @DisplayName("Integer values have no decimal point")
    void writeNumber_integer_noDecimal() {
        StringBuilder sb = new StringBuilder();
        JsonWriter.writeNumber(sb, 42);

        assertEquals("42", sb.toString());
    }

    @Test
    @DisplayName("Double values preserve decimal")
    void writeNumber_double_preservesDecimal() {
        StringBuilder sb = new StringBuilder();
        JsonWriter.writeNumber(sb, 3.14159);

        assertEquals("3.14159", sb.toString());
    }

    @Test
    @DisplayName("NaN produces null")
    void writeNumber_nan_producesNull() {
        StringBuilder sb = new StringBuilder();
        JsonWriter.writeNumber(sb, Double.NaN);

        assertEquals("null", sb.toString());
    }

    @Test
    @DisplayName("Positive infinity produces null")
    void writeNumber_positiveInfinity_producesNull() {
        StringBuilder sb = new StringBuilder();
        JsonWriter.writeNumber(sb, Double.POSITIVE_INFINITY);

        assertEquals("null", sb.toString());
    }

    @Test
    @DisplayName("Negative infinity produces null")
    void writeNumber_negativeInfinity_producesNull() {
        StringBuilder sb = new StringBuilder();
        JsonWriter.writeNumber(sb, Double.NEGATIVE_INFINITY);

        assertEquals("null", sb.toString());
    }

    @Test
    @DisplayName("Float NaN produces null")
    void writeNumber_floatNan_producesNull() {
        StringBuilder sb = new StringBuilder();
        JsonWriter.writeNumber(sb, Float.NaN);

        assertEquals("null", sb.toString());
    }

    // ============= JSON Validation Tests =============

    @Test
    @DisplayName("Valid JSON object is recognized")
    void isValidJson_validObject_returnsTrue() {
        String json = "{\"key\":\"value\"}";

        assertTrue(JsonWriter.isValidJson(json));
    }

    @Test
    @DisplayName("Valid JSON array is recognized")
    void isValidJson_validArray_returnsTrue() {
        String json = "[1,2,3]";

        assertTrue(JsonWriter.isValidJson(json));
    }

    @Test
    @DisplayName("Nested JSON is recognized")
    void isValidJson_nestedJson_returnsTrue() {
        String json = "{\"outer\":{\"inner\":[1,2,3]}}";

        assertTrue(JsonWriter.isValidJson(json));
    }

    @Test
    @DisplayName("Null returns false")
    void isValidJson_null_returnsFalse() {
        assertFalse(JsonWriter.isValidJson(null));
    }

    @Test
    @DisplayName("Empty string returns false")
    void isValidJson_empty_returnsFalse() {
        assertFalse(JsonWriter.isValidJson(""));
    }

    @Test
    @DisplayName("Unbalanced braces return false")
    void isValidJson_unbalancedBraces_returnsFalse() {
        assertFalse(JsonWriter.isValidJson("{\"key\":\"value\""));
    }

    @Test
    @DisplayName("Unbalanced brackets return false")
    void isValidJson_unbalancedBrackets_returnsFalse() {
        assertFalse(JsonWriter.isValidJson("[1,2,3"));
    }

    @Test
    @DisplayName("Unclosed string returns false")
    void isValidJson_unclosedString_returnsFalse() {
        assertFalse(JsonWriter.isValidJson("{\"key\":\"value}"));
    }

    // ============= Integration Tests - Full Request Body =============

    @Test
    @DisplayName("Embedding request body is valid JSON with proper escaping")
    void embeddingRequestBody_validJsonAndUtf8() {
        String text = "What is café? 日本語 emoji: 🎉";
        String model = "text-embedding-3-small";

        // Simulate the request body construction
        String escapedText = JsonWriter.escapeString(text);
        String escapedModel = JsonWriter.escapeString(model);
        String requestBody = "{\"model\":\"" + escapedModel + "\",\"input\":\"" + escapedText + "\"}";

        // Verify valid JSON
        assertTrue(JsonWriter.isValidJson(requestBody));

        // Verify UTF-8 round-trip
        byte[] encoded = JsonWriter.encodeUtf8(requestBody);
        String decoded = JsonWriter.decodeUtf8(encoded);
        assertEquals(requestBody, decoded);
    }

    @Test
    @DisplayName("Relevancy request body handles special characters")
    void relevancyRequestBody_specialCharacters_validJson() {
        String query = "What is \"machine learning\"?";
        String candidate = "A field\nwith\nnewlines\tand\ttabs";

        String escapedQuery = JsonWriter.escapeString(query);
        String escapedCandidate = JsonWriter.escapeString(candidate);

        // Build minimal request body
        String requestBody = "{\"query\":\"" + escapedQuery + "\",\"candidate\":\"" + escapedCandidate + "\"}";

        // Verify valid JSON
        assertTrue(JsonWriter.isValidJson(requestBody));

        // Verify special chars are escaped
        assertTrue(requestBody.contains("\\\""));
        assertTrue(requestBody.contains("\\n"));
        assertTrue(requestBody.contains("\\t"));
    }

    @Test
    @DisplayName("Full request body with Unicode preserves characters")
    void fullRequestBody_unicodePreserved() {
        String text = "Test with 世界 and 🚀 and café";

        String escaped = JsonWriter.escapeString(text);
        String requestBody = "{\"input\":\"" + escaped + "\"}";

        // Unicode should be preserved (not escaped)
        assertTrue(requestBody.contains("世界"));
        assertTrue(requestBody.contains("🚀"));
        assertTrue(requestBody.contains("café"));
    }

    // ============= Domain Correctness Verification =============

    @Test
    @DisplayName("Domain correctness: all escape sequences are valid JSON")
    void domainCorrectness_escapeSequences_validJson() {
        // Test string with all escape types
        String input = "Quote: \" Backslash: \\ Newline: \n Tab: \t CR: \r Null: \u0000 DEL: \u007F LS: \u2028 PS: \u2029";
        String escaped = JsonWriter.escapeString(input);

        // Wrap in JSON object and validate
        String json = "{\"text\":\"" + escaped + "\"}";
        assertTrue(JsonWriter.isValidJson(json),
            "Escaped string should produce valid JSON");

        // Verify UTF-8 encoding works
        byte[] encoded = JsonWriter.encodeUtf8(json);
        String decoded = JsonWriter.decodeUtf8(encoded);
        assertEquals(json, decoded, "UTF-8 round-trip should be lossless");
    }

    @Test
    @DisplayName("Domain correctness: JSON special characters are always escaped")
    void domainCorrectness_jsonSpecialChars_alwaysEscaped() {
        // Characters that MUST be escaped in JSON
        String mustEscape = "\"\\";

        for (char c : mustEscape.toCharArray()) {
            String input = String.valueOf(c);
            String escaped = JsonWriter.escapeString(input);

            // Should not contain unescaped version in JSON context
            // Wrap in an object since isValidJson requires { or [ prefix
            String json = "{\"char\":\"" + escaped + "\"}";
            assertTrue(JsonWriter.isValidJson(json),
                "Character '" + c + "' should be escaped to produce valid JSON");
        }
    }
}
