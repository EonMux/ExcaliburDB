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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
 * Multiple threads may call {@link #crawl()}, {@link #fetch(String)} and
 * {@link #fetchIfChanged} concurrently.</p>
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
 * is allocated rather than being checked after the whole body is already on the heap. The
 * bound may not exceed {@link DocumentLimits#MAX_DOCUMENT_BYTES}: a larger body could not be
 * indexed, and a larger bound would only let one be held before it failed.</p>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>Base URL must be a valid HTTP or HTTPS URL</li>
 *   <li>Maximum response size: 10 MiB (configurable up to 100 MiB), enforced while reading</li>
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
 * <h2>robots.txt</h2>
 * <p>Honoured by default, as RFC 9309 specifies, and switched off only by a source built with
 * {@code respectRobotsTxt(false)}. Every URL is judged against its origin's file before it is
 * requested - the base URL, each link the walk finds, and each redirect hop - so a disallowed
 * URL is never listed and never fetched, and a fetch redirected onto one fails with a
 * {@link FetchDisallowedException} before the second request leaves. The file is read once per
 * origin per run (see {@link RobotsPolicy}) through the same client, host policy, address check,
 * timeout, pacing and redirect handling as any other request; only the path filters are not
 * applied to it, because {@code /robots.txt} sits outside the base path of most crawls.</p>
 *
 * <p>A file the origin cannot serve - a 5xx answer, a network failure, or a redirect off the
 * allowed hosts - stands, as the RFC requires, for a complete disallow. A crawl that meets one
 * fails rather than listing what it could reach: a listing with an origin's pages missing
 * because its server had a bad minute would read to change detection as a site that deleted
 * them. A fetch that meets one fails as retryable, and the indexed version is kept. A
 * {@code Crawl-delay} can only slow the crawl: the gap before a request to an origin is the
 * larger of {@code crawlDelay} and the origin's own, which is held to
 * {@link RobotsRules#MAX_CRAWL_DELAY}.</p>
 *
 * <h2>Pages whose links cannot be read</h2>
 * <p>One page that fails does not fail the crawl, but neither is it read as a page with no
 * links. A page that did not answer for itself - a {@code 5xx}, {@code 408}, {@code 429},
 * {@code 401}, {@code 403} or {@code 407}, a timeout, a body over {@code maxResponseSize} - is
 * a gap in the listing, reported by {@link #getListingGaps()}, and change detection keeps
 * whatever the listing missed rather than removing it. A page that is gone or is not HTML links
 * to nothing, and what only it led to leaves the listing, as it should.</p>
 *
 * <h2>Change Detection</h2>
 * <p>Per document, with a conditional GET. Every fetch records the {@code ETag} and
 * {@code Last-Modified} the server sent, the document registry keeps them beside the content
 * hash, and a re-crawl hands them back through {@link #fetchIfChanged}, which sends them as
 * {@code If-None-Match} and {@code If-Modified-Since}. A {@code 304 Not Modified} is taken as
 * the server's word that the indexed version is current and the body is never transferred; any
 * other answer is fetched and compared by content as before, so a server that ignores the
 * conditions costs a full fetch and nothing worse. A 304 is only an answer to a question: to a
 * request that went out with no condition - no validators, or only a tag a header cannot carry -
 * it is a {@link SourceException}, never "unchanged". The link walk of a crawl still reads every
 * HTML page it follows links from, because a page's links cannot be read from a 304.</p>
 *
 * <h2>Resource Management</h2>
 * <p>The internal HttpClient is shared and does not need explicit closing. Call
 * {@link #close()} to stop the body-read watchdog thread.</p>
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

    /** The paths a crawl keeps out when its registration names none of its own. */
    private static final Set<String> DEFAULT_EXCLUDED_PATH_PATTERNS =
        Set.of("/api", "/admin", "/login", "/logout");
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

    /** Where RFC 9309 puts an origin's crawling rules. */
    private static final String ROBOTS_PATH = "/robots.txt";

    /** What a robots.txt request asks for: the plain text the file is, and anything else. */
    private static final String ROBOTS_ACCEPT = "text/plain,*/*;q=0.5";

    /** The answer to a conditional request whose version the server still holds. */
    private static final int NOT_MODIFIED = 304;

    /** {@code 404 Not Found}: the page is not there. */
    private static final int NOT_FOUND = 404;

    /** {@code 410 Gone}: the page was there and has been removed for good. */
    private static final int GONE = 410;

    /** {@code 401 Unauthorized}: the credentials were refused, for every page alike. */
    private static final int UNAUTHORIZED = 401;

    /** {@code 403 Forbidden}: the page is there and is withheld from this crawler. */
    private static final int FORBIDDEN = 403;

    /** {@code 407 Proxy Authentication Required}: the proxy's answer, not the page's. */
    private static final int PROXY_AUTHENTICATION_REQUIRED = 407;

    /** {@code 408 Request Timeout}: the server gave up waiting, which says nothing of the page. */
    private static final int REQUEST_TIMEOUT = 408;

    /** {@code 429 Too Many Requests}: the server is shedding load, not answering for the page. */
    private static final int TOO_MANY_REQUESTS = 429;

    /** IMF-fixdate, the one form of HTTP date a sender may produce. */
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter
        .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
        .withZone(ZoneOffset.UTC);

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

    /** Whether each origin's robots.txt is read and obeyed. */
    private final boolean respectRobotsTxt;

    /**
     * The robots.txt rules of the current run, replaced when a crawl begins; null when this
     * source does not respect robots.txt.
     */
    private volatile RobotsPolicy robots;

    /** The pages the current run could not read for links; replaced when a crawl begins. */
    private volatile ListingGaps.Recorder listingGaps = new ListingGaps.Recorder();

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
            ? Set.of("text/html", "application/xhtml+xml", "text/plain", "text/markdown",
                "application/json", "application/xml")
            : Set.copyOf(normalizeContentTypes(builder.includedContentTypes));
        this.maxDepth = Math.max(0, builder.maxDepth);
        this.maxDocuments = Math.max(1, builder.maxDocuments);
        this.maxResponseSize = Math.max(1,
            DocumentLimits.requireIndexable(builder.maxResponseSize, "maxResponseSize"));
        this.timeout = builder.timeout != null ? builder.timeout : DEFAULT_TIMEOUT;
        this.crawlDelay = builder.crawlDelay != null ? builder.crawlDelay : DEFAULT_CRAWL_DELAY;
        this.userAgent = builder.userAgent != null ? builder.userAgent : DEFAULT_USER_AGENT;
        this.requestHeaders = Map.copyOf(builder.requestHeaders);
        validateRequestHeaders(this.requestHeaders);
        this.respectRobotsTxt = builder.respectRobotsTxt;
        // A fetch made before any crawl has a run to judge it by too.
        this.robots = newRobotsRun();

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
        robots = newRobotsRun();
        ListingGaps.Recorder gaps = new ListingGaps.Recorder();
        listingGaps = gaps;

        List<String> result = new ArrayList<>(crawlBreadthFirst(gaps));
        Collections.sort(result);

        ListingGaps unread = gaps.snapshot();
        if (unread.isComplete()) {
            LOG.info("Crawl completed for source {}: {} documents found", sourceId, result.size());
        } else {
            LOG.warn("Crawl completed for source {}: {} documents found, and the listing is "
                + "incomplete: {}", sourceId, result.size(), unread.describe());
        }

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
     * @param gaps Told of each page whose links could not be read
     * @return The in-policy document URLs: the base URL and those discovered below it
     * @throws SourceException if the crawl is interrupted, or an origin's robots.txt cannot be
     *                         read
     */
    private Set<String> crawlBreadthFirst(ListingGaps.Recorder gaps) throws SourceException {
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

            if (!robotsAllowCrawling(target.url())) {
                continue;
            }

            // The base URL is a document like any other: a depth of zero is the one page it names
            if (!found.add(target.url())) {
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

            Set<String> links;
            try {
                links = extractLinks(target.url(), gaps);
            } catch (FetchDisallowedException e) {
                // It redirects onto a URL robots.txt forbids, so fetching it later would too.
                LOG.debug("Leaving {} out of the crawl: {}", target.url(), e.getMessage());
                found.remove(target.url());
                robots.recordDisallowed(target.url());
                continue;
            }
            for (String link : links) {
                String normalized = normalizeUrl(link);
                if (visited.add(normalized)) {
                    frontier.add(new CrawlTarget(normalized, target.depth() + 1));
                }
            }
        }

        return found;
    }

    /**
     * Whether the walk may request a URL, recording it when robots.txt forbids it.
     *
     * @param url An in-policy, normalized URL
     * @return false when its origin's robots.txt disallows it
     * @throws SourceException if the origin's robots.txt cannot be read, which ends the crawl, or
     *                         the crawl is interrupted
     */
    private boolean robotsAllowCrawling(String url) throws SourceException {
        try {
            robotsCheck(url, url);
            return true;
        } catch (FetchDisallowedException e) {
            LOG.debug("Leaving {} out of the crawl: {}", url, e.getMessage());
            robots.recordDisallowed(url);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("Crawl interrupted", sourceId, true, e);
        }
    }

    /**
     * Starts the robots.txt cache for a run, or none when this source ignores robots.txt.
     */
    private RobotsPolicy newRobotsRun() {
        return respectRobotsTxt ? new RobotsPolicy(this::loadRobots) : null;
    }

    /**
     * Refuses a request robots.txt does not permit, and says how long to leave before it.
     *
     * @param documentId The document the request is made for, which is what a refusal names
     * @param target     The URL about to be requested: the document, or a hop of its redirect
     * @return The gap to leave before the request
     * @throws FetchDisallowedException  if the origin's robots.txt disallows the target
     * @throws RobotsUnreachableException if the origin's robots.txt cannot be read
     * @throws InterruptedException      if the run is stopped while the file is being read
     */
    private Duration robotsCheck(String documentId, String target)
            throws SourceException, InterruptedException {
        RobotsPolicy run = robots;
        if (run == null) {
            return crawlDelay;
        }
        URI uri = URI.create(target);
        String origin = RobotsPolicy.originOf(uri);
        RobotsRules rules = run.rulesFor(origin);
        if (!rules.isReachable()) {
            throw new RobotsUnreachableException(rules.unreachableReason()
                + "; RFC 9309 has a crawler treat a robots.txt it cannot read as disallowing "
                + "everything, so nothing on " + origin + " is fetched this run", sourceId);
        }
        if (!rules.allows(RobotsPolicy.pathAndQuery(uri))) {
            throw new FetchDisallowedException(documentId, sourceId,
                "the robots.txt of " + origin + " disallows " + target);
        }
        return rules.crawlDelay().compareTo(crawlDelay) > 0 ? rules.crawlDelay() : crawlDelay;
    }

    /**
     * Reads an origin's robots.txt, following redirects under the same host policy as a page.
     *
     * <p>Never throws over what the origin did: every outcome is a set of rules, as
     * {@link RobotsRules#fromResponse} classifies it. A redirect off the allowed hosts is not
     * followed - the file is then unreadable rather than absent, because the site did publish
     * one - and a chain longer than {@code MAX_REDIRECTS} is read as no file, as RFC 9309 section
     * 2.3.1.2 permits.</p>
     *
     * @param origin {@code scheme://host:port}
     * @return The rules that apply to this crawler on that origin
     * @throws InterruptedException if the run is stopped meanwhile
     */
    private RobotsRules loadRobots(String origin) throws InterruptedException {
        String robotsUrl = normalizeUrl(origin + ROBOTS_PATH);
        String target = robotsUrl;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!isRequestable(target)) {
                return RobotsRules.unreachable("robots.txt at " + robotsUrl + " could not be read: "
                    + target + " is outside the source policy");
            }
            pace(crawlDelay);
            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(request(target, ROBOTS_ACCEPT).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                return RobotsRules.unreachable("robots.txt at " + robotsUrl
                    + " could not be fetched: " + describe(e));
            }
            int status = response.statusCode();
            if (isRedirect(status)) {
                closeQuietly(response.body());
                String location = getHeader(response, "Location", null);
                String next = location == null ? null : resolveUrl(target, location.trim());
                if (next == null) {
                    return RobotsRules.unreachable("robots.txt at " + robotsUrl
                        + " answered a redirect with no usable Location");
                }
                target = normalizeUrl(next);
                continue;
            }
            return robotsFromResponse(response, robotsUrl, target);
        }
        LOG.info("robots.txt at {} redirects more than {} times; reading it as absent, as RFC "
            + "9309 permits, so nothing on {} is disallowed", robotsUrl, MAX_REDIRECTS, origin);
        return RobotsRules.ALLOW_ALL;
    }

    /** Reads the final answer to a robots.txt request, bounded at {@link RobotsRules#MAX_BYTES}. */
    private RobotsRules robotsFromResponse(HttpResponse<InputStream> response, String robotsUrl,
                                           String target) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            closeQuietly(response.body());
            return RobotsRules.fromResponse(status, new byte[0], userAgent, robotsUrl);
        }
        try {
            // One byte past the cap tells a file that fits from one that has to be cut.
            byte[] body = readUpTo(response, target, RobotsRules.MAX_BYTES + 1);
            return RobotsRules.fromResponse(status, body, userAgent, robotsUrl);
        } catch (SourceException | IOException e) {
            return RobotsRules.unreachable("robots.txt at " + robotsUrl
                + " could not be read: " + describe(e));
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
            ? e.getClass().getSimpleName()
            : e.getClass().getSimpleName() + ": " + message;
    }

    /**
     * A robots.txt that could not be read, stopping a request to its origin.
     *
     * <p>Retryable, unlike {@link FetchDisallowedException}: the site has not said no, it has
     * failed to say anything, and the next run asks again.</p>
     */
    private static final class RobotsUnreachableException extends SourceException {

        RobotsUnreachableException(String message, String sourceId) {
            super(message, sourceId, true);
        }
    }

    /**
     * One entry of the crawl frontier: an already-normalized URL and the depth it was found at.
     */
    private record CrawlTarget(String url, int depth) {
    }

    /**
     * Fetches a page and returns the in-policy links it contains.
     *
     * <p>A page that cannot be read for links does not fail the crawl, but it is not taken for a
     * page with none either: everything below it would drop out of the listing, and change
     * detection would read that as a site that deleted it. So what the page answered decides.
     * A page that answered has told the crawl what it links to: HTML that was read, a body that
     * is not HTML, a page that is gone ({@code 404}, {@code 410}), any other {@code 4xx}, a
     * redirect this source will not follow - each links to nothing this source lists, and what
     * only it led to is rightly missing. A page that did not answer for itself - a server error,
     * a {@code 408} or {@code 429}, credentials refused ({@code 401}, {@code 407}) or the page
     * withheld ({@code 403}), no answer at all, a body cut off by the timeout or larger than
     * {@code maxResponseSize} - yields no links and is recorded as a gap, so the listing reports
     * itself incomplete; see {@link #getListingGaps()}. A {@code 403} is a gap here although a
     * fetch reads it as a refusal: the page is kept as it was indexed, and so must be what it
     * linked to, since the refusal says nothing of where those pages went.</p>
     *
     * <p>Only an interruption, a robots.txt that cannot be read and a redirect onto a URL
     * robots.txt disallows stop the walk or leave the page out.</p>
     *
     * @param url  An in-policy, normalized URL
     * @param gaps Told when the page could not be read for links
     * @return The set of in-policy absolute URLs linked from the page
     * @throws FetchDisallowedException if the page redirects onto a URL robots.txt disallows
     * @throws SourceException if the crawl was interrupted, or a robots.txt on the way could not
     *                         be read
     */
    private Set<String> extractLinks(String url, ListingGaps.Recorder gaps)
            throws SourceException {
        HttpResponse<InputStream> response;
        try {
            response = get(url, "text/html,application/xhtml+xml", DocumentValidators.NONE)
                .response();
        } catch (FetchDisallowedException | RobotsUnreachableException e) {
            // Not "this page yielded no links": one is a page the crawl must not list, the other
            // a crawl that can no longer list the whole site.
            throw e;
        } catch (DocumentRefusedException e) {
            // A redirect off the source's policy, without a Location, or past the hop limit: the
            // server answered that the page is somewhere this source does not list.
            LOG.debug("Not following links from {}: {}", url, e.getMessage());
            return Set.of();
        } catch (SourceException e) {
            // Any other failure to get an answer says nothing about the page, and must neither
            // end the walk nor empty what lies below it.
            return unreadLinks(url, gaps, "no answer: " + e.getClass().getSimpleName());
        } catch (IOException e) {
            return unreadLinks(url, gaps, "no answer: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("Crawl interrupted", sourceId, true, e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            closeQuietly(response.body());
            if (answersForItsLinks(status)) {
                return Set.of();
            }
            return unreadLinks(url, gaps, "HTTP " + status);
        }

        String contentType = getHeader(response, "Content-Type", "");
        if (!contentType.contains("text/html") && !contentType.contains("application/xhtml+xml")) {
            closeQuietly(response.body());
            return Set.of();
        }

        byte[] bytes;
        try {
            bytes = readBoundedBody(response, url);
        } catch (DocumentRefusedException e) {
            return unreadLinks(url, gaps, "larger than " + maxResponseSize + " bytes");
        } catch (SourceException e) {
            return unreadLinks(url, gaps, "timed out reading the page");
        } catch (IOException e) {
            return unreadLinks(url, gaps, "page not read: " + e.getClass().getSimpleName());
        }
        // The page's own encoding: a UTF-16 page decoded as UTF-8 is NUL-interleaved text in
        // which no href matches, so the whole subtree below it silently leaves the crawl.
        String body = new String(bytes, detectCharset(response, bytes));

        Set<String> links = new HashSet<>();
        Matcher matcher = HREF_PATTERN.matcher(body);
        while (matcher.find()) {
            String href = matcher.group(1).trim();
            String resolved = resolveUrl(url, href);

            if (resolved != null && isUrlAllowed(resolved)) {
                links.add(resolved);
            }
        }
        return links;
    }

    /**
     * Whether a status other than {@code 2xx} is the server's answer about a page's links.
     *
     * <p>True for a page that is gone and for any other client error the fetch path reads as an
     * answer about the page. False for everything {@link #isTransient} reads as unanswered, for
     * a {@code 403} (see {@link #extractLinks}), and for the {@code 1xx} and {@code 3xx}
     * statuses that reach here - a {@code 304} to a request that asked nothing, a redirect code
     * this source does not follow - none of which carries the page.</p>
     *
     * @param status A status outside {@code 2xx} that is not a followed redirect
     * @return true when the page is rightly taken to link to nothing
     */
    private static boolean answersForItsLinks(int status) {
        return status >= 400 && !isTransient(status) && status != FORBIDDEN;
    }

    /**
     * Records a page whose links could not be read, and yields none for it.
     *
     * @param url    The page
     * @param gaps   The run's gaps
     * @param reason Why, in the fixed terms a status readback may carry
     * @return No links
     */
    private Set<String> unreadLinks(String url, ListingGaps.Recorder gaps, String reason) {
        LOG.warn("Could not read the links of {} ({}); the listing of source {} is incomplete, "
            + "and nothing it misses is removed", url, reason, sourceId);
        gaps.record(url, "links not read: " + reason);
        return Set.of();
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
     * @param gap The gap to leave after this request: {@code crawlDelay}, or an origin's longer
     *            {@code Crawl-delay}
     * @throws InterruptedException if the wait is interrupted, which is how a stopped source
     *                              stops rather than finishing its delay first
     */
    private void pace(Duration gap) throws InterruptedException {
        long delayNanos = gap.toNanos();
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
     * <p>The conditions travel with every hop. A redirect is the server saying the document
     * lives elsewhere, and it is the server at the end of the chain that holds the version the
     * validators describe.</p>
     *
     * <p>robots.txt is consulted per hop as well, before the hop is requested: a disallowed
     * redirect target is refused without being asked for, like a disallowed document.</p>
     *
     * @param url        The in-policy URL to request
     * @param accept     The {@code Accept} header value
     * @param conditions The validators to ask conditionally with; {@link DocumentValidators#NONE}
     *                   for an unconditional request
     * @return The first non-redirect response, and whether the request it answers carried a
     *         condition
     * @throws FetchDisallowedException if robots.txt disallows the URL or a hop of its redirect
     * @throws SourceException if a redirect leaves the policy, the hop limit is exceeded, or a
     *                         robots.txt on the way cannot be read
     */
    private Reply get(String url, String accept, DocumentValidators conditions)
            throws SourceException, IOException, InterruptedException {
        String target = url;

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            Duration gap = robotsCheck(url, target);
            // Per hop, not per call: a redirect is another request to the host, and a chain of
            // them issued back to back is exactly the burst the delay exists to prevent.
            pace(gap);
            HttpRequest.Builder builder = request(target, accept);
            boolean conditional = addConditions(builder, conditions, url);

            HttpResponse<InputStream> response =
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());

            if (!isRedirect(response.statusCode())) {
                return new Reply(response, conditional);
            }

            closeQuietly(response.body());
            target = redirectTarget(target, response);
        }

        throw new DocumentRefusedException(
            "Too many redirects (max " + MAX_REDIRECTS + ") starting at: " + url, sourceId);
    }

    /**
     * Starts a GET carrying what every request from this source carries.
     *
     * @param target The URL to request
     * @param accept The {@code Accept} header value
     * @return The request, ready for conditions to be added
     */
    private HttpRequest.Builder request(String target, String accept) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(target))
            .header("User-Agent", userAgent)
            .header("Accept", accept)
            .timeout(timeout)
            .GET();
        requestHeaders.forEach(builder::header);
        return builder;
    }

    /**
     * Makes a request conditional on the version the validators describe.
     *
     * <p>Both are sent when both are known. RFC 9110 has a server that understands
     * {@code If-None-Match} ignore {@code If-Modified-Since}, so the exact tag decides where the
     * server keeps one, and the timestamp still decides for a server that tracks only
     * modification times. The timestamp is sent in the form the server issued it in, which is
     * the form it compares against.</p>
     *
     * <p>A stored tag that could not travel in a header is left off rather than allowed to fail
     * the request. It came from a registry file on disk, and a request that asks with only the
     * timestamp, or unconditionally, still gets a correct answer - only a costlier one.</p>
     *
     * <p>What was actually sent is reported, not what the validators held: a {@code 304} is
     * an answer about the version a request asked after, and a request whose only condition was
     * left off asked after none.</p>
     *
     * @param builder    The request being built
     * @param conditions The validators to ask with
     * @param url        The document, for the log line a dropped tag leaves
     * @return true when the request now carries at least one condition
     */
    private boolean addConditions(HttpRequest.Builder builder, DocumentValidators conditions,
                                  String url) {
        boolean conditional = false;
        if (conditions.hasEntityTag()) {
            if (isHeaderSafe(conditions.entityTag())) {
                builder.header("If-None-Match", conditions.entityTag());
                conditional = true;
            } else {
                LOG.warn("Not sending the stored entity tag for {}: it holds characters a "
                    + "header cannot carry", url);
            }
        }
        if (conditions.hasLastModified()) {
            builder.header("If-Modified-Since",
                formatHttpDate(Instant.ofEpochMilli(conditions.lastModifiedMillis())));
            conditional = true;
        }
        return conditional;
    }

    /**
     * A response, with whether the request it answers carried a condition.
     *
     * @param response    The first non-redirect response; its body is owned by the caller
     * @param conditional Whether that request carried {@code If-None-Match} or
     *                    {@code If-Modified-Since}
     */
    private record Reply(HttpResponse<InputStream> response, boolean conditional) {
    }

    /** Whether a value can be sent as a header without ending it or being refused. */
    private static boolean isHeaderSafe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c >= 0x7F) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether an error status says the request was not answered rather than what the document is.
     *
     * @param status An HTTP status of 400 or more
     * @return true for a server error, a request timeout, load shedding, or credentials the
     *         server or a proxy refused - none of which is an answer about this page
     */
    private static boolean isTransient(int status) {
        return status >= 500 || status == REQUEST_TIMEOUT || status == TOO_MANY_REQUESTS
            || status == UNAUTHORIZED || status == PROXY_AUTHENTICATION_REQUIRED;
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
            throw new DocumentRefusedException(
                "Redirect " + response.statusCode() + " without Location header: " + from,
                sourceId);
        }

        String next = resolveUrl(from, location);
        if (next == null || !isUrlAllowed(next)) {
            // Worth a warning of its own: this is a crawled host trying to steer the ingester
            // somewhere the source's filters would have rejected, and the caller may well
            // swallow the exception as "this page yielded no links".
            LOG.warn("Source {} refused a redirect from {} to {}: outside the source policy",
                sourceId, from, location);
            throw new DocumentRefusedException(
                "Redirect from " + from + " to " + location + " is outside the source policy",
                sourceId);
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
        // One byte past the bound tells a body at the limit from one over it. The builder holds
        // maxResponseSize to DocumentLimits.MAX_DOCUMENT_BYTES, so the sum fits an int.
        byte[] content = readUpTo(response, url, (int) maxResponseSize + 1);
        if (content.length > maxResponseSize) {
            throw new DocumentRefusedException(
                "Response too large: exceeds " + maxResponseSize + " bytes: " + url,
                sourceId);
        }
        return content;
    }

    /**
     * Reads at most {@code limit} bytes of a response body, under the request timeout.
     *
     * @param response The response whose body should be read; its stream is always closed
     * @param url      The URL being read, for the error message
     * @param limit    The most bytes to read; the rest of the body is abandoned
     * @return The bytes read
     * @throws SourceException if the body stops arriving for longer than the timeout
     * @throws IOException     if reading fails
     */
    private byte[] readUpTo(HttpResponse<InputStream> response, String url, int limit)
            throws SourceException, IOException {
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
            return body.readNBytes(limit);
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
    public FetchedDocument fetch(String documentId) throws SourceException, DocumentNotFoundException {
        // Never null: the request is unconditional, and a 304 to it is an error rather than an
        // answer.
        return fetchConditionally(documentId, DocumentValidators.NONE);
    }

    /**
     * Fetches a document with a conditional GET, answering empty on {@code 304 Not Modified}.
     *
     * <p>See the class documentation. With validators that carry nothing to ask with, this is
     * an ordinary fetch.</p>
     */
    @Override
    public Optional<FetchedDocument> fetchIfChanged(String documentId, DocumentValidators previous)
            throws SourceException, DocumentNotFoundException {
        Objects.requireNonNull(previous, "previous must not be null");
        FetchedDocument fetched = fetchConditionally(documentId, previous);
        if (fetched == null) {
            LOG.debug("Document {} not modified since the indexed version", documentId);
        }
        return Optional.ofNullable(fetched);
    }

    /**
     * The one fetch path, conditional or not.
     *
     * @param documentId The document to fetch
     * @param conditions The validators to ask with; {@link DocumentValidators#NONE} for none
     * @return The document, or null when the server answered {@code 304 Not Modified} to a
     *         request that carried a condition
     * @throws SourceException also when the server answered {@code 304} to a request that
     *         carried none - with no stored tag it could be sent, or no validators at all - since
     *         that names no version this crawl holds and gives it no body to index
     * @throws FetchDisallowedException if robots.txt disallows the document or its redirect,
     *         which is counted towards {@link #getDisallowedCount()}
     */
    private FetchedDocument fetchConditionally(String documentId, DocumentValidators conditions)
            throws SourceException, DocumentNotFoundException {
        ensureOpen();
        Objects.requireNonNull(documentId, "documentId must not be null");
        if (documentId.isEmpty()) {
            throw new IllegalArgumentException("documentId must not be empty");
        }

        if (!isUrlAllowed(documentId)) {
            throw new DocumentRefusedException(
                "Document ID not allowed by source filters: " + documentId,
                sourceId
            );
        }

        try {
            Reply reply = get(documentId, "*/*", conditions);
            HttpResponse<InputStream> response = reply.response();

            if (response.statusCode() == NOT_MODIFIED) {
                closeQuietly(response.body());
                if (!reply.conditional()) {
                    throw new SourceException(
                        "Server answered 304 Not Modified to an unconditional request: "
                            + documentId,
                        sourceId, false);
                }
                return null;
            }

            if (response.statusCode() == NOT_FOUND || response.statusCode() == GONE) {
                closeQuietly(response.body());
                throw new DocumentNotFoundException(documentId, sourceId);
            }

            if (response.statusCode() >= 400) {
                closeQuietly(response.body());
                String failure = "HTTP error " + response.statusCode() + " fetching: " + documentId;
                if (isTransient(response.statusCode())) {
                    throw new SourceException(failure, sourceId, true);
                }
                // Any other client error answers for the document: this is what it is now.
                throw new DocumentRefusedException(failure, sourceId);
            }

            byte[] content = readBoundedBody(response, documentId);

            String contentType = detectContentType(response, documentId);
            requireIncludedContentType(contentType, documentId);
            Charset charset = detectCharset(response, content);
            long lastModifiedMillis = extractLastModified(response);
            String entityTag = getHeader(response, "ETag", null);

            LOG.debug("Fetched document: {} ({} bytes, {}, {})",
                documentId, content.length, contentType, charset);

            return new FetchedDocument(documentId, content, contentType, charset,
                lastModifiedMillis, entityTag);

        } catch (DocumentNotFoundException e) {
            throw e;
        } catch (FetchDisallowedException e) {
            RobotsPolicy run = robots;
            if (run != null) {
                run.recordDisallowed(documentId);
            }
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

    /**
     * Whether this source reads each origin's robots.txt and leaves out what it disallows.
     *
     * @return true unless the source was built with {@code respectRobotsTxt(false)}
     */
    public boolean isRespectingRobotsTxt() {
        return respectRobotsTxt;
    }

    /**
     * The URLs robots.txt kept out of the current run: those the latest crawl did not list or
     * request, and any fetch since refused.
     */
    @Override
    public long getDisallowedCount() {
        RobotsPolicy run = robots;
        return run == null ? 0 : run.disallowedCount();
    }

    /**
     * The pages the latest crawl could not read for links; see {@link #extractLinks} for which
     * answers those are.
     */
    @Override
    public ListingGaps getListingGaps() {
        return listingGaps.snapshot();
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
     * later and repeatedly. Re-resolving before every request is what stops a name repointed
     * since registration, and it is the check that survives whoever else constructs an
     * {@code HttpSource}. What counts as public is {@link PublicAddresses}'s rule, the same one
     * the API applies.
     *
     * <p>A host that cannot be resolved is refused. Failing open here would turn every transient
     * DNS failure into a bypass.
     *
     * <p>It does not pin the connection. The client resolves the name again when it connects, and
     * {@link HttpClient} offers no way to connect to an address already checked while still
     * sending the name as the {@code Host} header and verifying the server's certificate against
     * it. Both lookups go through the JVM's address cache ({@code networkaddress.cache.ttl}, 30
     * seconds by default, whatever TTL the record carries), so they return the same addresses
     * unless the entry expires in the moment between them - a race that whoever controls an
     * allow-listed name's DNS can keep retrying across a long crawl. That residual rebinding
     * window is what an egress firewall on the node closes; a cache lifetime of zero widens it
     * to every request.
     */
    private boolean isAddressAllowed(String host) {
        if (allowPrivateAddresses) {
            return true;
        }
        try {
            for (java.net.InetAddress address : java.net.InetAddress.getAllByName(host)) {
                if (!PublicAddresses.isPublic(address)) {
                    LOG.warn("Refusing {}: it resolves to the non-public address {}", host,
                        address.getHostAddress());
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

    /**
     * Whether a URL is one this source may request at all: an allowed scheme, an allow-listed
     * host, and a host that resolves only to addresses this source may reach.
     *
     * <p>The part of {@link #isUrlAllowed} that is about where a request goes rather than which
     * documents the crawl wants, and so the part a robots.txt request has to pass as well.</p>
     */
    private boolean isOriginAllowed(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            return false;
        }

        String host = uri.getHost();
        if (host == null || !allowedDomains.contains(host.toLowerCase(Locale.ROOT))) {
            return false;
        }

        return isAddressAllowed(host);
    }

    /** {@link #isOriginAllowed} for a URL that may not parse. */
    private boolean isRequestable(String url) {
        try {
            return isOriginAllowed(URI.create(url).normalize());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isUrlAllowed(String url) {
        try {
            URI uri = URI.create(url).normalize();

            if (!isOriginAllowed(uri)) {
                return false;
            }

            String path = uri.getPath();
            if (path == null) {
                path = "/";
            }

            if (!path.startsWith(basePath)) {
                return false;
            }

            return pathRefusal(path) == null;

        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Why the path patterns keep a path out of the crawl, if they do.
     *
     * @param path A decoded URL path under the base path
     * @return The reason, naming the setting that refuses it, or null when the patterns admit it
     */
    private String pathRefusal(String path) {
        for (String excluded : excludedPathPatterns) {
            if (path.contains(excluded)) {
                return "the path '" + path + "' contains '" + excluded + "', one of "
                    + "excluded_path_patterns " + new TreeSet<>(excludedPathPatterns)
                    + " (which are " + DEFAULT_EXCLUDED_PATH_PATTERNS.stream().sorted().toList()
                    + " when a registration gives none)";
            }
        }
        if (!includedPathPatterns.isEmpty()
                && includedPathPatterns.stream().noneMatch(path::contains)) {
            return "the path '" + path + "' contains none of included_path_patterns "
                + new TreeSet<>(includedPathPatterns);
        }
        return null;
    }

    /**
     * Why this crawl's path patterns keep out the page its base URL names, if they do.
     *
     * <p>The walk starts at that page and reaches every other one through its links, so a crawl
     * that may not request it lists nothing and never will, while each run of it succeeds. That is
     * a registration to refuse when it is made - which is why this is asked separately rather than
     * checked when the source is built: a registration already recorded has to come back at
     * startup as it was, to be deregistered or replaced.</p>
     *
     * @return The reason, naming the setting that refuses the page, or empty when it is crawled
     */
    public Optional<String> basePageRefusal() {
        String path;
        try {
            path = URI.create(normalizeUrl(baseUri.toString())).normalize().getPath();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String reason = pathRefusal(path == null || path.isEmpty() ? "/" : path);
        return Optional.ofNullable(reason).map(why -> "base_url is never crawled: " + why);
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
        throw new DocumentRefusedException(
            "Content type not accepted by this source: " + contentType + " for " + documentId,
            sourceId);
    }

    /**
     * The encoding a response's bytes are in.
     *
     * <p>A byte-order mark when the body opens with one, the {@code charset} parameter of
     * {@code Content-Type} when the server sent one the JVM knows, and UTF-8 otherwise - the
     * order {@link TextEncoding#resolve} sets for every source, and the one the WHATWG encoding
     * standard sets for the web. Guessing UTF-8 for a windows-1252 page does not fail: it turns
     * every non-ASCII byte into U+FFFD, so the words the document is indexed under are not the
     * words it contains.</p>
     *
     * @param response The response
     * @param content The body bytes, for the byte-order mark
     * @return The charset, never null
     */
    private Charset detectCharset(HttpResponse<?> response, byte[] content) {
        return TextEncoding.resolve(content, charsetOf(getHeader(response, "Content-Type", null)));
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

    /**
     * Writes a timestamp as an HTTP date, in the IMF-fixdate form RFC 9110 requires a sender to
     * use: a two-digit day and {@code GMT}. The looser RFC 1123 form writes the first nine days
     * of a month with one digit, which a strict server fails to parse and then ignores the
     * condition it was carried in.
     */
    private static String formatHttpDate(Instant instant) {
        return HTTP_DATE.format(instant);
    }

    /** Reads an HTTP date. Lenient where formatting is strict, as RFC 9110 asks a recipient to be. */
    private static Instant parseHttpDate(String date) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)
            .parse(date, Instant::from);
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
        private Set<String> excludedPathPatterns = DEFAULT_EXCLUDED_PATH_PATTERNS;
        private Set<String> includedContentTypes = Set.of();
        private int maxDepth = DEFAULT_MAX_DEPTH;
        private int maxDocuments = DEFAULT_MAX_DOCUMENTS;
        private long maxResponseSize = DEFAULT_MAX_RESPONSE_SIZE;
        private Duration timeout = DEFAULT_TIMEOUT;
        private Duration crawlDelay = DEFAULT_CRAWL_DELAY;
        private String userAgent = DEFAULT_USER_AGENT;
        private Map<String, String> requestHeaders = Map.of();
        private boolean respectRobotsTxt = true;

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

        /**
         * Sets the largest response body a fetch holds; a larger one fails the document.
         *
         * @param maxBytes The bound, 10 MiB by default and at most
         *                 {@link DocumentLimits#MAX_DOCUMENT_BYTES}; {@link #build()} refuses a
         *                 larger one
         * @return This builder
         */
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

        /**
         * Sets whether each origin's robots.txt is read and obeyed.
         *
         * <p>On by default. Turning it off is for a site the operator speaks for - an intranet
         * whose robots.txt keeps public crawlers out, say - and never a way to crawl faster:
         * the crawl delay applies either way.</p>
         *
         * @param respect false to fetch without consulting robots.txt
         * @return This builder
         */
        public Builder respectRobotsTxt(boolean respect) {
            this.respectRobotsTxt = respect;
            return this;
        }

        public HttpSource build() {
            return new HttpSource(this);
        }
    }
}
