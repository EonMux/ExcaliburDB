package com.eonmux.excalibur.ocr.pipeline;

import com.eonmux.excalibur.ocr.json.Json;
import com.eonmux.excalibur.ocr.testing.Fixtures;
import com.eonmux.excalibur.ocr.testing.Models;
import com.eonmux.excalibur.ocr.text.Vocab;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The line rules against Indra's own results on synthetic lines
 * ({@code tools/fixtures/gen_lines.py}).
 */
@DisplayName("Line rules, as Indra OCR applies them")
class LineRulesTest {

    private static Map<String, Object> fixtures;
    private static Vocab vocab;

    @BeforeAll
    static void load() throws IOException {
        fixtures = Json.parseObject(Fixtures.text("pipeline/lines.json"));
        Map<String, Object> meta = Json.parseObject(
            Files.readString(Models.dir().resolve("meta.json")));
        vocab = Vocab.fromJson((String) meta.get("vocab"));
    }

    @Test
    @DisplayName("drops leader lines and joins lone list markers to their items")
    void tidy() {
        for (Map<String, Object> c : cases("tidy")) {
            Long space = (Long) c.get("space");
            TidyLines.Lines out = TidyLines.tidy(boxes(c.get("boxes")), readings(c.get("rec")),
                (Boolean) c.get("rtl"), space == null ? null : space.intValue());

            assertThat(out.boxes()).as("boxes of %s", c.get("rec"))
                .isDeepEqualTo(boxes(c.get("out_boxes")));
            assertThat(out.readings()).as("lines of %s", c.get("rec"))
                .isEqualTo(readings(c.get("out_rec")));
        }
    }

    @Test
    @DisplayName("cuts lines at wide gaps and across gutters, into boxes Indra would draw")
    void split() {
        for (Map<String, Object> c : cases("split")) {
            LineText text = new LineText(vocab, (Boolean) c.get("page_rtl"));
            float[] box = floats(c.get("box"));
            double[] at = ((List<?>) c.get("at")).stream()
                .mapToDouble(v -> ((Number) v).doubleValue()).toArray();

            List<LineText.Piece> out = text.split(box, tokens(c.get("tokens")),
                ((Number) c.get("gap")).doubleValue(), at);

            List<?> expected = (List<?>) c.get("out");
            assertThat(out).as("pieces of %s", c.get("tokens")).hasSize(expected.size());
            for (int i = 0; i < out.size(); i++) {
                List<?> piece = (List<?>) expected.get(i);
                assertThat(out.get(i).box()).isEqualTo(floats(piece.get(0)));
                assertThat(out.get(i).text()).isEqualTo(piece.get(1));
                assertThat(out.get(i).tokens()).isEqualTo(tokens(piece.get(2)));
            }
        }
    }

    @Test
    @DisplayName("finds the gaps other lines' edges line up with")
    void gutters() {
        LineText text = new LineText(vocab, false);
        for (Map<String, Object> c : cases("gutter")) {
            double[][] cuts = text.gutterCuts(boxes(c.get("boxes")), readings(c.get("rec")));

            List<?> expected = (List<?>) c.get("cuts");
            assertThat(cuts.length).isEqualTo(expected.size());
            for (int i = 0; i < cuts.length; i++) {
                assertThat(cuts[i]).containsExactly(((List<?>) expected.get(i)).stream()
                    .mapToDouble(v -> ((Number) v).doubleValue()).toArray());
            }
        }
    }

    @Test
    @DisplayName("reads tokens as text: ids by the vocabulary, characters composed and in order")
    void decode() {
        for (Map<String, Object> c : cases("decode")) {
            LineText text = new LineText(vocab, (Boolean) c.get("page_rtl"));

            assertThat(text.decode(tokens(c.get("tokens")))).as("%s", c)
                .isEqualTo(c.get("text"));
        }
    }

    @Test
    @DisplayName("tokens are one kind at a time")
    void tokenKinds() {
        assertThatThrownBy(() -> new Token("a", 3, 0, Token.NO_INDEX))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Token(null, 3, 0, 4))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Token.ofLayer("a", 0, Token.NO_INDEX))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(Token.ofLayer("a", 1, 7).at(3)).isEqualTo(Token.ofLayer("a", 3, 7));
        assertThat(Token.ofId(4, 1).fromLayer()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cases(String name) {
        List<Map<String, Object>> cases = (List<Map<String, Object>>) fixtures.get(name);
        assertThat(cases).isNotEmpty();
        return cases;
    }

    static float[] floats(Object row) {
        List<?> values = (List<?>) row;
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) ((Number) values.get(i)).doubleValue();
        }
        return out;
    }

    static float[][] boxes(Object rows) {
        return ((List<?>) rows).stream().map(LineRulesTest::floats).toArray(float[][]::new);
    }

    static List<Token> tokens(Object list) {
        List<Token> out = new ArrayList<>();
        for (Object o : (List<?>) list) {
            List<?> t = (List<?>) o;
            double x = ((Number) t.get(2)).doubleValue();
            out.add(switch ((String) t.get(0)) {
                case "id" -> Token.ofId(((Number) t.get(1)).intValue(), x);
                case "text" -> Token.ofText((String) t.get(1), x);
                default -> Token.ofLayer((String) t.get(1), x, ((Number) t.get(3)).intValue());
            });
        }
        return out;
    }

    static List<Reading> readings(Object list) {
        List<Reading> out = new ArrayList<>();
        for (Object o : (List<?>) list) {
            List<?> r = (List<?>) o;
            out.add(new Reading((String) r.get(0), ((Number) r.get(1)).doubleValue(),
                tokens(r.get(2))));
        }
        return out;
    }
}
