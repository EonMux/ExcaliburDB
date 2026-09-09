package excalibur.api.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * The HTTP calls the MCP tools make against a running node.
 *
 * <h2>Why over HTTP rather than in process</h2>
 * <p>An MCP server is launched by whatever is doing the asking - a desktop client, an editor, an
 * agent - and lives and dies with that conversation. The database it queries does not: it holds
 * memory mappings, an epoch clock and an index that took time to build. They are separate
 * processes because they have separate lifetimes, and the API between them already exists.</p>
 *
 * <h2>Configuration</h2>
 * <table border="1">
 *   <caption>Environment</caption>
 *   <tr><th>Variable</th><th>Meaning</th><th>Default</th></tr>
 *   <tr><td>{@code EXCALIBUR_URL}</td><td>Base URL of the node</td>
 *       <td>{@code http://127.0.0.1:8080}</td></tr>
 *   <tr><td>{@code EXCALIBUR_API_AUTH_TOKEN}</td><td>Bearer token, when the node requires one</td>
 *       <td>none</td></tr>
 *   <tr><td>{@code EXCALIBUR_NAMESPACE}</td><td>Default namespace for graph calls</td>
 *       <td>the shared one</td></tr>
 *   <tr><td>{@code EXCALIBUR_MCP_TIMEOUT_SECONDS}</td><td>Per-request timeout</td><td>60</td></tr>
 * </table>
 *
 * <p>The token is read from the environment and never from an argument, because a process's
 * arguments are readable by any other process on the machine and its environment is not.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable, over a thread-safe {@link HttpClient}.</p>
 *
 * @author ExcaliburDB
 */
public final class ExcaliburClient {

    /** Where the node is, when the environment does not say. */
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8080";

    /** How long a call may take, when the environment does not say. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /** Header carrying the namespace, matching the server's. */
    public static final String NAMESPACE_HEADER = "X-Excalibur-Namespace";

    private final HttpClient http;
    private final URI baseUri;
    private final String authToken;
    private final String defaultNamespace;
    private final Duration timeout;

    private ExcaliburClient(URI baseUri, String authToken, String defaultNamespace,
                            Duration timeout) {
        this.baseUri = baseUri;
        this.authToken = authToken;
        this.defaultNamespace = defaultNamespace;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /**
     * Builds a client from the environment.
     *
     * @param environment The environment, normally {@code System.getenv()}
     * @return The client
     * @throws IllegalArgumentException if a configured value is unusable
     */
    public static ExcaliburClient fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment must not be null");

        String base = trimToNull(environment.get("EXCALIBUR_URL"));
        URI uri;
        try {
            uri = URI.create(base != null ? stripTrailingSlash(base) : DEFAULT_BASE_URL);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("EXCALIBUR_URL is not a URL: " + base);
        }
        // Checked here rather than left to the first call: HttpClient rejects any other scheme
        // at send time, which turns a configuration mistake into a failure of whichever tool the
        // model happened to try first.
        String scheme = uri.getScheme();
        if (uri.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(
                "EXCALIBUR_URL must be an absolute http(s) URL: " + uri);
        }

        int seconds = DEFAULT_TIMEOUT_SECONDS;
        String configured = trimToNull(environment.get("EXCALIBUR_MCP_TIMEOUT_SECONDS"));
        if (configured != null) {
            try {
                seconds = Integer.parseInt(configured);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "EXCALIBUR_MCP_TIMEOUT_SECONDS is not a number: " + configured);
            }
            if (seconds <= 0) {
                throw new IllegalArgumentException(
                    "EXCALIBUR_MCP_TIMEOUT_SECONDS must be positive: " + seconds);
            }
        }

        return new ExcaliburClient(uri,
            trimToNull(environment.get("EXCALIBUR_API_AUTH_TOKEN")),
            trimToNull(environment.get("EXCALIBUR_NAMESPACE")),
            Duration.ofSeconds(seconds));
    }

    /**
     * The node this client talks to, for diagnostics.
     *
     * @return The base URI
     */
    public URI baseUri() {
        return baseUri;
    }

    /**
     * POSTs a JSON body.
     *
     * @param path      Path on the node, beginning with {@code /}
     * @param body      The JSON request body
     * @param namespace Namespace to scope the call to, or null for the configured default
     * @return The node's answer
     * @throws IOException          if the node cannot be reached
     * @throws InterruptedException if the calling thread is interrupted
     */
    public Response post(String path, String body, String namespace)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(baseUri.resolve(path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return send(request, namespace);
    }

    /**
     * GETs a path, with one optional query parameter.
     *
     * @param path       Path on the node, beginning with {@code /}
     * @param queryName  Parameter name, or null for none
     * @param queryValue Parameter value, or null for none
     * @return The node's answer
     * @throws IOException          if the node cannot be reached
     * @throws InterruptedException if the calling thread is interrupted
     */
    public Response get(String path, String queryName, String queryValue)
            throws IOException, InterruptedException {
        String target = path;
        if (queryName != null && queryValue != null) {
            target = path + "?" + queryName + "="
                + URLEncoder.encode(queryValue, StandardCharsets.UTF_8);
        }
        return send(HttpRequest.newBuilder()
            .uri(baseUri.resolve(target))
            .timeout(timeout)
            .GET(), null);
    }

    private Response send(HttpRequest.Builder request, String namespace)
            throws IOException, InterruptedException {
        if (authToken != null) {
            request.header("Authorization", "Bearer " + authToken);
        }
        String scope = namespace != null ? namespace : defaultNamespace;
        if (scope != null) {
            request.header(NAMESPACE_HEADER, scope);
        }
        HttpResponse<String> response =
            http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(response.statusCode(), response.body());
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * What the node answered.
     *
     * @param status HTTP status code
     * @param body   Response body, normally JSON
     */
    public record Response(int status, String body) {

        /**
         * Whether the node accepted the request.
         *
         * @return true for 2xx
         */
        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }
    }
}
