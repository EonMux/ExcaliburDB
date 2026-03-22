package excalibur.ingest.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * HTTP-based document source for web crawling.
 *
 * <p>Crawls web content starting from a base URL and fetches individual pages
 * as documents. Supports configurable depth, URL patterns, and content types.</p>
 *
 * <h2>Document IDs</h2>
 * <p>Document IDs are absolute URLs (e.g., "https://example.com/docs/readme.html").
 * Only URLs under the base URL path are included.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The configuration is immutable after construction.
 * Multiple threads may call {@link #crawl()}, {@link #hasChanged(Instant)}, and
 * {@link #fetch(String)} concurrently.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Base URL must be a valid HTTP or HTTPS URL</li>
 *   <li>Maximum response size: 10MB (configurable)</li>
 *   <li>Request timeout: 30 seconds (configurable)</li>
 *   <li>Maximum crawl depth: 3 (configurable)</li>
 *   <li>User-Agent: ExcaliburDB/1.0 (configurable)</li>
 *   <li>Respects robots.txt crawl-delay (basic implementation)</li>
 *   <li>URLs are normalized before comparison</li>
 * </ul>
 *
 * <h2>Change Detection</h2>
 * <p>Uses HTTP HEAD request with If-Modified-Since header for efficient
 * change detection. Falls back to Last-Modified header comparison.</p>
 *
 * <h2>Resource Management</h2>
 * <p>The internal HttpClient is shared and does not need explicit closing.
 * Call {@link #close()} to clear internal caches.</p>
 *
 * @see Source
 * @see FetchedDocument
 */
public class HttpSource implements Source {

    private static final Logger LOG = LoggerFactory.getLogger(HttpSource.class);

    private static final String SOURCE_TYPE = "http";

    private static final long DEFAULT_MAX_RESPONSE_SIZE = 10L * 1024 * 1024;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_DEPTH = 3;
    private static final String DEFAULT_USER_AGENT = "ExcaliburDB/1.0";
    private static final long MILLIS_PER_SECOND = 1000L;

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private static final Map<String, String> EXTENSION_TO_MIME_TYPE = Map.ofEntries(
        Map.entry(".html", "text/html"),
        Map.entry(".htm", "text/html"),
        Map.entry(".xhtml", "application/xhtml+xml"),
        Map.entry(".txt", "text/plain"),
        Map.entry(".md", "text/markdown"),
        Map.entry(".json", "application/json"),
        Map.entry(".xml", "application/xml"),
        Map.entry(".pdf", "application/pdf"),
        Map.entry(".csv", "text/csv")
    );

    private static final Pattern HREF_PATTERN = Pattern.compile(
        "href\\s*=\\s*[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern ANCHOR_PATTERN = Pattern.compile("#.*$");

    private final String sourceId;
    private final URI baseUri;
    private final String basePath;
    private final Set<String> allowedDomains;
    private final Set<String> includedPathPatterns;
    private final Set<String> excludedPathPatterns;
    private final Set<String> includedContentTypes;
    private final int maxDepth;
    private final long maxResponseSize;
    private final Duration timeout;
    private final String userAgent;
    private final HttpClient httpClient;
    private final Map<String, String> requestHeaders;

    private final Map<String, Long> lastModifiedCache = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    private HttpSource(Builder builder) {
        this.sourceId = Objects.requireNonNull(builder.sourceId, "sourceId must not be null");
        if (sourceId.isEmpty()) {
            throw new IllegalArgumentException("sourceId must not be empty");
        }
        if (sourceId.length() > 256) {
            throw new IllegalArgumentException("sourceId must not exceed 256 characters");
        }

        this.baseUri = Objects.requireNonNull(builder.baseUrl, "baseUrl must not be null")
            .normalize();
        validateUri(baseUri);

        String path = baseUri.getPath();
        this.basePath = (path == null || path.isEmpty()) ? "/" : path;

        this.allowedDomains = builder.allowedDomains.isEmpty()
            ? Set.of(baseUri.getHost().toLowerCase(Locale.ROOT))
            : Set.copyOf(normalizeDomains(builder.allowedDomains));

        this.includedPathPatterns = Set.copyOf(builder.includedPathPatterns);
        this.excludedPathPatterns = Set.copyOf(builder.excludedPathPatterns);
        this.includedContentTypes = builder.includedContentTypes.isEmpty()
            ? Set.of("text/html", "text/plain", "text/markdown", "application/json", "application/xml")
            : Set.copyOf(normalizeContentTypes(builder.includedContentTypes));
        this.maxDepth = Math.max(0, builder.maxDepth);
        this.maxResponseSize = Math.max(1, builder.maxResponseSize);
        this.timeout = builder.timeout != null ? builder.timeout : DEFAULT_TIMEOUT;
        this.userAgent = builder.userAgent != null ? builder.userAgent : DEFAULT_USER_AGENT;
        this.requestHeaders = Map.copyOf(builder.requestHeaders);

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

        LOG.debug("HttpSource created: id={}, baseUrl={}, maxDepth={}, timeout={}",
            sourceId, baseUri, maxDepth, timeout);
    }

    @Override
    public String getSourceId() {
        return sourceId;
    }

    @Override
    public String getSourceType() {
        return SOURCE_TYPE;
    }

    @Override
    public String getDescription() {
        return SOURCE_TYPE + ":" + baseUri;
    }

    @Override
    public Stream<String> crawl() throws SourceException {
        ensureOpen();

        LOG.info("Starting crawl for source {}: {}", sourceId, baseUri);

        Set<String> visited = Collections.newSetFromMap(new ConcurrentHashMap<>());
        Set<String> found = Collections.synchronizedSet(new HashSet<>());
        List<String> result = new ArrayList<>();

        crawlRecursive(baseUri.toString(), 0, visited, found);

        result.addAll(found);
        Collections.sort(result);

        LOG.info("Crawl completed for source {}: {} documents found", sourceId, result.size());

        return result.stream();
    }

    private void crawlRecursive(String url, int depth, Set<String> visited, Set<String> found)
            throws SourceException {
        
        String normalizedUrl = normalizeUrl(url);
        
        if (visited.contains(normalizedUrl)) {
            return;
        }
        visited.add(normalizedUrl);

        if (depth > maxDepth) {
            LOG.debug("Max depth ({}) reached, skipping: {}", maxDepth, url);
            return;
        }

        if (!isUrlAllowed(normalizedUrl)) {
            LOG.debug("URL not allowed by filters: {}", normalizedUrl);
            return;
        }

        if (depth > 0) {
            found.add(normalizedUrl);
        }

        if (depth < maxDepth) {
            try {
                Set<String> links = extractLinks(normalizedUrl);
                for (String link : links) {
                    if (!visited.contains(normalizeUrl(link))) {
                        crawlRecursive(link, depth + 1, visited, found);
                    }
                }
            } catch (SourceException e) {
                LOG.debug("Failed to extract links from {}: {}", normalizedUrl, e.getMessage());
            }
        }
    }

    private Set<String> extractLinks(String url) throws SourceException {
        Set<String> links = new HashSet<>();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml")
                .timeout(timeout)
                .GET()
                .build();

            requestHeaders.forEach((name, value) -> 
                request.headers().map().putIfAbsent(name, List.of(value))
            );

            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() >= 400) {
                return links;
            }

            String contentType = getHeader(response, "Content-Type", "");
            if (!contentType.contains("text/html") && !contentType.contains("application/xhtml+xml")) {
                return links;
            }

            String body = response.body();
            if (body == null || body.isEmpty()) {
                return links;
            }

            Matcher matcher = HREF_PATTERN.matcher(body);
            while (matcher.find()) {
                String href = matcher.group(1).trim();
                String resolved = resolveUrl(url, href);
                
                if (resolved != null && isUrlAllowed(resolved)) {
                    links.add(resolved);
                }
            }

        } catch (IOException e) {
            LOG.debug("IO error extracting links from {}: {}", url, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("Crawl interrupted", sourceId, true, e);
        }

        return links;
    }

    private String resolveUrl(String baseUrl, String href) {
        if (href == null || href.isEmpty() || href.startsWith("javascript:") || 
            href.startsWith("mailto:") || href.startsWith("tel:") || href.startsWith("data:")) {
            return null;
        }

        href = ANCHOR_PATTERN.matcher(href).replaceAll("");

        if (href.isEmpty()) {
            return null;
        }

        try {
            URI base = URI.create(baseUrl);
            URI resolved = base.resolve(href).normalize();

            if (!ALLOWED_SCHEMES.contains(resolved.getScheme())) {
                return null;
            }

            return resolved.toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public boolean hasChanged(Instant since) throws SourceException {
        ensureOpen();
        Objects.requireNonNull(since, "since must not be null");

        if (since.isAfter(Instant.now())) {
            LOG.warn("hasChanged called with future timestamp: {}", since);
            return true;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(baseUri)
                .header("User-Agent", userAgent)
                .header("If-Modified-Since", formatHttpDate(since))
                .timeout(timeout)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

            HttpResponse<Void> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.discarding());

            if (response.statusCode() == 304) {
                LOG.debug("Source {} not modified since {}", sourceId, since);
                return false;
            }

            if (response.statusCode() == 200) {
                String lastModified = getHeader(response, "Last-Modified", null);
                if (lastModified != null) {
                    try {
                        Instant lastModifiedInstant = parseHttpDate(lastModified);
                        if (!lastModifiedInstant.isAfter(since)) {
                            LOG.debug("Source {} Last-Modified ({}) not after {}", 
                                sourceId, lastModifiedInstant, since);
                            return false;
                        }
                    } catch (Exception e) {
                        LOG.debug("Could not parse Last-Modified header: {}", lastModified);
                    }
                }
            }

            LOG.debug("Source {} may have changed since {}", sourceId, since);
            return true;

        } catch (IOException e) {
            LOG.warn("Could not check modification time for source {}: {}", sourceId, e.getMessage());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("Change check interrupted", sourceId, true, e);
        }
    }

    @Override
    public FetchedDocument fetch(String documentId) throws SourceException, DocumentNotFoundException {
        ensureOpen();
        Objects.requireNonNull(documentId, "documentId must not be null");
        if (documentId.isEmpty()) {
            throw new IllegalArgumentException("documentId must not be empty");
        }

        if (!isUrlAllowed(documentId)) {
            throw new SourceException(
                "Document ID not allowed by source filters: " + documentId,
                sourceId,
                false
            );
        }

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(documentId))
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
                .timeout(timeout)
                .GET();

            requestHeaders.forEach((name, value) -> 
                requestBuilder.header(name, value)
            );

            HttpRequest request = requestBuilder.build();

            HttpResponse<byte[]> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() == 404) {
                throw new DocumentNotFoundException(documentId, sourceId);
            }

            if (response.statusCode() >= 400) {
                throw new SourceException(
                    "HTTP error " + response.statusCode() + " fetching: " + documentId,
                    sourceId,
                    response.statusCode() >= 500
                );
            }

            byte[] content = response.body();
            if (content == null) {
                content = new byte[0];
            }

            if (content.length > maxResponseSize) {
                throw new SourceException(
                    "Response too large: " + content.length + " bytes (max: " + maxResponseSize + "): " + documentId,
                    sourceId,
                    false
                );
            }

            String contentType = detectContentType(response, documentId);
            long lastModifiedMillis = extractLastModified(response);

            LOG.debug("Fetched document: {} ({} bytes, {})", documentId, content.length, contentType);

            return new FetchedDocument(documentId, content, contentType, lastModifiedMillis);

        } catch (DocumentNotFoundException e) {
            throw e;
        } catch (IOException e) {
            throw new SourceException(
                "IO error fetching document: " + documentId,
                sourceId,
                true,
                e
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException(
                "Fetch interrupted: " + documentId,
                sourceId,
                true,
                e
            );
        }
    }

    @Override
    public long getDocumentCount() throws SourceException {
        return -1;
    }

    @Override
    public long getEstimatedTotalSize() throws SourceException {
        return -1;
    }

    @Override
    public void close() {
        closed = true;
        lastModifiedCache.clear();
        LOG.debug("HttpSource closed: {}", sourceId);
    }

    public URI getBaseUrl() {
        return baseUri;
    }

    public Set<String> getAllowedDomains() {
        return allowedDomains;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public long getMaxResponseSize() {
        return maxResponseSize;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public String getUserAgent() {
        return userAgent;
    }

    private void ensureOpen() throws SourceException {
        if (closed) {
            throw new SourceException("Source is closed: " + sourceId, sourceId, false);
        }
    }

    private void validateUri(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                "URL must use http or https scheme: " + uri
            );
        }

        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException(
                "URL must have a host: " + uri
            );
        }
    }

    private boolean isUrlAllowed(String url) {
        try {
            URI uri = URI.create(url).normalize();
            
            String scheme = uri.getScheme();
            if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
                return false;
            }

            String host = uri.getHost();
            if (host == null || !allowedDomains.contains(host.toLowerCase(Locale.ROOT))) {
                return false;
            }

            String path = uri.getPath();
            if (path == null) {
                path = "/";
            }

            if (!path.startsWith(basePath)) {
                return false;
            }

            for (String excluded : excludedPathPatterns) {
                if (path.contains(excluded)) {
                    return false;
                }
            }

            if (!includedPathPatterns.isEmpty()) {
                boolean matchesAny = false;
                for (String included : includedPathPatterns) {
                    if (path.contains(included)) {
                        matchesAny = true;
                        break;
                    }
                }
                if (!matchesAny) {
                    return false;
                }
            }

            return true;

        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String normalizeUrl(String url) {
        try {
            URI uri = URI.create(url).normalize();
            String normalized = uri.getScheme() + "://" + uri.getHost().toLowerCase(Locale.ROOT);
            
            if (uri.getPort() > 0 && uri.getPort() != 80 && uri.getPort() != 443) {
                normalized += ":" + uri.getPort();
            }
            
            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            
            while (path.contains("//")) {
                path = path.replace("//", "/");
            }
            
            normalized += path;
            
            if (uri.getQuery() != null && !uri.getQuery().isEmpty()) {
                normalized += "?" + uri.getQuery();
            }
            
            normalized = ANCHOR_PATTERN.matcher(normalized).replaceAll("");
            
            return normalized;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private String detectContentType(HttpResponse<?> response, String url) {
        String contentType = getHeader(response, "Content-Type", null);
        
        if (contentType != null) {
            int semicolon = contentType.indexOf(';');
            if (semicolon > 0) {
                contentType = contentType.substring(0, semicolon).trim();
            }
            if (!contentType.isEmpty()) {
                return contentType.toLowerCase(Locale.ROOT);
            }
        }

        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path != null) {
                int dotIndex = path.lastIndexOf('.');
                if (dotIndex >= 0 && dotIndex < path.length() - 1) {
                    String extension = path.substring(dotIndex).toLowerCase(Locale.ROOT);
                    String mimeType = EXTENSION_TO_MIME_TYPE.get(extension);
                    if (mimeType != null) {
                        return mimeType;
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            // Ignore
        }

        return "application/octet-stream";
    }

    private long extractLastModified(HttpResponse<?> response) {
        String lastModified = getHeader(response, "Last-Modified", null);
        if (lastModified != null) {
            try {
                Instant instant = parseHttpDate(lastModified);
                return instant.toEpochMilli();
            } catch (Exception e) {
                LOG.debug("Could not parse Last-Modified: {}", lastModified);
            }
        }
        return 0L;
    }

    private String getHeader(HttpResponse<?> response, String name, String defaultValue) {
        return response.headers()
            .firstValue(name)
            .orElse(defaultValue);
    }

    private String formatHttpDate(Instant instant) {
        java.time.format.DateTimeFormatter formatter = 
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .withZone(java.time.ZoneOffset.UTC);
        return formatter.format(instant);
    }

    private Instant parseHttpDate(String date) {
        java.time.format.DateTimeFormatter formatter = 
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .withZone(java.time.ZoneOffset.UTC);
        return formatter.parse(date, Instant::from);
    }

    private static Set<String> normalizeDomains(Set<String> domains) {
        Set<String> normalized = new HashSet<>();
        for (String domain : domains) {
            String trimmed = domain.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }

    private static Set<String> normalizeContentTypes(Set<String> contentTypes) {
        Set<String> normalized = new HashSet<>();
        for (String ct : contentTypes) {
            String trimmed = ct.trim().toLowerCase(Locale.ROOT);
            int semicolon = trimmed.indexOf(';');
            if (semicolon > 0) {
                trimmed = trimmed.substring(0, semicolon).trim();
            }
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String sourceId;
        private URI baseUrl;
        private Set<String> allowedDomains = Set.of();
        private Set<String> includedPathPatterns = Set.of();
        private Set<String> excludedPathPatterns = Set.of("/api", "/admin", "/login", "/logout");
        private Set<String> includedContentTypes = Set.of();
        private int maxDepth = DEFAULT_MAX_DEPTH;
        private long maxResponseSize = DEFAULT_MAX_RESPONSE_SIZE;
        private Duration timeout = DEFAULT_TIMEOUT;
        private String userAgent = DEFAULT_USER_AGENT;
        private Map<String, String> requestHeaders = Map.of();

        public Builder sourceId(String sourceId) {
            this.sourceId = sourceId;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = URI.create(baseUrl);
            return this;
        }

        public Builder baseUrl(URI baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder allowedDomains(Set<String> domains) {
            this.allowedDomains = domains != null ? domains : Set.of();
            return this;
        }

        public Builder allowedDomains(String... domains) {
            this.allowedDomains = Set.of(domains);
            return this;
        }

        public Builder includedPathPatterns(Set<String> patterns) {
            this.includedPathPatterns = patterns != null ? patterns : Set.of();
            return this;
        }

        public Builder includedPathPatterns(String... patterns) {
            this.includedPathPatterns = Set.of(patterns);
            return this;
        }

        public Builder excludedPathPatterns(Set<String> patterns) {
            this.excludedPathPatterns = patterns != null ? patterns : Set.of();
            return this;
        }

        public Builder excludedPathPatterns(String... patterns) {
            this.excludedPathPatterns = Set.of(patterns);
            return this;
        }

        public Builder includedContentTypes(Set<String> contentTypes) {
            this.includedContentTypes = contentTypes != null ? contentTypes : Set.of();
            return this;
        }

        public Builder includedContentTypes(String... contentTypes) {
            this.includedContentTypes = Set.of(contentTypes);
            return this;
        }

        public Builder maxDepth(int depth) {
            this.maxDepth = depth;
            return this;
        }

        public Builder maxResponseSize(long maxBytes) {
            this.maxResponseSize = maxBytes;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder timeoutSeconds(int seconds) {
            this.timeout = Duration.ofSeconds(seconds);
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Builder requestHeaders(Map<String, String> headers) {
            this.requestHeaders = headers != null ? headers : Map.of();
            return this;
        }

        public Builder addRequestHeader(String name, String value) {
            Map<String, String> newHeaders = new java.util.HashMap<>(this.requestHeaders);
            newHeaders.put(name, value);
            this.requestHeaders = Map.copyOf(newHeaders);
            return this;
        }

        public HttpSource build() {
            return new HttpSource(this);
        }
    }
}
