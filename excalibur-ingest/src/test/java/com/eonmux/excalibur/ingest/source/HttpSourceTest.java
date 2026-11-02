package com.eonmux.excalibur.ingest.source;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.*;

class HttpSourceTest {

    private HttpServer server;
    private int port;
    private HttpSource source;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        baseUrl = "http://localhost:" + port;
        
        server.createContext("/", new TestHandler());
        server.createContext("/page1.html", new Page1Handler());
        server.createContext("/page2.html", new Page2Handler());
        server.createContext("/missing.html", new NotFoundHandler());
        server.createContext("/status/", new StatusHandler());
        server.createContext("/large.html", new LargeContentHandler());
        server.createContext("/api/data.json", new ApiHandler());
        server.createContext("/paper.pdf", new PdfHandler());
        server.createContext("/redirect-in-policy.html", new RedirectHandler("/page1.html"));
        server.createContext("/redirect-off-host.html", new RedirectHandler(null));
        server.createContext("/redirect-excluded.html", new RedirectHandler("/api/data.json"));
        server.createContext("/echo-headers.html", new EchoHeadersHandler());
        server.createContext("/hub/", new HubHandler());
        server.createContext("/always-not-modified.txt", new AlwaysNotModifiedHandler());
        
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
        
        source = HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
            .sourceId("test-http-source")
            .baseUrl(baseUrl)
            .maxDepth(1)
            .timeoutSeconds(5)
            .build();
    }

    @AfterEach
    void tearDown() {
        if (source != null) {
            source.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Nested
    @DisplayName("Builder Validation")
    class BuilderValidationTest {

        @Test
        @DisplayName("rejects null sourceId")
        void rejectsNullSourceId() {
            assertThatThrownBy(() -> HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId(null)
                .baseUrl("http://example.com")
                .build())
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects empty sourceId")
        void rejectsEmptySourceId() {
            assertThatThrownBy(() -> HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("")
                .baseUrl("http://example.com")
                .build())
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects too long sourceId")
        void rejectsTooLongSourceId() {
            String longId = "x".repeat(257);

            assertThatThrownBy(() -> HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId(longId)
                .baseUrl("http://example.com")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256");
        }

        @Test
        @DisplayName("rejects null baseUrl")
        void rejectsNullBaseUrl() {
            assertThatThrownBy(() -> HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("test")
                .baseUrl((URI) null)
                .build())
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("rejects non-HTTP URL")
        void rejectsNonHttpUrl() {
            assertThatThrownBy(() -> HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("test")
                .baseUrl("ftp://example.com/files")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http or https");
        }

        @Test
        @DisplayName("rejects URL without host")
        void rejectsUrlWithoutHost() {
            assertThatThrownBy(() -> HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("test")
                .baseUrl("http:///path")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
        }

        @Test
        @DisplayName("accepts HTTPS URL")
        void acceptsHttpsUrl() {
            HttpSource httpsSource = HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("test-https")
                .baseUrl("https://example.com/docs")
                .build();
            
            assertThat(httpsSource.getBaseUrl().getScheme()).isEqualTo("https");
            httpsSource.close();
        }

        @Test
        @DisplayName("accepts string baseUrl")
        void acceptsStringBaseUrl() {
            HttpSource stringSource = HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("test-string")
                .baseUrl("http://example.com/docs")
                .build();
            
            assertThat(stringSource.getBaseUrl().getHost()).isEqualTo("example.com");
            stringSource.close();
        }
    }

    @Nested
    @DisplayName("Source Interface")
    class SourceInterfaceTest {

        @Test
        @DisplayName("getSourceId returns configured ID")
        void getSourceId_returnsConfiguredId() {
            assertThat(source.getSourceId()).isEqualTo("test-http-source");
        }

        @Test
        @DisplayName("getSourceType returns http")
        void getSourceType_returnsHttp() {
            assertThat(source.getSourceType()).isEqualTo("http");
        }

        @Test
        @DisplayName("getDescription contains URL")
        void getDescription_containsUrl() {
            assertThat(source.getDescription()).contains("http").contains("localhost");
        }
    }

    @Nested
    @DisplayName("Fetch Operation")
    class FetchTest {

        @Test
        @DisplayName("fetch returns document content")
        void fetch_returnsDocumentContent() throws SourceException, DocumentNotFoundException {
            FetchedDocument doc = source.fetch(baseUrl + "/page1.html");

            assertThat(doc.getDocumentId()).isEqualTo(baseUrl + "/page1.html");
            assertThat(doc.getContent()).isNotEmpty();
            assertThat(doc.getContentType()).contains("text/html");
        }

        @Test
        @DisplayName("fetch returns correct content type for JSON")
        void fetch_returnsCorrectContentType() throws SourceException, DocumentNotFoundException {
            HttpSource jsonSource = HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("json-test")
                .baseUrl(baseUrl)
                .includedContentTypes("application/json")
                .excludedPathPatterns()
                .maxDepth(1)
                .build();
            
            FetchedDocument doc = jsonSource.fetch(baseUrl + "/api/data.json");
            assertThat(doc.getContentType()).contains("application/json");
            
            jsonSource.close();
        }

        @Test
        @DisplayName("fetch accepts a PDF without being told to, as it reads PDFs by default")
        void fetch_acceptsPdfByDefault() throws SourceException, DocumentNotFoundException {
            FetchedDocument doc = source.fetch(baseUrl + "/paper.pdf");

            assertThat(doc.getContentType()).contains("application/pdf");
            assertThat(PdfSignature.isPdf(doc.contentView())).isTrue();
        }

        @Test
        @DisplayName("fetch throws DocumentNotFoundException for 404")
        void fetch_throwsDocumentNotFoundExceptionFor404() {
            assertThatThrownBy(() -> source.fetch(baseUrl + "/missing.html"))
                .isInstanceOf(DocumentNotFoundException.class)
                .hasMessageContaining("missing.html");
        }

        @ParameterizedTest(name = "{0} is reported as {1}")
        @CsvSource({
            "410, gone",
            "403, refused",
            "400, refused",
            "401, unanswered",
            "407, unanswered",
            "408, unanswered",
            "429, unanswered",
            "503, unanswered"
        })
        @DisplayName("an error status is reported as what it says about the page")
        void fetch_classifiesErrorStatuses(int status, String meaning) {
            Throwable thrown = catchThrowable(() -> source.fetch(baseUrl + "/status/" + status));

            switch (meaning) {
                case "gone" -> assertThat(thrown).isInstanceOf(DocumentNotFoundException.class);
                case "refused" -> assertThat(thrown).isInstanceOf(DocumentRefusedException.class);
                default -> assertThat(thrown).isExactlyInstanceOf(SourceException.class)
                    .satisfies(e -> assertThat(((SourceException) e).isRetryable()).isTrue());
            }
        }

        @Test
        @DisplayName("fetch throws SourceException for URL outside allowed domain")
        void fetch_throwsForDisallowedUrl() {
            assertThatThrownBy(() -> source.fetch("http://other-domain.com/page.html"))
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("not allowed");
        }

        @Test
        @DisplayName("fetch null documentId throws exception")
        void fetch_nullDocumentId_throwsException() {
            assertThatThrownBy(() -> source.fetch(null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("fetch empty documentId throws exception")
        void fetch_emptyDocumentId_throwsException() {
            assertThatThrownBy(() -> source.fetch(""))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("fetch respects max response size")
        void fetch_respectsMaxResponseSize() {
            source.close();
            source = HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("test-small-max")
                .baseUrl(baseUrl)
                .maxResponseSize(100)
                .maxDepth(1)
                .timeoutSeconds(5)
                .build();

            assertThatThrownBy(() -> source.fetch(baseUrl + "/large.html"))
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("too large");
        }
    }

    @Nested
    @DisplayName("Redirect Policy")
    class RedirectPolicyTest {

        @Test
        @DisplayName("follows a redirect that stays inside the source policy")
        void followsInPolicyRedirect() throws SourceException, DocumentNotFoundException {
            FetchedDocument doc = source.fetch(baseUrl + "/redirect-in-policy.html");

            assertThat(new String(doc.getContent())).contains("Page 1");
        }

        @Test
        @DisplayName("refuses a redirect to a host outside allowedDomains")
        void refusesOffHostRedirect() {
            assertThatThrownBy(() -> source.fetch(baseUrl + "/redirect-off-host.html"))
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("outside the source policy");
        }

        @Test
        @DisplayName("refuses a redirect into an excluded path")
        void refusesRedirectIntoExcludedPath() {
            source.close();
            source = HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("test-redirect-excluded")
                .baseUrl(baseUrl)
                .excludedPathPatterns("/api")
                .maxDepth(1)
                .timeoutSeconds(5)
                .build();

            assertThatThrownBy(() -> source.fetch(baseUrl + "/redirect-excluded.html"))
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("outside the source policy");
        }
    }

    @Nested
    @DisplayName("Crawl Operation")
    class CrawlTest {

        @Test
        @DisplayName("crawl discovers linked pages")
        void crawl_discoversLinkedPages() throws SourceException {
            source.close();
            source = HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("test-crawl")
                .baseUrl(baseUrl)
                .maxDepth(2)
                .timeoutSeconds(5)
                .excludedPathPatterns("/api")
                .build();

            var docs = source.crawl().toList();

            assertThat(docs).isNotEmpty();
        }

        @Test
        @DisplayName("a depth of zero lists the one page named by the base URL")
        void crawl_depthZeroListsOnlyTheBasePage() throws SourceException {
            source.close();
            source = HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("test-depth")
                .baseUrl(baseUrl)
                .maxDepth(0)
                .timeoutSeconds(5)
                .excludedPathPatterns()
                .build();

            var docs = source.crawl().toList();

            assertThat(docs).containsExactly(baseUrl + "/");
        }

        @Test
        @DisplayName("lists the base page beside the pages it links to")
        void crawl_listsTheBasePage() throws SourceException {
            source.close();
            source = HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("test-depth-one")
                .baseUrl(baseUrl)
                .maxDepth(1)
                .timeoutSeconds(5)
                .excludedPathPatterns()
                .build();

            var docs = source.crawl().toList();

            assertThat(docs).containsExactlyInAnyOrder(
                baseUrl + "/", baseUrl + "/page1.html", baseUrl + "/page2.html");
        }
    }

    @Nested
    @DisplayName("Base page refusal")
    class BasePageRefusalTest {

        private HttpSource over(String base, Set<String> included, Set<String> excluded) {
            return HttpSource.builder()
                .allowPrivateAddresses(true)
                .sourceId("refusal")
                .baseUrl(base)
                .includedPathPatterns(included)
                .excludedPathPatterns(excluded)
                .build();
        }

        @Test
        @DisplayName("names nothing when the patterns admit the base page")
        void admitted() {
            try (HttpSource admitted = over(baseUrl + "/docs/", Set.of("/docs"), Set.of("/api"))) {
                assertThat(admitted.basePageRefusal()).isEmpty();
            }
        }

        @Test
        @DisplayName("names the exclusion that keeps the base page out")
        void excluded() {
            try (HttpSource excluded = over(baseUrl + "/api/v2/", Set.of(), Set.of("/api"))) {
                assertThat(excluded.basePageRefusal()).get().asString()
                    .contains("base_url").contains("'/api'").contains("excluded_path_patterns");
            }
        }

        @Test
        @DisplayName("says when no inclusion admits the base page")
        void notIncluded() {
            try (HttpSource notIncluded = over(baseUrl + "/docs/", Set.of("/guide"), Set.of())) {
                assertThat(notIncluded.basePageRefusal()).get().asString()
                    .contains("included_path_patterns").contains("/guide");
            }
        }
    }

    @Nested
    @DisplayName("Crawl Budget")
    class CrawlBudgetTest {

        @Test
        @DisplayName("stops the walk once maxDocuments is reached")
        void respectsMaxDocuments() throws SourceException {
            source.close();
            source = HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("test-budget")
                .baseUrl(baseUrl + "/hub/")
                .maxDepth(2)
                .maxDocuments(3)
                .timeoutSeconds(5)
                .excludedPathPatterns()
                .build();

            var docs = source.crawl().toList();

            assertThat(docs).hasSize(3);
            // What the budget left unread may still be there: not a listing to delete by
            assertThat(source.getListingGaps().isComplete()).isFalse();
            assertThat(source.getListingGaps().describe()).contains("budget");
        }

        @Test
        @DisplayName("yields every reachable document when the budget is generous")
        void yieldsAllWithinBudget() throws SourceException {
            source.close();
            source = HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("test-budget-large")
                .baseUrl(baseUrl + "/hub/")
                .maxDepth(2)
                .maxDocuments(1000)
                .timeoutSeconds(5)
                .excludedPathPatterns()
                .build();

            var docs = source.crawl().toList();

            // Every leaf, and the hub the walk started from
            assertThat(docs).hasSize(HubHandler.LEAF_COUNT + 1).contains(baseUrl + "/hub/");
            assertThat(source.getListingGaps().isComplete()).isTrue();
        }
    }

    @Nested
    @DisplayName("URL Filtering")
    class UrlFilteringTest {

        @Test
        @DisplayName("excludes paths matching excluded patterns")
        void excludesMatchingPaths() throws SourceException {
            source.close();
            source = HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("test-exclude")
                .baseUrl(baseUrl)
                .maxDepth(1)
                .timeoutSeconds(5)
                .excludedPathPatterns("/api", "/admin")
                .build();

            assertThatThrownBy(() -> source.fetch(baseUrl + "/api/data.json"))
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("not allowed");
        }

        @Test
        @DisplayName("includes only paths matching included patterns when specified")
        void includesOnlyMatchingPaths() throws SourceException {
            source.close();
            source = HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("test-include")
                .baseUrl(baseUrl)
                .maxDepth(1)
                .timeoutSeconds(5)
                .includedPathPatterns("/page")
                .excludedPathPatterns()
                .build();

            FetchedDocument doc = source.fetch(baseUrl + "/page1.html");
            assertThat(doc).isNotNull();

            assertThatThrownBy(() -> source.fetch(baseUrl + "/other.html"))
                .isInstanceOf(SourceException.class);
        }

        @Test
        @DisplayName("allows multiple domains when configured")
        void allowsMultipleDomains() {
            HttpSource multiSource = HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("multi-domain")
                .baseUrl(baseUrl)
                .allowedDomains("localhost", "example.com")
                .build();

            assertThat(multiSource.getAllowedDomains())
                .contains("localhost", "example.com");

            multiSource.close();
        }
    }

    @Nested
    @DisplayName("Closed Source")
    class ClosedSourceTest {

        @Test
        @DisplayName("crawl on closed source throws exception")
        void crawl_onClosed_throwsException() {
            source.close();

            assertThatThrownBy(() -> source.crawl())
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("closed");
        }

        @Test
        @DisplayName("fetch on closed source throws exception")
        void fetch_onClosed_throwsException() {
            source.close();

            assertThatThrownBy(() -> source.fetch(baseUrl + "/page1.html"))
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("closed");
        }
    }

    @Nested
    @DisplayName("Configuration Accessors")
    class ConfigurationAccessorsTest {

        @Test
        @DisplayName("returns configured max depth")
        void returnsMaxDepth() {
            assertThat(source.getMaxDepth()).isEqualTo(1);
        }

        @Test
        @DisplayName("returns configured max response size")
        void returnsMaxResponseSize() {
            assertThat(source.getMaxResponseSize()).isPositive();
        }

        @Test
        @DisplayName("returns configured timeout")
        void returnsTimeout() {
            assertThat(source.getTimeout()).isNotNull();
        }

        @Test
        @DisplayName("returns configured user agent")
        void returnsUserAgent() {
            assertThat(source.getUserAgent()).isNotEmpty();
        }

        @Test
        @DisplayName("returns configured base URL")
        void returnsBaseUrl() {
            assertThat(source.getBaseUrl()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Default Methods")
    class DefaultMethodsTest {

        @Test
        @DisplayName("getDocumentCount returns -1")
        void getDocumentCount_returnsMinusOne() throws SourceException {
            assertThat(source.getDocumentCount()).isEqualTo(-1);
        }

        @Test
        @DisplayName("getEstimatedTotalSize returns -1")
        void getEstimatedTotalSize_returnsMinusOne() throws SourceException {
            assertThat(source.getEstimatedTotalSize()).isEqualTo(-1);
        }
    }

    /**
     * A {@code 304} means "the version you asked about is current", so it can only be taken as
     * that when a version was asked about. Answered to a request that carried no condition, it
     * names no version at all, and treating it as "unchanged" would keep whatever the index holds
     * on the word of a server that was never asked.
     */
    @Nested
    @DisplayName("Conditional Fetch")
    class ConditionalFetchTest {

        private String url() {
            return baseUrl + "/always-not-modified.txt";
        }

        @Test
        @DisplayName("a 304 to a request with a condition is the indexed version still current")
        void aSolicitedNotModifiedIsUnchanged() throws Exception {
            assertThat(source.fetchIfChanged(url(), new DocumentValidators(0L, "\"v1\"")))
                .isEmpty();
            assertThat(source.fetchIfChanged(url(), new DocumentValidators(1_700_000_000_000L, null)))
                .isEmpty();
        }

        @Test
        @DisplayName("a 304 to a request with nothing to ask with is an error, not unchanged")
        void anUnsolicitedNotModifiedFails() {
            assertThatThrownBy(() -> source.fetchIfChanged(url(), DocumentValidators.NONE))
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("unconditional");
        }

        @Test
        @DisplayName("a 304 after the only condition was left off is an error, not unchanged")
        void aNotModifiedToADroppedTagFails() {
            // A stored tag a header cannot carry is not sent, and with no timestamp beside it the
            // request goes out unconditionally.
            DocumentValidators unsendable = new DocumentValidators(0L, "\"v\u00e9\"");

            assertThatThrownBy(() -> source.fetchIfChanged(url(), unsendable))
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("unconditional");
        }

        @Test
        @DisplayName("an ordinary fetch answered 304 is an error")
        void anOrdinaryFetchAnsweredNotModifiedFails() {
            assertThatThrownBy(() -> source.fetch(url()))
                .isInstanceOf(SourceException.class)
                .hasMessageContaining("unconditional");
        }
    }

    @Nested
    @DisplayName("Request Headers")
    class RequestHeadersTest {

        @Test
        @DisplayName("accepts custom request headers")
        void acceptsCustomHeaders() {
            HttpSource customSource = HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("custom-headers")
                .baseUrl(baseUrl)
                .addRequestHeader("X-Custom-Header", "custom-value")
                .addRequestHeader("Authorization", "Bearer token123")
                .build();

            assertThat(customSource).isNotNull();
            customSource.close();
        }

        @Test
        @DisplayName("sends configured headers on fetch")
        void sendsCustomHeadersOnFetch() throws SourceException, DocumentNotFoundException {
            HttpSource customSource = HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("custom-headers-fetch")
                .baseUrl(baseUrl)
                .addRequestHeader("X-Custom-Header", "custom-value")
                .timeoutSeconds(5)
                .build();

            FetchedDocument doc = customSource.fetch(baseUrl + "/echo-headers.html");

            // com.sun.net.httpserver normalizes header names, so compare case-insensitively.
            assertThat(new String(doc.getContent()))
                .containsIgnoringCase("X-Custom-Header=custom-value");
            customSource.close();
        }

        @Test
        @DisplayName("crawls a site that requires a configured header")
        void crawlsWithCustomHeaders() throws SourceException {
            HttpSource customSource = HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("custom-headers-crawl")
                .baseUrl(baseUrl)
                .addRequestHeader("Authorization", "Bearer token123")
                .maxDepth(2)
                .timeoutSeconds(5)
                .excludedPathPatterns("/api")
                .build();

            // The old implementation mutated the built request's immutable header map here and
            // aborted the whole crawl with UnsupportedOperationException.
            var docs = customSource.crawl().toList();

            assertThat(docs).isNotEmpty();
            customSource.close();
        }

        @Test
        @DisplayName("rejects a header the HTTP client will not send")
        void rejectsRestrictedHeader() {
            assertThatThrownBy(() -> HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("restricted-header")
                .baseUrl(baseUrl)
                .addRequestHeader("Host", "evil.example")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Host");
        }

        @Test
        @DisplayName("accepts custom user agent")
        void acceptsCustomUserAgent() {
            HttpSource customSource = HttpSource.builder()
            .allowPrivateAddresses(true)  // crawls a local mock server
                .sourceId("custom-ua")
                .baseUrl(baseUrl)
                .userAgent("CustomBot/2.0")
                .build();

            assertThat(customSource.getUserAgent()).isEqualTo("CustomBot/2.0");
            customSource.close();
        }
    }

    @Nested
    @DisplayName("Pacing")
    class PacingTest {

        /** Long enough that the gap dominates a loopback round trip, short enough to test. */
        private static final long DELAY_MS = 300;

        @Test
        @DisplayName("consecutive requests are separated by at least the crawl delay")
        void requestsArePaced() throws Exception {
            // A lower bound only. The wait cannot make a request leave early, so this asserts
            // something the clock can only confirm; an upper bound would be asserting that the
            // machine is not busy.
            try (HttpSource paced = HttpSource.builder()
                    .allowPrivateAddresses(true)
                    .sourceId("paced")
                    .baseUrl(baseUrl)
                    .crawlDelayMillis(DELAY_MS)
                    .timeoutSeconds(5)
                    .build()) {

                long start = System.nanoTime();
                paced.fetch(baseUrl + "/page1.html");
                paced.fetch(baseUrl + "/page2.html");
                paced.fetch(baseUrl + "/page1.html");
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;

                // Three requests, two gaps between them. The first one waits for nothing.
                assertThat(elapsedMs).isGreaterThanOrEqualTo(2 * DELAY_MS);
            }
        }

        @Test
        @DisplayName("a crawl of several pages is paced too, not only the fetches")
        void theWalkIsPaced() throws Exception {
            try (HttpSource paced = HttpSource.builder()
                    .allowPrivateAddresses(true)
                    .sourceId("paced-crawl")
                    .baseUrl(baseUrl)
                    .maxDepth(2)
                    .crawlDelayMillis(DELAY_MS)
                    .timeoutSeconds(5)
                    .build()) {

                long start = System.nanoTime();
                try (Stream<String> urls = paced.crawl()) {
                    assertThat(urls.count()).isPositive();
                }
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;

                // The walk reads the base page's links and then descends, so it makes more than
                // one request. One gap is the least a multi-request walk can cost; depth 1 alone
                // reads only the base page and would be asserting nothing.
                assertThat(elapsedMs).isGreaterThanOrEqualTo(DELAY_MS);
            }
        }

        @Test
        @DisplayName("zero paces nothing")
        void zeroPacesNothing() throws Exception {
            try (HttpSource unpaced = HttpSource.builder()
                    .allowPrivateAddresses(true)
                    .sourceId("unpaced")
                    .baseUrl(baseUrl)
                    .crawlDelayMillis(0)
                    .timeoutSeconds(5)
                    .build()) {

                long start = System.nanoTime();
                unpaced.fetch(baseUrl + "/page1.html");
                unpaced.fetch(baseUrl + "/page2.html");
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;

                // Generous: this is asserting that nothing waited 300ms twice, not that loopback
                // is fast.
                assertThat(elapsedMs).isLessThan(2 * DELAY_MS);
            }
        }

        @Test
        @DisplayName("a negative delay is read as none rather than as a wait that never ends")
        void negativeIsNone() throws Exception {
            try (HttpSource unpaced = HttpSource.builder()
                    .allowPrivateAddresses(true)
                    .sourceId("negative")
                    .baseUrl(baseUrl)
                    .crawlDelayMillis(-1000)
                    .timeoutSeconds(5)
                    .build()) {

                long start = System.nanoTime();
                unpaced.fetch(baseUrl + "/page1.html");
                unpaced.fetch(baseUrl + "/page2.html");
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;

                assertThat(elapsedMs).isLessThan(2 * DELAY_MS);
            }
        }
    }

    // Test handlers

    private static class TestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = "<!DOCTYPE html><html><body>" +
                "<a href=\"/page1.html\">Page 1</a>" +
                "<a href=\"/page2.html\">Page 2</a>" +
                "</body></html>";
            
            byte[] response = html.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.getResponseBody().close();
        }
    }

    private static class Page1Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = "<!DOCTYPE html><html><body><h1>Page 1</h1><p>Content of page 1</p></body></html>";
            
            byte[] response = html.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.getResponseBody().close();
        }
    }

    private static class Page2Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = "<!DOCTYPE html><html><body><h1>Page 2</h1><p>Content of page 2</p></body></html>";
            
            byte[] response = html.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.getResponseBody().close();
        }
    }

    private static class NotFoundHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.sendResponseHeaders(404, -1);
            exchange.getResponseBody().close();
        }
    }

    /** Answers {@code /status/<code>} with that status and no body. */
    private static class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            int status = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
            exchange.sendResponseHeaders(status, -1);
            exchange.getResponseBody().close();
        }
    }

    private static class LargeContentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = "<!DOCTYPE html><html><body>" + "x".repeat(10000) + "</body></html>";
            
            byte[] response = html.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.getResponseBody().close();
        }
    }

    /**
     * Answers 301 with a Location under the same host, or - when the target is null - one
     * pointing at a host that is not in allowedDomains.
     */
    private static class RedirectHandler implements HttpHandler {
        private final String targetPath;

        RedirectHandler(String targetPath) {
            this.targetPath = targetPath;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String host = exchange.getLocalAddress().getPort() + "";
            String location = targetPath != null
                ? "http://localhost:" + host + targetPath
                : "http://127.0.0.1:" + host + "/page1.html";

            exchange.getResponseHeaders().set("Location", location);
            exchange.sendResponseHeaders(301, -1);
            exchange.getResponseBody().close();
        }
    }

    /**
     * Echoes the request headers back as the body, so a test can prove they reached the wire.
     */
    private static class EchoHeadersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            StringBuilder body = new StringBuilder();
            exchange.getRequestHeaders().forEach((name, values) ->
                values.forEach(value -> body.append(name).append('=').append(value).append('\n')));

            byte[] response = body.toString().getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.getResponseBody().close();
        }
    }

    /**
     * Serves the whole {@code /hub/} subtree: the hub page links to {@link #LEAF_COUNT} leaves
     * under the same base path, so a crawl has more to find than a small budget allows.
     */
    /** A server that answers every request {@code 304 Not Modified}, asked or not. */
    private static class AlwaysNotModifiedHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
        }
    }

    private static class HubHandler implements HttpHandler {
        static final int LEAF_COUNT = 12;

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String html = path.endsWith("/hub/") ? hubPage()
                : "<!DOCTYPE html><html><body>leaf</body></html>";

            byte[] response = html.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.getResponseBody().close();
        }

        private static String hubPage() {
            StringBuilder html = new StringBuilder("<!DOCTYPE html><html><body>");
            for (int i = 0; i < LEAF_COUNT; i++) {
                html.append("<a href=\"/hub/leaf-").append(i).append(".html\">leaf ").append(i).append("</a>");
            }
            return html.append("</body></html>").toString();
        }
    }

    private static class PdfHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] response = "%PDF-1.4\n%%EOF\n"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            exchange.getResponseHeaders().set("Content-Type", "application/pdf");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.getResponseBody().close();
        }
    }

    private static class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String json = "{\"status\": \"ok\", \"data\": []}";
            
            byte[] response = json.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.getResponseBody().close();
        }
    }
}
