package io.excalibur.llm.client;

import excalibur.llm.client.LLMClientConfig;
import io.excalibur.llm.LLMException;
import io.excalibur.llm.LLMException.ErrorType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down what the client reads as the model's answer.
 *
 * <p>The assistant message is located by hand rather than with a JSON parser, which is fine
 * while {@code content} holds a string and dangerous the moment it does not. A provider
 * returning a tool-call-only message sends {@code "content": null}, and a scan that looks for
 * the next quoted run after the key walks straight past the null into whatever field follows -
 * handing a neighbouring key's name back to the rest of the system as though the model had said
 * it. These tests fix the rule that absent content is absent, and that anything unreadable is
 * an error rather than a guess.</p>
 */
@Timeout(value = 30)
class ChatContentExtractionTest {

    private MockLLMServer server;
    private LLMHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = MockLLMServer.create().start();
        LLMClientConfig config = LLMClientConfig.builder()
            .baseUrl(server.getBaseUrl())
            .timeout(Duration.ofSeconds(5))
            .maxRetries(0)
            .build();
        client = new LLMHttpClient(config);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private void replyWith(String body) {
        server.setChatHandler(server.responseBuilder().statusCode(200).body(body).build());
    }

    @Test
    @DisplayName("a null content does not read as the next field in the message")
    void nullContentIsNotTheNextField() {
        replyWith("""
            {"choices":[{"index":0,"message":{"role":"assistant","content":null,
            "tool_calls":[{"id":"call_1","type":"function",
            "function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}
            """);

        LLMException thrown = assertThrows(LLMException.class,
            () -> client.complete("do a thing", "some input", null));

        assertEquals(ErrorType.PARSE_ERROR, thrown.getErrorType(),
            "A message with no textual content is a reply that cannot be read, not a reply "
                + "whose text happens to be the name of the next key");
    }

    @Test
    @DisplayName("a null content is not mistaken for a score array either")
    void nullContentIsNotAScoreArray() {
        replyWith("""
            {"choices":[{"index":0,"message":{"role":"assistant","content":null,
            "refusal":"[{\\"index\\": 0, \\"score\\": 1.0}]"}}]}
            """);

        LLMException thrown = assertThrows(LLMException.class,
            () -> client.scoreRelevancy("q", java.util.List.of("a"), null));

        assertEquals(ErrorType.PARSE_ERROR, thrown.getErrorType(),
            "Scores must never be taken from a field that is not the assistant's content");
    }

    @Test
    @DisplayName("a content field that is neither a string nor null is an error")
    void nonStringContentIsAnError() {
        replyWith("""
            {"choices":[{"index":0,"message":{"role":"assistant",
            "content":[{"type":"text","text":"hello"}]}}]}
            """);

        LLMException thrown = assertThrows(LLMException.class,
            () -> client.complete("do a thing", "some input", null));

        assertEquals(ErrorType.PARSE_ERROR, thrown.getErrorType(),
            "A structured content block is not something this parser can read; saying so beats "
                + "returning part of it");
    }

    @Test
    @DisplayName("an ordinary string content is still read")
    void stringContentIsRead() {
        replyWith("""
            {"choices":[{"index":0,"message":{"role":"assistant","content":"hello there"}}]}
            """);

        assertEquals("hello there", client.complete("do a thing", "some input", null));
    }

    @Test
    @DisplayName("an empty string content is read as empty, not as the next field")
    void emptyStringContentIsRead() {
        replyWith("""
            {"choices":[{"index":0,"message":{"role":"assistant","content":"","role2":"decoy"}}]}
            """);

        assertEquals("", client.complete("do a thing", "some input", null));
    }

    @Test
    @DisplayName("escape sequences in content survive extraction")
    void escapedContentIsUnescaped() {
        replyWith("""
            {"choices":[{"index":0,"message":{"role":"assistant",
            "content":"line1\\nline2 \\"quoted\\" \\u00e9"}}]}
            """);

        assertEquals("line1\nline2 \"quoted\" é",
            client.complete("do a thing", "some input", null));
    }

    @Test
    @DisplayName("a response carrying no content key at all is an error")
    void missingContentIsAnError() {
        replyWith("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\"}}]}");

        LLMException thrown = assertThrows(LLMException.class,
            () -> client.complete("do a thing", "some input", null));

        assertEquals(ErrorType.PARSE_ERROR, thrown.getErrorType());
    }

    @Test
    @DisplayName("a parse failure does not quote the model's reply back to the caller")
    void parseFailureDoesNotEchoTheReply() {
        // Bracketed, so the sanitizer hands it on as an array and the score reader is the stage
        // that gives up - the stage that used to append what it was given to its message.
        String secret = "PATIENT-RECORD-ABC-123";
        replyWith("{\"choices\":[{\"message\":{\"content\":\"[" + secret + "]\"}}]}");

        LLMException thrown = assertThrows(LLMException.class,
            () -> client.scoreRelevancy("q", java.util.List.of("a"), null));

        assertTrue(thrown.getMessage() != null && !thrown.getMessage().contains(secret),
            "The model's reply is derived from ingested documents; an exception that carries it "
                + "hands document content to whoever reads the error. Message was: "
                + thrown.getMessage());
    }
}
