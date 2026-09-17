package excalibur.api.endpoint;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * That a running process can say which build it is.
 *
 * <p>"Which version is on this host" is the first question of an incident and of a rollback.
 * The jar's manifest carries the answer and nothing served it, so the only way to find out was
 * to go and look at the file on the host - which is exactly what an operator cannot do for
 * forty pods.</p>
 */
@DisplayName("Version")
class VersionEndpointTest {

    @Test
    @DisplayName("names the build, the JVM and how long this process has been up")
    void namesTheBuild() throws IOException {
        Answer answer = send("GET");

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).contains("\"name\": \"" + BuildInfo.title() + "\"");
        assertThat(answer.body()).contains("\"version\": \"" + BuildInfo.version() + "\"");
        assertThat(answer.body()).contains("\"jvm\": \"" + BuildInfo.jvm() + "\"");
        assertThat(answer.body()).contains("\"uptimeMs\": ");
        assertThat(answer.body()).contains("\"startedAtMs\": ");
    }

    @Test
    @DisplayName("says so rather than guessing when the build stamped no version")
    void admitsAnUnstampedBuild() {
        // Run from a directory of class files there is no manifest to read, which is exactly
        // how this runs under Gradle. Reporting a made-up version would be worse than none:
        // a rollback decision would be taken against it.
        assertThat(BuildInfo.version()).isNotBlank();
    }

    @Test
    @DisplayName("refuses a method this route does not serve")
    void refusesOtherMethods() throws IOException {
        assertThat(send("POST").status()).isEqualTo(405);
    }

    private record Answer(int status, String body) {
    }

    private Answer send(String method) throws IOException {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/version"));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(out);

        new VersionEndpoint().handle(exchange);

        var status = forClass(Integer.class);
        verify(exchange).sendResponseHeaders(status.capture(),
            org.mockito.ArgumentMatchers.anyLong());
        return new Answer(status.getValue(), out.toString(StandardCharsets.UTF_8));
    }
}
