package excalibur.api.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The JSON this server reads and writes.
 *
 * <p>The property that matters most is the one the field-scanning reader in
 * {@code excalibur.api.protocol.JsonText} cannot offer: a field is found in its own scope, and a
 * string that happens to spell a field name is still just a string. Searching the whole document
 * for a quoted name and reading from the first hit answers with whatever it lands on, silently,
 * and the strings passing through here are entity names and questions written by a model - so
 * landing on one is a matter of what the corpus is about.</p>
 */
@DisplayName("JSON")
class JsonTest {

    @Test
    @DisplayName("a string that spells a field name is not mistaken for that field")
    void aStringSpellingAFieldNameIsNotAField() {
        // Reachable straight through search_graph: context_entities carries names a model chose,
        // and a corpus about databases contains an entity called "namespace". Scanning for the
        // quoted name finds the list entry first and answers "namespace" instead of "tenant-a".
        Object parsed = Json.parse(
            "{\"context_entities\":[\"namespace\"],\"namespace\":\"tenant-a\"}");

        assertThat(Json.stringAt(parsed, "namespace"))
            .as("the field, not the entity that shares its name")
            .isEqualTo("tenant-a");
        assertThat(Json.at(parsed, "context_entities")).isEqualTo(List.of("namespace"));
    }

    @Test
    @DisplayName("a nested key does not answer for the outer one")
    void anInnerKeyDoesNotShadowTheOuterOne() {
        // The shape of every tools/call: arguments nested inside params, and no reason the two
        // levels cannot use the same field name.
        Object parsed = Json.parse(
            "{\"arguments\":{\"namespace\":\"inner\"},\"namespace\":\"outer\"}");

        assertThat(Json.stringAt(parsed, "namespace")).isEqualTo("outer");
        assertThat(Json.stringAt(Json.objectAt(parsed, "arguments"), "namespace"))
            .isEqualTo("inner");
    }

