package excalibur.api.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the shared JSON helpers.
 */
@DisplayName("JsonText")
class JsonTextTest {

    private final Locale originalLocale = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    @Nested
    @DisplayName("Number formatting")
    class NumberFormatting {

        @Test
        @DisplayName("uses a decimal point regardless of the default locale")
        void localeIndependent() {
            Locale.setDefault(Locale.GERMANY);

            // A comma decimal separator here produces {"coverage": 0,9500}, which no
            // JSON parser accepts.
            assertThat(JsonText.number(0.95, 4)).isEqualTo("0.9500");
            assertThat(JsonText.number(-1.5, 6)).isEqualTo("-1.500000");
        }
    }

    @Nested
    @DisplayName("Escaping")
    class Escaping {

        @Test
        @DisplayName("escapes quotes, backslashes and control characters")
        void escapes() {
            assertThat(JsonText.escape("a\"b\\c\nd\te")).isEqualTo("a\\\"b\\\\c\\nd\\te");
            assertThat(JsonText.escape(null)).isEmpty();
        }

        @Test
        @DisplayName("round-trips through unescape")
        void roundTrips() {
            String raw = "path\\to\\file \"quoted\"\nnext";
            assertThat(JsonText.unescape(JsonText.escape(raw))).isEqualTo(raw);
        }

        @Test
        @DisplayName("decodes unicode escapes")
        void decodesUnicode() {
            assertThat(JsonText.unescape("caf\\u00e9")).isEqualTo("café");
        }

        @Test
        @DisplayName("escapes every character below U+0020")
        void escapesEveryControlCharacter() {
            // RFC 8259 forbids them raw in a string literal, and they are client-reachable:
            // unescape() turns \\u0001 in a request into a real 0x01, which the endpoints echo
            // back in an error message or a document id.
            for (char c = 0; c < 0x20; c++) {
                String escaped = JsonText.escape("a" + c + "b");
                assertThat(escaped)
                    .as("U+%04X is escaped", (int) c)
                    .doesNotContain(String.valueOf(c));
                assertThat(JsonText.unescape(escaped))
                    .as("U+%04X survives a round trip", (int) c)
                    .isEqualTo("a" + c + "b");
            }
        }

        @Test
        @DisplayName("uses the short forms for backspace and form feed")
        void usesShortFormsForBackspaceAndFormFeed() {
            assertThat(JsonText.escape("a\bb")).isEqualTo("a\\bb");
            assertThat(JsonText.escape("a\fb")).isEqualTo("a\\fb");
        }

        @Test
        @DisplayName("escapes the remaining control characters as \\u00xx")
        void usesUnicodeEscapeForTheRest() {
            assertThat(JsonText.escape("a\u0001b")).isEqualTo("a\\u0001b");
            assertThat(JsonText.escape("a\u001fb")).isEqualTo("a\\u001fb");
            assertThat(JsonText.escape("a\u0000b")).isEqualTo("a\\u0000b");
        }
    }

    @Nested
    @DisplayName("Extraction")
    class Extraction {

        @Test
        @DisplayName("reads strings, objects and arrays")
        void readsStructures() {
            String json = "{\"a\": \"x\", \"config\": {\"b\": \"y{z}\"}, \"list\": [\"p\", \"q\"]}";

            assertThat(JsonText.string(json, "a")).isEqualTo("x");
            assertThat(JsonText.object(json, "config")).isEqualTo("{\"b\": \"y{z}\"}");
            assertThat(JsonText.stringArray(json, "list")).containsExactly("p", "q");
            assertThat(JsonText.string(json, "missing")).isNull();
        }

        @Test
        @DisplayName("returns the default for an absent field")
        void defaultsForAbsentFields() {
            String json = "{}";

            assertThat(JsonText.integer(json, "top_k", 10)).isEqualTo(10);
            assertThat(JsonText.longValue(json, "timeout_ms", 5L)).isEqualTo(5L);
            assertThat(JsonText.floatValue(json, "threshold", 0.5f)).isEqualTo(0.5f);
            assertThat(JsonText.doubleValue(json, "recall_target", 0.95)).isEqualTo(0.95);
            assertThat(JsonText.booleanValue(json, "flag", true)).isTrue();
        }

        @Test
        @DisplayName("rejects a field that is present with the wrong type")
        void rejectsTypeMismatch() {
            // Silently substituting the default hides a client mistake and answers a
            // question the caller never asked.
            assertThatThrownBy(() -> JsonText.integer("{\"top_k\": \"ten\"}", "top_k", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("top_k");
            assertThatThrownBy(() -> JsonText.booleanValue("{\"flag\": \"yes\"}", "flag", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flag");
            assertThatThrownBy(() -> JsonText.longValue("{\"timeout_ms\": 99999999999999999999}", "timeout_ms", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("parses numbers under a comma-decimal default locale")
        void parsesNumbersLocaleIndependently() {
            Locale.setDefault(Locale.GERMANY);

            assertThat(JsonText.doubleValue("{\"recall_target\": 0.75}", "recall_target", 0.0))
                .isEqualTo(0.75);
        }
    }
}
