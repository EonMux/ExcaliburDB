package excalibur.api.bootstrap;

import io.excalibur.config.Config;
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
        return RetrievalCapabilities.from(new Config.Llm(new HashMap<>(props)));
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
}