    @Test
    @DisplayName("an escaped quote inside a value does not end it")
    void escapedQuotesDoNotTerminateValues() {
        Object parsed = Json.parse(
            "{\"query\":\"what does \\\"namespace\\\": mean here\",\"n\":1}");

        assertThat(Json.stringAt(parsed, "query"))
            .isEqualTo("what does \"namespace\": mean here");
        assertThat(Json.at(parsed, "n")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a brace inside a string does not end the object")
    void bracesInsideStringsAreNotStructure() {
        Object parsed = Json.parse("{\"a\":\"} {\",\"b\":1}");

        assertThat(Json.stringAt(parsed, "a")).isEqualTo("} {");
        assertThat(Json.at(parsed, "b")).isEqualTo(1L);
    }

    @Test
    @DisplayName("an integral number survives the round trip as an integer")
    void integersDoNotBecomeFloats() {
        // A JSON-RPC id echoed as 1.0 is an id the client cannot match to its request.
        Object parsed = Json.parse("{\"id\":7}");

        assertThat(Json.at(parsed, "id")).isEqualTo(7L);
        assertThat(Json.write(parsed)).isEqualTo("{\"id\":7}");
    }

    @Test
    @DisplayName("a fractional number stays fractional")
    void fractionsAreDoubles() {
        assertThat(Json.at(Json.parse("{\"confidence\":0.87}"), "confidence"))
            .isEqualTo(0.87d);
    }

    @Test
    @DisplayName("a string id is echoed as a string")
    void stringIdsSurvive() {
        Object parsed = Json.parse("{\"id\":\"abc-1\"}");
        assertThat(Json.write(parsed)).isEqualTo("{\"id\":\"abc-1\"}");
    }

    @Test
    @DisplayName("escapes decode and re-encode")
    void escapesRoundTrip() {
        String decoded = (String) Json.at(
            Json.parse("{\"s\":\"a\\nb\\t\\\"c\\\"\\\\d\\u00e9\"}"), "s");

        assertThat(decoded).isEqualTo("a\nb\t\"c\"\\d\u00e9");
        assertThat(Json.parse(Json.write(Map.of("s", decoded))))
            .isEqualTo(Map.of("s", decoded));
    }

    @Test
    @DisplayName("a control character is escaped rather than emitted raw")
    void controlCharactersAreEscaped() {
        // A raw newline inside a value would split one message into two on a line-delimited
        // transport, and the client would see the second half as garbage.
        String written = Json.write(Map.of("s", "ab\nc"));

        assertThat(written).doesNotContain("\n");
        assertThat(Json.at(Json.parse(written), "s")).isEqualTo("ab\nc");
    }

    @Test
    @DisplayName("objects keep the order their fields were put in")
    void fieldOrderIsPreserved() {
        Map<String, Object> object = Json.object();
        object.put("z", 1);
        object.put("a", 2);

        assertThat(Json.write(object)).isEqualTo("{\"z\":1,\"a\":2}");
    }

    @Test
    @DisplayName("nested structure round trips")
    void nestedStructureRoundTrips() {
        String text = "{\"a\":[1,{\"b\":[true,false,null]}],\"c\":{}}";
        assertThat(Json.write(Json.parse(text))).isEqualTo(text);
    }

    @Test
    @DisplayName("empty containers parse")
    void emptyContainersParse() {
        assertThat(Json.parse("{}")).isEqualTo(Map.of());
        assertThat(Json.parse("[]")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("whitespace between tokens is ignored")
    void whitespaceIsIgnored() {
        assertThat(Json.at(Json.parse("  {\n  \"a\" : \t 1 \r\n }  "), "a")).isEqualTo(1L);
    }

    @Test
    @DisplayName("text that is not JSON is refused rather than half-read")
    void malformedInputIsRefused() {
        assertThatThrownBy(() -> Json.parse("{\"a\":}"))
            .isInstanceOf(Json.JsonException.class);
        assertThatThrownBy(() -> Json.parse("{\"a\":1"))
            .isInstanceOf(Json.JsonException.class);
        assertThatThrownBy(() -> Json.parse("{\"a\":\"unterminated"))
            .isInstanceOf(Json.JsonException.class);
        assertThatThrownBy(() -> Json.parse("nope"))
            .isInstanceOf(Json.JsonException.class);
        assertThatThrownBy(() -> Json.parse(null))
            .isInstanceOf(Json.JsonException.class);
    }

    @Test
    @DisplayName("a second value after the first is refused")
    void trailingTextIsRefused() {
        // Accepting it would let one line smuggle a second message past the transport.
        assertThatThrownBy(() -> Json.parse("{\"a\":1} {\"b\":2}"))
            .isInstanceOf(Json.JsonException.class)
            .hasMessageContaining("trailing");
    }

    @Test
    @DisplayName("input nested past the limit is refused rather than exhausting the stack")
    void depthIsBounded() {
        String deep = "[".repeat(Json.MAX_DEPTH + 5) + "]".repeat(Json.MAX_DEPTH + 5);

        assertThatThrownBy(() -> Json.parse(deep))
            .isInstanceOf(Json.JsonException.class)
            .hasMessageContaining("nests deeper");
    }

    @Test
    @DisplayName("a value with no JSON form is refused rather than written as its toString")
    void unwritableValuesAreRefused() {
        assertThatThrownBy(() -> Json.write(Map.of("x", new Object())))
            .isInstanceOf(Json.JsonException.class);
        assertThatThrownBy(() -> Json.write(Map.of("x", Double.NaN)))
            .isInstanceOf(Json.JsonException.class);
    }

    @Test
    @DisplayName("accessors decline the wrong shape instead of guessing")
    void accessorsAreTypeSafe() {
        Object parsed = Json.parse("{\"a\":1,\"b\":{\"c\":2}}");

        assertThat(Json.stringAt(parsed, "a")).isNull();
        assertThat(Json.objectAt(parsed, "a")).isNull();
        assertThat(Json.objectAt(parsed, "b")).containsEntry("c", 2L);
        assertThat(Json.at("not an object", "a")).isNull();
    }
}
