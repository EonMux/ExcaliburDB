package com.eonmux.excalibur.bench.retrieval;

import com.eonmux.excalibur.config.LlmConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The runner's command line.
 *
 * <p>The arguments are positional and a Gradle property carries them, so the properties guarded
 * here are the ones a positional line gets wrong silently: an argument added later must not move
 * an earlier one, a placeholder must not become a value, and a model named with nothing to reach
 * it through must be refused rather than quietly dropped.
 */
@DisplayName("Evaluation arguments")
class EvaluationArgumentsTest {

    private static EvaluationArguments parse(String... args) {
        return EvaluationArguments.parse(args, Map.of());
    }

    @Test
    @DisplayName("the three required arguments alone measure BM25 on the shipped engine")
    void defaults() {
        EvaluationArguments parsed = parse("corpus", "index", "qrels.tsv");

        assertThat(parsed.corpus()).isEqualTo(Path.of("corpus"));
        assertThat(parsed.indexRoot()).isEqualTo(Path.of("index"));
        assertThat(parsed.qrels()).isEqualTo(Path.of("qrels.tsv"));
        assertThat(parsed.k()).isEqualTo(10);
        assertThat(parsed.engines()).containsExactly(LexicalEngine.NATIVE);
        assertThat(parsed.hasChatModel()).isFalse();
        assertThat(parsed.hasEmbeddingModel()).isFalse();
        assertThat(parsed.embeddingDimensions())
            .isEqualTo(LlmConfig.DEFAULT_EMBEDDINGS_DIMENSIONS);
    }

    @Test
    @DisplayName("the arguments that existed before the embedding model keep their positions")
    void existingPositionsAreUnchanged() {
        EvaluationArguments parsed =
            parse("c", "i", "q", "5", "http://127.0.0.1:1234", "chat", "both");

        assertThat(parsed.k()).isEqualTo(5);
        assertThat(parsed.baseUrl()).isEqualTo("http://127.0.0.1:1234");
        assertThat(parsed.chatModel()).isEqualTo("chat");
        assertThat(parsed.engines()).containsExactly(LexicalEngine.NATIVE, LexicalEngine.LUCENE);
        assertThat(parsed.hasChatModel()).isTrue();
        assertThat(parsed.hasEmbeddingModel()).isFalse();
    }

    @Test
    @DisplayName("an embedding model and its width follow the engine")
    void embeddingModel() {
        EvaluationArguments parsed =
            parse("c", "i", "q", "10", "http://127.0.0.1:1234", "-", "lucene", "embed", "768");

        assertThat(parsed.hasChatModel()).isFalse();
        assertThat(parsed.hasEmbeddingModel()).isTrue();
        assertThat(parsed.embeddingModel()).isEqualTo("embed");
        assertThat(parsed.embeddingDimensions()).isEqualTo(768);
        assertThat(parsed.engines()).containsExactly(LexicalEngine.LUCENE);
    }

    @Test
    @DisplayName("placeholders are absent values, in every spelling a shell can pass")
    void placeholders() {
        for (String placeholder : List.of("-", "''", "\"\"", " ")) {
            EvaluationArguments parsed = parse("c", "i", "q", placeholder, placeholder,
                placeholder, placeholder, placeholder, placeholder);

            assertThat(parsed.k()).as(placeholder).isEqualTo(10);
            assertThat(parsed.baseUrl()).as(placeholder).isNull();
            assertThat(parsed.chatModel()).as(placeholder).isNull();
            assertThat(parsed.engines()).as(placeholder).containsExactly(LexicalEngine.NATIVE);
            assertThat(parsed.embeddingModel()).as(placeholder).isNull();
        }
    }

    @Test
    @DisplayName("the endpoint's key comes from the variable a node reads it from")
    void apiKeyFromTheEnvironment() {
        String[] args = {"c", "i", "q"};

        assertThat(EvaluationArguments.parse(args,
            Map.of(EvaluationArguments.API_KEY_VARIABLE, "sk-test")).apiKey())
            .isEqualTo("sk-test");
        assertThat(EvaluationArguments.parse(args, Map.of()).apiKey())
            .as("a local endpoint needs no key, and a node calls it without one")
            .isEmpty();
        assertThat(EvaluationArguments.parse(args,
            Map.of(EvaluationArguments.API_KEY_VARIABLE, "  ")).apiKey())
            .as("a blank variable is a mis-set one, not a key")
            .isEmpty();
    }

    @Test
    @DisplayName("a model with no base URL to reach it through is refused, not dropped")
    void modelsNeedAnEndpoint() {
        assertThatThrownBy(() -> parse("c", "i", "q", "10", "-", "chat"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("base URL");
        assertThatThrownBy(() -> parse("c", "i", "q", "10", "-", "-", "native", "embed"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("base URL");
    }

    @Test
    @DisplayName("numbers that are not positive whole numbers are refused")
    void numbersAreChecked() {
        assertThatThrownBy(() -> parse("c", "i", "q", "0"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("k");
        assertThatThrownBy(() -> parse("c", "i", "q", "ten"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("k");
        assertThatThrownBy(() -> parse("c", "i", "q", "10", "http://h", "-", "native", "e", "-1"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dimensions");
        assertThatThrownBy(() -> parse("c", "i", "q", "10", "http://h", "-", "native", "e", "x"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dimensions");
    }

    @Test
    @DisplayName("more arguments than it takes is a mistake, not something to ignore")
    void tooManyArguments() {
        assertThatThrownBy(() -> parse("c", "i", "q", "10", "-", "-", "native", "-", "-", "x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("usage");
    }

    @Test
    @DisplayName("too few arguments is answered with the usage")
    void tooFewArguments() {
        assertThatThrownBy(() -> parse("c", "i"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("usage");
    }

    @Test
    @DisplayName("an engine it does not have is refused")
    void unknownEngine() {
        assertThatThrownBy(() -> parse("c", "i", "q", "10", "-", "-", "elasticsearch"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("native, lucene or both");
    }
}
