package com.eonmux.excalibur.api.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code code} member every error response carries.
 */
@DisplayName("Error codes")
class ErrorCodeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SNAKE_CASE = Pattern.compile("[a-z]+(_[a-z]+)*");

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "400, invalid_request",
        "401, unauthorized",
        "403, forbidden",
        "404, not_found",
        "405, method_not_allowed",
        "408, timeout",
        "409, conflict",
        "413, payload_too_large",
        "422, unprocessable",
        "429, rate_limited",
        "500, internal_error",
        "503, unavailable",
        "504, timeout",
        "418, client_error",
        "502, server_error"
    })
    @DisplayName("every status has a default code")
    void defaultsFollowTheStatus(int status, String code) {
        assertThat(ErrorCode.forStatus(status).wireName()).isEqualTo(code);
    }

    @Test
    @DisplayName("codes are distinct snake_case names")
    void codesAreDistinctSnakeCase() {
        Set<String> seen = new HashSet<>();
        Arrays.stream(ErrorCode.values()).forEach(code -> {
            assertThat(code.wireName()).matches(SNAKE_CASE);
            assertThat(seen.add(code.wireName())).as(code.wireName()).isTrue();
        });
    }

    @Test
    @DisplayName("an envelope without a code is given its status's default after 'error'")
    void missingCodeIsStamped() throws Exception {
        String stamped = ResponseBody.withErrorCode(404, "{\"error\": \"Not Found\"}");

        JsonNode body = JSON.readTree(stamped);
        assertThat(body.get("error").asText()).isEqualTo("Not Found");
        assertThat(body.get("code").asText()).isEqualTo("not_found");
        assertThat(stamped).isEqualTo("{\"error\": \"Not Found\", \"code\": \"not_found\"}");
    }

    @Test
    @DisplayName("members after 'error' are kept, and the code sits between")
    void extraMembersAreKept() throws Exception {
        String stamped = ResponseBody.withErrorCode(409,
            "{\"error\":\"two \\\"Ada\\\", pick one\",\"candidates\":[{\"entity_id\":\"e1\"}]}");

        JsonNode body = JSON.readTree(stamped);
        assertThat(body.get("code").asText()).isEqualTo("conflict");
        assertThat(body.get("error").asText()).isEqualTo("two \"Ada\", pick one");
        assertThat(body.get("candidates").get(0).get("entity_id").asText()).isEqualTo("e1");
    }

    @Test
    @DisplayName("an 'error' member that is not first is still found")
    void errorNotFirst() throws Exception {
        String stamped = ResponseBody.withErrorCode(400, "{\"field\": \"x\", \"error\": \"bad\"}");

        assertThat(JSON.readTree(stamped).get("code").asText()).isEqualTo("invalid_request");
    }

    @Test
    @DisplayName("a code the route chose is not overwritten")
    void explicitCodeIsKept() {
        String body = ResponseBody.errorJson(ErrorCode.GRAPH_NOT_SERVED, "no graph");

        assertThat(ResponseBody.withErrorCode(503, body)).isEqualTo(body);
        assertThat(body).isEqualTo("{\"error\": \"no graph\", \"code\": \"graph_not_served\"}");
    }

    @Test
    @DisplayName("successes, status reports and non-envelopes are left alone")
    void nonErrorsAreUntouched() {
        assertThat(ResponseBody.withErrorCode(200, "{\"error\": \"last build failed\"}"))
            .isEqualTo("{\"error\": \"last build failed\"}");
        assertThat(ResponseBody.withErrorCode(503, "{\"ready\": false}"))
            .isEqualTo("{\"ready\": false}");
        assertThat(ResponseBody.withErrorCode(500, "{\"error\": null}"))
            .isEqualTo("{\"error\": null}");
        assertThat(ResponseBody.withErrorCode(500, "{\"error\": {\"nested\": 1}}"))
            .isEqualTo("{\"error\": {\"nested\": 1}}");
        assertThat(ResponseBody.withErrorCode(500, "{\"error\": \"unterminated"))
            .isEqualTo("{\"error\": \"unterminated");
    }

    @Test
    @DisplayName("bytes that are not a JSON object are returned as the same array")
    void nonObjectBytesAreUntouched() {
        byte[] text = "Internal Server Error".getBytes(StandardCharsets.UTF_8);
        byte[] blank = "   ".getBytes(StandardCharsets.UTF_8);
        byte[] success = "{\"error\": \"x\"}".getBytes(StandardCharsets.UTF_8);
        byte[] coded = "{\"error\": \"x\", \"code\": \"y\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(ResponseBody.withErrorCode(500, text)).isSameAs(text);
        assertThat(ResponseBody.withErrorCode(500, blank)).isSameAs(blank);
        assertThat(ResponseBody.withErrorCode(200, success)).isSameAs(success);
        assertThat(ResponseBody.withErrorCode(500, coded)).isSameAs(coded);
    }

    @Test
    @DisplayName("leading whitespace before the object is tolerated")
    void leadingWhitespace() {
        byte[] body = " \n{\"error\": \"x\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(new String(ResponseBody.withErrorCode(413, body), StandardCharsets.UTF_8))
            .contains("\"code\": \"payload_too_large\"");
    }
}
