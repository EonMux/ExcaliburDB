package com.eonmux.excalibur.ingest.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * <h2>Fetch Policy</h2>
 * <p>Every URL this source contacts - the base URL, each link followed during a crawl, and
 * each redirect hop - is checked against {@link #isUrlAllowed(String)} before the request is
 * issued. Redirects are therefore <strong>not</strong> delegated to the {@link HttpClient}:
 * the client is built with {@link HttpClient.Redirect#NEVER} and each {@code Location} is
 * resolved, re-checked and only then followed. A redirect out of policy fails the fetch rather
 * than silently returning another host's body under an in-policy document id.</p>
 *
 * <p>Response bodies are read through a bounded stream, so {@code maxResponseSize} caps what
 * is allocated rather than being checked after the whole body is already on the heap.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Base URL must be a valid HTTP or HTTPS URL</li>
 *   <li>Maximum response size: 10MB (configurable), enforced while reading</li>
 *   <li>Maximum documents per crawl: 10,000 (configurable)</li>
 *   <li>Maximum redirect hops: 5, each re-checked against the URL filters</li>
 *   <li>Request timeout: 30 seconds (configurable)</li>
 *   <li>Maximum crawl depth: 3 (configurable)</li>
 *   <li>User-Agent: ExcaliburDB/1.0 (configurable)</li>
 *   <li>Minimum gap between requests to the host: 200ms (configurable)</li>
 *   <li>URLs are normalized before comparison</li>
 * </ul>
 *
 * <h2>Pacing</h2>
 * <p>The walk is sequential and issues two requests per page - one to read its links, one to
 * fetch it - so nothing but the host's own latency limited how fast it was asked for pages.
 * Every outbound request here waits until at least {@code crawlDelay} has passed since the last
 * one left. The wait is taken outside the lock that reserves the slot, so a source's crawl never
 * pins the virtual thread it runs on, and a deployment that wants none sets the delay to zero.</p>
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

    /** Five requests a second, unless a deployment says otherwise. */
    private static final Duration DEFAULT_CRAWL_DELAY = Duration.ofMillis(200);
    private static final int DEFAULT_MAX_DEPTH = 3;
    private static final String DEFAULT_USER_AGENT = "ExcaliburDB/1.0";

    /**
     * Upper bound on the number of documents one crawl may discover.
     *
     * <p>{@code maxDepth} bounds how deep the walk goes but not how wide it is. A site with a
     * generated URL space - a calendar, a faceted search, session ids in the path - branches
     * without limit within any depth, so the walk needs a budget of its own.</p>
     */
    private static final int DEFAULT_MAX_DOCUMENTS = 10_000;

    /**
     * Maximum number of redirect hops followed for a single request.
     */
    private static final int MAX_REDIRECTS = 5;

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

    /** The {@code charset} parameter of a {@code Content-Type} header. */
    private static final Pattern CHARSET_PARAMETER = Pattern.compile(
        ";\\s*charset\\s*=\\s*([^;\\s]+)", Pattern.CASE_INSENSITIVE);

    private final String sourceId;
    private final URI baseUri;
    private final String basePath;
    private final Set<String> allowedDomains;

    /** Whether hosts resolving to loopback or private ranges may be crawled. */
    private final boolean allowPrivateAddresses;
    private final Set<String> includedPathPatterns;
    private final Set<String> excludedPathPatterns;
    private final Set<String> includedContentTypes;
    private final int maxDepth;
    private final int maxDocuments;
    private final long maxResponseSize;
    private final Duration timeout;
    private final Duration crawlDelay;

    /** Reserves the next request slot. Held only to claim a time, never while waiting for it. */
    private final ReentrantLock paceLock = new ReentrantLock();

    /** The earliest {@link System#nanoTime} at which the next request may leave. */
    private long nextRequestNanos = System.nanoTime();
    private final String userAgent;
    private final HttpClient httpClient;
    private final Map<String, String> requestHeaders;

    private final Map<String, Long> lastModifiedCache = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    /**
     * Closes response bodies that stop producing bytes.
     *
     * <p>One daemon thread per source. It only ever closes a stream, so it never runs long, and
     * a daemon thread cannot hold the JVM open if a source is not closed.</p>
     */
    private final ScheduledExecutorService bodyReadWatchdog = Executors.newSingleThreadScheduledExecutor(
        runnable -> {
            Thread thread = new Thread(runnable, "excalibur-http-body-watchdog");
            thread.setDaemon(true);
            return thread;
        });

    private HttpSource(Builder builder) {
        this.sourceId = Objects.requireNonNull(builder.sourceId, "sourceId must not be null");
        if (sourceId.isEmpty()) {
            throw new IllegalArgumentException("sourceId must not be empty");
        }
        if (sourceId.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(
                "sourceId must not exceed " + MAX_ID_LENGTH + " characters");
        }

        this.baseUri = Objects.requireNonNull(builder.baseUrl, "baseUrl must not be null")
            .normalize();
        validateUri(baseUri);

        String path = baseUri.getPath();
        this.basePath = (path == null || path.isEmpty()) ? "/" : path;

        this.allowPrivateAddresses = builder.allowPrivateAddresses;
        this.allowedDomains = builder.allowedDomains.isEmpty()
            ? Set.of(baseUri.getHost().toLowerCase(Locale.ROOT))
            : Set.copyOf(normalizeDomains(builder.allowedDomains));

        this.includedPathPatterns = Set.copyOf(builder.includedPathPatterns);
        this.excludedPathPatterns = Set.copyOf(builder.excludedPathPatterns);
        this.includedContentTypes = builder.includedContentTypes.isEmpty()
            ? Set.of("text/html", "text/plain", "text/markdown", "application/json", "application/xml")
            : Set.copyOf(normalizeContentTypes(builder.includedContentTypes));
        this.maxDepth = Math.max(0, builder.maxDepth);
        this.maxDocuments = Math.max(1, builder.maxDocuments);
        this.maxResponseSize = Math.max(1, builder.maxResponseSize);
        this.timeout = builder.timeout != null ? builder.timeout : DEFAULT_TIMEOUT;
        this.crawlDelay = builder.crawlDelay != null ? builder.crawlDelay : DEFAULT_CRAWL_DELAY;
        this.userAgent = builder.userAgent != null ? builder.userAgent : DEFAULT_USER_AGENT;
        this.requestHeaders = Map.copyOf(builder.requestHeaders);
        validateRequestHeaders(this.requestHeaders);

        // Redirects are followed by this class, not by the client, so that every hop passes
        // through isUrlAllowed. See the class javadoc.
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
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

        List<String> result = new ArrayList<>(crawlBreadthFirst());
        Collections.sort(result);

        LOG.info("Crawl completed for source {}: {} documents found", sourceId, result.size());

        return result.stream();
    }

    /**
     * Walks the site breadth-first from the base URL, bounded by {@code maxDepth} in depth and
     * {@code maxDocuments} in breadth.
     *
     * <p>The walk is iterative rather than recursive: a generated URL space branches without
     * limit, and a recursive walk both overflows the stack and holds every frontier alive until
     * it unwinds. The frontier queue here is dropped as it drains, and the budget stops the walk
     * before {@code visited} can grow without bound.</p>
     *
     * @return The in-policy document URLs discovered below the base URL
     * @throws SourceException if the crawl is interrupted
     */
    private Set<String> crawlBreadthFirst() throws SourceException {
        Set<String> visited = new HashSet<>();
        Set<String> found = new HashSet<>();
        Deque<CrawlTarget> frontier = new ArrayDeque<>();

        String start = normalizeUrl(baseUri.toString());
        visited.add(start);
        frontier.add(new CrawlTarget(start, 0));

        while (!frontier.isEmpty()) {
            CrawlTarget target = frontier.poll();

            if (!isUrlAllowed(target.url())) {
                LOG.debug("URL not allowed by filters: {}", target.url());
                continue;
            }

            if (target.depth() > 0 && !found.add(target.url())) {
                continue;
            }

            if (found.size() >= maxDocuments) {
                LOG.warn("Crawl budget of {} documents reached for source {}; stopping walk",
                    maxDocuments, sourceId);
                break;
            }

            if (target.depth() >= maxDepth) {
                continue;
            }

            for (String link : extractLinks(target.url())) {
                String normalized = normalizeUrl(link);
                if (visited.add(normalized)) {
                    frontier.add(new CrawlTarget(normalized, target.depth() + 1));
                }
            }
        }

        return found;
    }

    /**
     * One entry of the crawl frontier: an already-normalized URL and the depth it was found at.
     */
    private record CrawlTarget(String url, int depth) {
    }

    /**
     * Fetches a page and returns the in-policy links it contains.
     *
     * <p>A page that cannot be fetched yields no links rather than failing the crawl; only an
     * interruption stops the walk.</p>
     *
     * @param url An in-policy, normalized URL
     * @return The set of in-policy absolute URLs linked from the page
     * @throws SourceException if the crawl was interrupted
     */
    private Set<String> extractLinks(String url) throws SourceException {
        Set<String> links = new HashSet<>();

        try {
            HttpResponse<InputStream> response = get(url, "text/html,application/xhtml+xml");

            String contentType = getHeader(response, "Content-Type", "");
            boolean html = contentType.contains("text/html")
                || contentType.contains("application/xhtml+xml");
            if (response.statusCode() >= 400 || !html) {
                closeQuietly(response.body());
                return links;
            }

            byte[] bytes = readBoundedBody(response, url);
            // The page's own encoding: a UTF-16 page decoded as UTF-8 is NUL-interleaved text in
            // which no href matches, so the whole subtree below it silently leaves the crawl.
            String body = new String(bytes, detectCharset(response, bytes));
            if (body.isEmpty()) {
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

        } catch (SourceException e) {
            LOG.debug("Failed to extract links from {}: {}", url, e.getMessage());
        } catch (IOException e) {
            LOG.debug("IO error extracting links from {}: {}", url, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("Crawl interrupted", sourceId, true, e);
        }

        return links;
    }

    /**
     * Waits until this crawl may make its next request to the host.
     *
     * <p>The slot is reserved under the lock and waited for outside it. Reserving it is what
     * makes the gap hold when two threads ask at once - a crawl and the scheduler's change
     * check, say - because the second one is handed a later slot rather than finding the same
     * "last request" time as the first. Waiting outside the lock is what keeps a sleeping crawl
     * from pinning the carrier thread its virtual thread runs on, which is what a sleep inside a
     * monitor would do on this runtime.</p>
     *
     * @throws InterruptedException if the wait is interrupted, which is how a stopped source
     *                              stops rather than finishing its delay first
     */
    private void pace() throws InterruptedException {
        long delayNanos = crawlDelay.toNanos();
        if (delayNanos <= 0) {
            return;
        }

        long waitNanos;
        paceLock.lock();
        try {
            long now = System.nanoTime();
            // Subtraction, not comparison: System.nanoTime has no fixed origin and wraps.
            waitNanos = Math.max(0, nextRequestNanos - now);
            nextRequestNanos = now + waitNanos + delayNanos;
        } finally {
            paceLock.unlock();
        }

        if (waitNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
        }
    }

    /**
     * Issues a GET for an in-policy URL, following redirects one hop at a time.
     *
     * <p>Each {@code Location} is resolved against the URL that produced it and re-checked with
     * {@link #isUrlAllowed(String)} before it is requested, so a crawled host cannot redirect the
     * ingester at an address the source's filters would have rejected. The returned response's
     * body stream is owned by the caller.</p>
     *
     * @param url    The in-policy URL to request
     * @param accept The {@code Accept} header value
     * @return The first non-redirect response
     * @throws SourceException if a redirect leaves the policy or the hop limit is exceeded
     */
    private HttpResponse<InputStream> get(String url, String accept)
            throws SourceException, IOException, InterruptedException {
        String target = url;

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            // Per hop, not per call: a redirect is another request to the host, and a chain of
            // them issued back to back is exactly the burst the delay exists to prevent.
            pace();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(target))
                .header("User-Agent", userAgent)
                .header("Accept", accept)
                .timeout(timeout)
                .GET();
            requestHeaders.forEach(builder::header);

            HttpResponse<InputStream> response =
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());

            if (!isRedirect(response.statusCode())) {
                return response;
            }

            closeQuietly(response.body());
            target = redirectTarget(target, response);
        }

        throw new SourceException(
            "Too many redirects (max " + MAX_REDIRECTS + ") starting at: " + url, sourceId, false);
    }

    /**
     * Resolves and authorizes the next hop of a redirect.
     *
     * @param from     The URL that returned the redirect
     * @param response The redirect response
     * @return The normalized, in-policy URL to request next
     * @throws SourceException if there is no usable Location, or it is outside the source policy
     */
    private String redirectTarget(String from, HttpResponse<InputStream> response)
            throws SourceException {
        String location = getHeader(response, "Location", null);
        if (location == null || location.isBlank()) {
            throw new SourceException(
                "Redirect " + response.statusCode() + " without Location header: " + from,
                sourceId, false);
        }

        String next = resolveUrl(from, location);
        if (next == null || !isUrlAllowed(next)) {
            // Worth a warning of its own: this is a crawled host trying to steer the ingester
            // somewhere the source's filters would have rejected, and the caller may well
            // swallow the exception as "this page yielded no links".
            LOG.warn("Source {} refused a redirect from {} to {}: outside the source policy",
                sourceId, from, location);
            throw new SourceException(
                "Redirect from " + from + " to " + location + " is outside the source policy",
                sourceId, false);
        }
        return normalizeUrl(next);
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
            || statusCode == 307 || statusCode == 308;
    }

    /**
     * Reads a response body, refusing to allocate more than {@code maxResponseSize} bytes.
     *
     * <p>Reading one byte past the limit is what distinguishes "exactly at the limit" from
     * "over it" without buffering the rest of the body: a hostile or merely huge response is
     * abandoned after that many bytes rather than growing the heap until the JVM dies.</p>
     *
     * @param response The response whose body should be read; its stream is always closed
     * @param url      The URL being read, for the error message
     * @return The body bytes
     * @throws SourceException if the body exceeds {@code maxResponseSize}
     * @throws IOException     if reading fails
     */
    private byte[] readBoundedBody(HttpResponse<InputStream> response, String url)
            throws SourceException, IOException {
        // Clamp before adding the extra byte: maxResponseSize is a long and a caller may set it
        // near Long.MAX_VALUE, where the increment would wrap into a negative read length.
        int limit = (int) Math.min(maxResponseSize, Integer.MAX_VALUE - 1L) + 1;

        InputStream body = response.body();
        // The request timeout does not reach here. With a streaming body handler the client
        // completes send() as soon as the headers arrive, so a server that sends headers and
        // then stops leaves this read blocked with no deadline of its own - the crawl for that
        // source never finishes and nothing reports it. Closing the stream from the watchdog is
        // what unblocks the read; there is no socket read timeout to set.
        AtomicBoolean timedOut = new AtomicBoolean(false);
        ScheduledFuture<?> watchdog = bodyReadWatchdog.schedule(() -> {
            timedOut.set(true);
            closeQuietly(body);
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);

        try (body) {
            byte[] content = body.readNBytes(limit);
            if (content.length > maxResponseSize) {
                throw new SourceException(
                    "Response too large: exceeds " + maxResponseSize + " bytes: " + url,
                    sourceId,
                    false);
            }
            return content;
        } catch (IOException e) {
            if (timedOut.get()) {
                throw new SourceException(
                    "Timed out after " + timeout + " reading the response body: " + url,
                    sourceId,
                    true,
                    e);
            }
            throw e;
        } finally {
            watchdog.cancel(false);
            if (timedOut.get()) {
                throw new SourceException(
                    "Timed out after " + timeout + " reading the response body: " + url,
                    sourceId,
                    true);
            }
        }
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException e) {
            LOG.debug("Failed to close response body: {}", e.getMessage());
        }
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
            pace();
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(baseUri)
                .header("User-Agent", userAgent)
                .header("If-Modified-Since", formatHttpDate(since))
                .timeout(timeout)
                .method("HEAD", HttpRequest.BodyPublishers.noBody());
            requestHeaders.forEach(requestBuilder::header);

            HttpResponse<Void> response = httpClient.send(requestBuilder.build(),
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
            HttpResponse<InputStream> response = get(documentId, "*/*");

            if (response.statusCode() == 404) {
                closeQuietly(response.body());
                throw new DocumentNotFoundException(documentId, sourceId);
            }

            if (response.statusCode() >= 400) {
                closeQuietly(response.body());
                throw new SourceException(
                    "HTTP error " + response.statusCode() + " fetching: " + documentId,
                    sourceId,
                    response.statusCode() >= 500
                );
            }

            byte[] content = readBoundedBody(response, documentId);

            String contentType = detectContentType(response, documentId);
            requireIncludedContentType(contentType, documentId);
            Charset charset = detectCharset(response, content);
            long lastModifiedMillis = extractLastModified(response);

            LOG.debug("Fetched document: {} ({} bytes, {}, {})",
                documentId, content.length, contentType, charset);

            return new FetchedDocument(documentId, content, contentType, charset, lastModifiedMillis);

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
        bodyReadWatchdog.shutdownNow();
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

    /**
     * Returns the maximum number of documents one crawl may yield.
     *
     * @return The crawl budget in documents
     */
    public int getMaxDocuments() {
        return maxDocuments;
    }

    public long getMaxResponseSize() {
        return maxResponseSize;
    }

    public Duration getTimeout() {
        return timeout;
    }

    /**
     * The shortest gap this crawl leaves between two requests to its host.
     *
     * @return The gap; {@link Duration#ZERO} when this crawl is not paced
     */
    public Duration getCrawlDelay() {
        return crawlDelay;
    }

    public String getUserAgent() {
        return userAgent;
    }

    private void ensureOpen() throws SourceException {
        if (closed) {
            throw new SourceException("Source is closed: " + sourceId, sourceId, false);
        }
    }

    /**
     * Rejects configured headers the HTTP client will not let a request carry.
     *
     * <p>{@code HttpRequest.Builder.header} refuses a handful of restricted names
     * ({@code Host}, {@code Connection}, {@code Content-Length}, ...). Finding that out on the
     * first crawled page would abort the run with an unchecked exception far from the
     * misconfiguration, so the check happens once, here, where the operator set the header.</p>
     *
     * @param headers The configured request headers
     * @throws IllegalArgumentException if a header cannot be set on an outgoing request
     */
    private static void validateRequestHeaders(Map<String, String> headers) {
        if (headers.isEmpty()) {
            return;
        }

        HttpRequest.Builder probe = HttpRequest.newBuilder(URI.create("http://localhost/"));
        headers.forEach((name, value) -> {
            try {
                probe.header(name, value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "Request header cannot be set on an outgoing request: " + name, e);
            }
        });
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

    /**
     * Rejects a host that resolves anywhere internal.
     *
     * <p>Deliberately a second, independent check. The API layer validates addresses when a source
     * is registered, but that is one moment in time: DNS can change afterwards, and a crawl runs
     * later and repeatedly. Re-resolving here is what makes a rebind ineffective, and it is the
     * check that survives whoever else constructs an {@code HttpSource}.
     *
     * <p>A host that cannot be resolved is refused. Failing open here would turn every transient
     * DNS failure into a bypass.
     *
     * <p>Note the JVM caches successful lookups (see {@code networkaddress.cache.ttl}), so this
     * costs a resolution per distinct host rather than per URL — and a very long cache TTL narrows
     * the protection, which is worth knowing when tuning it.
     */
    private boolean isAddressAllowed(String host) {
        if (allowPrivateAddresses) {
            return true;
        }
        try {
            for (java.net.InetAddress address : java.net.InetAddress.getAllByName(host)) {
                if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    LOG.warn("Refusing {}: it resolves to a non-public address", host);
                    return false;
                }
                // IPv6 unique-local, fc00::/7, which none of the predicates above cover.
                byte[] bytes = address.getAddress();
                if (bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC) {
                    LOG.warn("Refusing {}: it resolves into fc00::/7", host);
                    return false;
                }
            }
            return true;
        } catch (java.net.UnknownHostException e) {
            LOG.warn("Refusing {}: host could not be resolved", host);
            return false;
        }
    }

    /**
     * Exposes {@link #isUrlAllowed} for tests.
     *
     * <p>The decision is otherwise only observable by running a crawl, which makes the cases worth
     * testing — a host that is allow-listed but resolves somewhere internal — awkward to reach.
     */
    boolean isUrlAllowedForTesting(String url) {
        return isUrlAllowed(url);
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

            if (!isAddressAllowed(host)) {
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

    /**
     * Canonicalises a URL for the frontier and for use as a document id.
     *
     * <p>Built from the <em>raw</em> path and query. The decoded forms lose the distinction
     * between a character and its escape: a decoded {@code %20} makes a URL that
     * {@link URI#create} rejects, so the page is dropped from the crawl with only a debug line;
     * a decoded {@code %2F} becomes a path separator and a decoded {@code %26} splits one query
     * parameter into two, so the crawler requests a different resource than the one it was
     * pointed at and files it under an id that does not identify it.</p>
     *
     * @param url The URL to normalise
     * @return The canonical form, or the input unchanged if it cannot be parsed
     */
    private String normalizeUrl(String url) {
        try {
            URI uri = URI.create(url).normalize();
            if (uri.getHost() == null) {
                return url;
            }
            StringBuilder normalized = new StringBuilder()
                .append(uri.getScheme())
                .append("://")
                .append(uri.getHost().toLowerCase(Locale.ROOT));

            if (uri.getPort() > 0 && uri.getPort() != 80 && uri.getPort() != 443) {
                normalized.append(':').append(uri.getPort());
            }

            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            while (path.contains("//")) {
                path = path.replace("//", "/");
            }
            normalized.append(path);

            // The fragment is dropped by taking the raw query rather than by a pattern over the
            // whole URL: a '#' that appears percent-encoded inside a query value is part of the
            // value, and stripping from it truncates the query.
            String query = uri.getRawQuery();
            if (query != null && !query.isEmpty()) {
                normalized.append('?').append(query);
            }

            return normalized.toString();
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    /**
     * Rejects a document whose type the source was configured to exclude.
     *
     * <p>The filter is a policy an operator sets to keep binaries out of the index. Fetching
     * them anyway leaves their bytes to be decoded as text and indexed as mojibake, with nothing
     * said about it.</p>
     *
     * @param contentType The detected type
     * @param documentId The document being fetched
     * @throws SourceException if the type is not in the configured set
     */
    private void requireIncludedContentType(String contentType, String documentId)
            throws SourceException {
        if (includedContentTypes.isEmpty() || includedContentTypes.contains(contentType)) {
            return;
        }
        throw new SourceException(
            "Content type not accepted by this source: " + contentType + " for " + documentId,
            sourceId,
            false);
    }

    /**
     * The encoding a response's bytes are in.
     *
     * <p>The {@code charset} parameter of {@code Content-Type} when the server sent one and the
     * JVM knows it, a byte-order mark when it did not, and UTF-8 otherwise. Guessing UTF-8 for a
     * windows-1252 page does not fail: it turns every non-ASCII byte into U+FFFD, so the words
     * the document is indexed under are not the words it contains.</p>
     *
     * @param response The response
     * @param content The body bytes, for the byte-order mark
     * @return The charset, never null
     */
    private Charset detectCharset(HttpResponse<?> response, byte[] content) {
        String header = getHeader(response, "Content-Type", null);
        Charset declared = charsetOf(header);
        if (declared != null) {
            return declared;
        }
        Charset marked = charsetOfByteOrderMark(content);
        return marked != null ? marked : StandardCharsets.UTF_8;
    }

    /**
     * The charset named by a {@code Content-Type} header, or null when there is none the JVM
     * supports.
     *
     * @param contentTypeHeader The raw header value, may be null
     * @return The charset, or null
     */
    private static Charset charsetOf(String contentTypeHeader) {
        if (contentTypeHeader == null) {
            return null;
        }
        Matcher matcher = CHARSET_PARAMETER.matcher(contentTypeHeader);
        if (!matcher.find()) {
            return null;
        }
        String name = matcher.group(1).trim();
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
            name = name.substring(1, name.length() - 1);
        }
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) {
            // Unknown or malformed: fall back rather than fail the fetch over a header.
            LOG.debug("Ignoring unsupported charset '{}' in Content-Type", name);
            return null;
        }
    }

    /**
     * The charset a byte-order mark declares, or null when the content carries none.
     *
     * @param content The body bytes
     * @return The charset, or null
     */
    private static Charset charsetOfByteOrderMark(byte[] content) {
        if (content.length >= 3
            && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB
            && (content[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (content.length >= 2) {
            int first = content[0] & 0xFF;
            int second = content[1] & 0xFF;
            if (first == 0xFE && second == 0xFF) {
                return StandardCharsets.UTF_16BE;
            }
            if (first == 0xFF && second == 0xFE) {
                return StandardCharsets.UTF_16LE;
            }
        }
        return null;
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
        private boolean allowPrivateAddresses = false;
        private Set<String> includedPathPatterns = Set.of();
        private Set<String> excludedPathPatterns = Set.of("/api", "/admin", "/login", "/logout");
        private Set<String> includedContentTypes = Set.of();
        private int maxDepth = DEFAULT_MAX_DEPTH;
        private int maxDocuments = DEFAULT_MAX_DOCUMENTS;
        private long maxResponseSize = DEFAULT_MAX_RESPONSE_SIZE;
        private Duration timeout = DEFAULT_TIMEOUT;
        private Duration crawlDelay = DEFAULT_CRAWL_DELAY;
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

        /**
         * Permits crawling hosts that resolve to loopback or private addresses.
         *
         * <p>Off by default, and it should stay off in production. An allow-listed hostname is not
         * a guarantee about where it points: DNS can be changed between the moment a source is
         * registered and the moment it is fetched, so a name that resolved to a public address at
         * registration can resolve to 169.254.169.254 by the time the crawl runs. Checking the
         * address per request is what closes that window, and this flag is the only way past it.
         *
         * <p>Turn it on for tests that crawl a local server, and for deliberate crawls of an
         * internal host — both of which are legitimate, and both of which should have to say so.
         */
        public Builder allowPrivateAddresses(boolean allow) {
            this.allowPrivateAddresses = allow;
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

        /**
         * Caps how many documents a single crawl may yield.
         *
         * @param maxDocuments The budget; values below 1 are clamped to 1
         * @return This builder
         */
        public Builder maxDocuments(int maxDocuments) {
            this.maxDocuments = maxDocuments;
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

        /**
         * Sets the shortest gap between two requests this crawl makes to its host.
         *
         * @param crawlDelayMillis Milliseconds; zero paces nothing, negative is read as zero
         * @return This builder
         */
        public Builder crawlDelayMillis(long crawlDelayMillis) {
            this.crawlDelay = Duration.ofMillis(Math.max(0, crawlDelayMillis));
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
