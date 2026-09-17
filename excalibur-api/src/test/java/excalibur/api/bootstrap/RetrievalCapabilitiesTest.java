package excalibur.api.bootstrap;

import io.excalibur.config.LlmConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which retrieval signals a node can offer, decided once at startup.
 *
 * <p>These three answers used to be one boolean - "is an API key set" - and that collapsed two
 * different questions. Query expansion needs a chat model and no vectors; corpus embedding needs
 * the same key and produces vectors. Deriving both from key-presence alone made expansion
 * reachable only on nodes that had already taken the vector path and would never consult it.</p>
 */
@DisplayName("Retrieval capabilities")
class RetrievalCapabilitiesTest {

    private static RetrievalCapabilities from(Map<String, String> props) {
        return RetrievalCapabilities.from(new LlmConfig(new HashMap<>(props)));
    }

    @Nested
    @DisplayName("With no API key")
    class Unkeyed {

        @Test
        @DisplayName("nothing that needs a model is offered")
        void noModelNoSignals() {
            RetrievalCapabilities caps = from(Map.of());

            assertThat(caps.chatModel()).isFalse();
            assertThat(caps.embedCorpus()).isFalse();
            assertThat(caps.expandQueries()).isFalse();
        }

        @Test
        @DisplayName("a key of only spaces is no key")
        void blankIsAbsent() {
            assertThat(from(Map.of("excalibur.llm.api-key", "   ")).chatModel()).isFalse();
        }

        @Test
        @DisplayName("declining embeddings changes nothing there is no model for")
        void decliningWithoutAKeyIsInert() {
            RetrievalCapabilities caps = from(Map.of("excalibur.llm.embeddings-enabled", "false"));

            assertThat(caps.expandQueries())
                .as("expansion is a chat call; without a key there is no one to call")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("With an API key")
    class Keyed {

        @Test
        @DisplayName("the corpus is embedded and the query is not expanded")
        void vectorNode() {
            RetrievalCapabilities caps = from(Map.of("excalibur.llm.api-key", "sk-test"));

            assertThat(caps.chatModel()).isTrue();
            assertThat(caps.embedCorpus()).isTrue();
            assertThat(caps.expandQueries())
                .as("the lexical path is not taken on a node that has vectors, so an expander "
                    + "built for it would be a client nobody calls")
                .isFalse();
        }

        @Test
        @DisplayName("declining embeddings moves the model from the corpus to the query")
        void lexicalNodeWithAModel() {
            RetrievalCapabilities caps = from(Map.of(
                "excalibur.llm.api-key", "sk-test",
                "excalibur.llm.embeddings-enabled", "false"));

            assertThat(caps.chatModel()).isTrue();
            assertThat(caps.embedCorpus()).isFalse();
            assertThat(caps.expandQueries())
                .as("this is the deployment lexical retrieval exists for")
                .isTrue();
        }
    }

    @Nested
    @DisplayName("With a key, embeddings on, and hybrid expansion asked for")
    class ExpandedHybrid {

        private static RetrievalCapabilities expandingHybrid() {
            return RetrievalCapabilities.from(
                new LlmConfig(new HashMap<>(Map.of("excalibur.llm.api-key", "sk-test"))), true);
        }

        @Test
        @DisplayName("all three signals are offered at once")
        void everySignalIsOffered() {
            // The combination the retrieval benchmark scores as BM25_EXPANSION_VECTOR and
            // FULL_HYBRID. It used to be unreachable by construction - expansion was derived as
            // "a chat model and no vectors" - so a deployment could not be put into the
            // configuration its own benchmark measured.
            RetrievalCapabilities caps = expandingHybrid();

            assertThat(caps.chatModel()).isTrue();
            assertThat(caps.embedCorpus()).isTrue();
            assertThat(caps.expandQueries()).isTrue();
        }

        @Test
        @DisplayName("the default leaves a hybrid node's lexical half unexpanded")
        void defaultIsUnchanged() {
            RetrievalCapabilities caps = from(Map.of("excalibur.llm.api-key", "sk-test"));

            assertThat(caps.embedCorpus()).isTrue();
            assertThat(caps.expandQueries())
                .as("turning the setting on is opt-in: a node that has always run plain BM25 "
                    + "beside its vectors must not start paying for a chat call per query "
                    + "because it was upgraded")
                .isFalse();
        }

        @Test
        @DisplayName("asking for it without a key still offers nothing")
        void noKeyStillOffersNothing() {
            RetrievalCapabilities caps =
                RetrievalCapabilities.from(new LlmConfig(new HashMap<>()), true);

            assertThat(caps.chatModel()).isFalse();
            assertThat(caps.expandQueries()).isFalse();
        }

        @Test
        @DisplayName("a lexical-only node expands either way")
        void lexicalOnlyIsUnaffected() {
            Map<String, String> lexical = Map.of(
                "excalibur.llm.api-key", "sk-test",
                "excalibur.llm.embeddings-enabled", "false");

            assertThat(from(lexical).expandQueries()).isTrue();
            assertThat(RetrievalCapabilities.from(new LlmConfig(new HashMap<>(lexical)), true)
                .expandQueries()).isTrue();
        }
    }
}
