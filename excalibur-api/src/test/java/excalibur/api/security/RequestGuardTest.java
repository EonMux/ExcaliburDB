package excalibur.api.security;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the authentication and rate-limiting decorator.
 */
@DisplayName("RequestGuard")
class RequestGuardTest {

    private static final String TOKEN = "correct-horse-battery-staple";

    @Nested
    @DisplayName("Authentication")
    class Authentication {

        @Test
        @DisplayName("rejects an authenticated endpoint with no Authorization header")
        void rejectsMissingToken() throws IOException {
            RequestGuard guard = new RequestGuard(withToken());
            AtomicInteger calls = new AtomicInteger();
            HttpExchange exchange = exchange(null);

            guard.protect(RequestGuard.Access.READ, countingHandler(calls)).handle(exchange);

            assertThat(calls).hasValue(0);
            verify(exchange).sendResponseHeaders(eq(401), anyLong());
            assertThat(exchange.getResponseHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
        }

        @Test
        @DisplayName("rejects a wrong token")
        void rejectsWrongToken() throws IOException {
            RequestGuard guard = new RequestGuard(withToken());
            AtomicInteger calls = new AtomicInteger();
            HttpExchange exchange = exchange("Bearer wrong");

            guard.protect(RequestGuard.Access.READ, countingHandler(calls)).handle(exchange);

            assertThat(calls).hasValue(0);
            verify(exchange).sendResponseHeaders(eq(401), anyLong());
        }

        @Test
        @DisplayName("accepts the configured token, scheme case-insensitively")
        void acceptsToken() throws IOException {
            RequestGuard guard = new RequestGuard(withToken());
            AtomicInteger calls = new AtomicInteger();

            guard.protect(RequestGuard.Access.READ, countingHandler(calls))
                .handle(exchange("Bearer " + TOKEN));
            guard.protect(RequestGuard.Access.MUTATE, countingHandler(calls))
                .handle(exchange("bearer " + TOKEN));

            assertThat(calls).hasValue(2);
        }

        @Test
        @DisplayName("leaves the liveness probe unauthenticated")
        void publicEndpointNeedsNoToken() throws IOException {
            RequestGuard guard = new RequestGuard(withToken());
            AtomicInteger calls = new AtomicInteger();

            guard.protect(RequestGuard.Access.PUBLIC, countingHandler(calls)).handle(exchange(null));

            assertThat(calls).hasValue(1);
        }

        @Test
        @DisplayName("does not require a token when none is configured")
        void noTokenConfigured() throws IOException {
            RequestGuard guard = new RequestGuard(ApiSecurityConfig.defaults());
            AtomicInteger calls = new AtomicInteger();

            guard.protect(RequestGuard.Access.MUTATE, countingHandler(calls)).handle(exchange(null));

            assertThat(calls).hasValue(1);
        }

        @Test
        @DisplayName("says only that authentication failed")
        void refusalLeaksNothing() throws IOException {
            RequestGuard guard = new RequestGuard(withToken());
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HttpExchange exchange = exchange("Bearer wrong", body);

            guard.protect(RequestGuard.Access.READ, countingHandler(new AtomicInteger())).handle(exchange);

            String response = body.toString(StandardCharsets.UTF_8);
            assertThat(response).isEqualTo("{\"error\": \"Authentication failed\"}");
        }
    }

    @Nested
    @DisplayName("Rate limiting")
    class RateLimiting {

        @Test
        @DisplayName("refuses with 429 and Retry-After once the budget is spent")
        void refusesOverBudget() throws IOException {
            ApiSecurityConfig config = ApiSecurityConfig.builder()
                .mutateBudget(1.0, 2.0)
                .build();
            RequestGuard guard = new RequestGuard(config);
            AtomicInteger calls = new AtomicInteger();
            HttpHandler handler = guard.protect(RequestGuard.Access.MUTATE, countingHandler(calls));

            handler.handle(exchange(null));
            handler.handle(exchange(null));
            HttpExchange refused = exchange(null);
            handler.handle(refused);

            assertThat(calls).hasValue(2);
            verify(refused).sendResponseHeaders(eq(429), anyLong());
            assertThat(refused.getResponseHeaders().getFirst("Retry-After")).isNotNull();
        }

        @Test
        @DisplayName("meters reads and mutations separately")
        void separateBudgets() throws IOException {
            ApiSecurityConfig config = ApiSecurityConfig.builder()
                .mutateBudget(1.0, 1.0)
                .readBudget(50.0, 50.0)
                .build();
            RequestGuard guard = new RequestGuard(config);
            AtomicInteger mutations = new AtomicInteger();
            AtomicInteger reads = new AtomicInteger();

            HttpHandler mutate = guard.protect(RequestGuard.Access.MUTATE, countingHandler(mutations));
            mutate.handle(exchange(null));
            mutate.handle(exchange(null));

            HttpHandler read = guard.protect(RequestGuard.Access.READ, countingHandler(reads));
            for (int i = 0; i < 10; i++) {
                read.handle(exchange(null));
            }

            assertThat(mutations).hasValue(1);
            assertThat(reads).hasValue(10);
        }

        @Test
        @DisplayName("charges the limiter before checking the token")
        void limitsUnauthenticatedFloods() throws IOException {
            ApiSecurityConfig config = ApiSecurityConfig.builder()
                .authToken(TOKEN)
                .readBudget(1.0, 2.0)
                .build();
            RequestGuard guard = new RequestGuard(config);
            HttpHandler handler = guard.protect(RequestGuard.Access.READ, countingHandler(new AtomicInteger()));

            handler.handle(exchange("Bearer wrong"));
            handler.handle(exchange("Bearer wrong"));
            HttpExchange third = exchange("Bearer " + TOKEN);
            handler.handle(third);

            // Token guessing is bounded: the third request never reaches the token check.
            verify(third).sendResponseHeaders(eq(429), anyLong());
        }
    }

    @Nested
    @DisplayName("The rate-limit key")
    class RateLimitKey {

        @Test
        @DisplayName("charges every address in one IPv6 prefix against a single budget")
        void aPrefixCannotMintFreshBudgets() throws IOException {
            // The holder of a /64 - the smallest routine residential allocation - can source
            // 2^64 addresses. Keyed on the full address, every request would arrive with a
            // full bucket and the limiter would refuse nothing.
            ApiSecurityConfig config = ApiSecurityConfig.builder()
                .readBudget(1.0, 2.0)
                .build();
            RequestGuard guard = new RequestGuard(config);
            AtomicInteger calls = new AtomicInteger();
            HttpHandler handler = guard.protect(RequestGuard.Access.READ, countingHandler(calls));

            handler.handle(exchangeFrom("2001:db8:1:2::1"));
            handler.handle(exchangeFrom("2001:db8:1:2::2"));
            HttpExchange third = exchangeFrom("2001:db8:1:2::3");
            handler.handle(third);

            assertThat(calls).hasValue(2);
            verify(third).sendResponseHeaders(eq(429), anyLong());
        }

        @Test
        @DisplayName("leaves a separate prefix its own budget")
        void anUnrelatedPrefixIsNotCharged() throws IOException {
            ApiSecurityConfig config = ApiSecurityConfig.builder()
                .readBudget(1.0, 1.0)
                .build();
            RequestGuard guard = new RequestGuard(config);
            AtomicInteger calls = new AtomicInteger();
            HttpHandler handler = guard.protect(RequestGuard.Access.READ, countingHandler(calls));

            handler.handle(exchangeFrom("2001:db8:1:2::1"));
            handler.handle(exchangeFrom("2001:db8:1:3::1"));

            assertThat(calls).hasValue(2);
        }
    }

    private static ApiSecurityConfig withToken() {
        return ApiSecurityConfig.builder().authToken(TOKEN).build();
    }

    private static HttpHandler countingHandler(AtomicInteger calls) {
        return exchange -> calls.incrementAndGet();
    }

    private static HttpExchange exchangeFrom(String remoteAddress) {
        HttpExchange exchange = exchange(null);
        when(exchange.getRemoteAddress())
            .thenReturn(new InetSocketAddress(remoteAddress, 51234));
        return exchange;
    }

    private static HttpExchange exchange(String authorization) {
        return exchange(authorization, new ByteArrayOutputStream());
    }

    private static HttpExchange exchange(String authorization, ByteArrayOutputStream body) {
        HttpExchange exchange = mock(HttpExchange.class);
        Headers requestHeaders = new Headers();
        if (authorization != null) {
            requestHeaders.add("Authorization", authorization);
        }
        when(exchange.getRequestHeaders()).thenReturn(requestHeaders);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestURI()).thenReturn(URI.create("/v1/stats"));
        when(exchange.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 51234));
        when(exchange.getResponseBody()).thenReturn(body);
        return exchange;
    }
}
