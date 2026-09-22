package com.eonmux.excalibur.api.security;

import com.eonmux.excalibur.api.protocol.ResponseBody;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Single entry point for authentication and rate limiting on the HTTP surface.
 *
 * <p>Every endpoint is registered through {@link #protect(Access, HttpHandler)},
 * so the decision about who may call what lives in one place instead of being
 * scattered through the handlers.</p>
 *
 * <h2>Order of checks</h2>
 * <ol>
 *   <li>Rate limit, keyed by remote address. Charging before authentication means a
 *       flood of unauthenticated requests - including token guessing - is bounded.</li>
 *   <li>Bearer token, compared with {@link java.security.MessageDigest#isEqual} so the
 *       comparison does not leak the token through timing.</li>
 * </ol>
 *
 * <h2>Error responses</h2>
 * <p>Refusals carry no detail beyond the reason class: a rejected request never
 * reveals whether a token was configured, which endpoint state was consulted, or
 * anything about the server's filesystem or network.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public final class RequestGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestGuard.class);

    private static final String AUTHORIZATION_HEADER = "Authorization";

    /**
     * The forwarding header read from a trusted proxy.
     *
     * <p>Only this one. RFC 7239's {@code Forwarded} says the same thing in a richer grammar,
     * and a second parser over the same attacker-supplied text is more ways to disagree with
     * the proxy in front, not more capability.</p>
     */
    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    private static final String BEARER_PREFIX = "bearer ";

    private final ApiSecurityConfig config;
    private final ClientRateLimiter rateLimiter;

    /**
     * Creates a guard for the given policy.
     *
     * @param config The security policy
     */
    public RequestGuard(ApiSecurityConfig config) {
        this(config, new ClientRateLimiter(config));
    }

    /**
     * Creates a guard with an explicit rate limiter.
     *
     * @param config      The security policy
     * @param rateLimiter The limiter to charge requests against
     */
    public RequestGuard(ApiSecurityConfig config, ClientRateLimiter rateLimiter) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter cannot be null");
    }

    /**
     * Access class of an endpoint.
     */
    public enum Access {
        /** Unauthenticated liveness probe; still rate limited against the read budget. */
        PUBLIC(false, false),
        /** Authenticated read; charged against the read budget. */
        READ(true, false),
        /** Authenticated mutation; charged against the smaller mutate budget. */
        MUTATE(true, true);

        private final boolean authenticated;
        private final boolean mutating;

        Access(boolean authenticated, boolean mutating) {
            this.authenticated = authenticated;
            this.mutating = mutating;
        }

        boolean isAuthenticated() {
            return authenticated;
        }

        boolean isMutating() {
            return mutating;
        }
    }

    /**
     * Wraps a handler so it is only reached by requests that pass the policy.
     *
     * @param access   The access class of the endpoint
     * @param delegate The endpoint handler
     * @return A handler that enforces the policy before delegating
     */
    public HttpHandler protect(Access access, HttpHandler delegate) {
        Objects.requireNonNull(access, "access cannot be null");
        Objects.requireNonNull(delegate, "delegate cannot be null");
        return exchange -> {
            if (!allow(exchange, access)) {
                return;
            }
            delegate.handle(exchange);
        };
    }

    /**
     * Applies the policy to one exchange, writing the refusal response itself.
     *
     * @param exchange The exchange
     * @param access   The access class of the endpoint
     * @return true when the request may proceed
     * @throws IOException if the refusal response cannot be written
     */
    public boolean allow(HttpExchange exchange, Access access) throws IOException {
        String clientKey = clientKey(exchange, config.trustedProxies());
        ClientRateLimiter.Decision decision = rateLimiter.check(clientKey, access.isMutating());
        if (!decision.allowed()) {
            LOGGER.warn("Rate limit exceeded for {} on {}", clientKey, exchange.getRequestURI().getPath());
            exchange.getResponseHeaders().set("Retry-After", Long.toString(decision.retryAfterSeconds()));
            sendJson(exchange, 429, "Rate limit exceeded");
            return false;
        }

        if (access.isAuthenticated() && config.isAuthenticationRequired() && !isAuthorized(exchange)) {
            LOGGER.warn("Rejected unauthenticated request from {} to {}",
                clientKey, exchange.getRequestURI().getPath());
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            sendJson(exchange, 401, "Authentication failed");
            return false;
        }
        return true;
    }

    /**
     * Returns the rate limiter this guard charges against.
     *
     * @return The rate limiter
     */
    public ClientRateLimiter rateLimiter() {
        return rateLimiter;
    }

    /**
     * Returns the policy this guard enforces.
     *
     * @return The security policy
     */
    public ApiSecurityConfig config() {
        return config;
    }

    private boolean isAuthorized(HttpExchange exchange) {
        List<String> headers = exchange.getRequestHeaders().get(AUTHORIZATION_HEADER);
        if (headers == null || headers.isEmpty()) {
            return false;
        }
        for (String header : headers) {
            if (header == null || header.length() <= BEARER_PREFIX.length()) {
                continue;
            }
            if (!header.substring(0, BEARER_PREFIX.length()).toLowerCase(Locale.ROOT).equals(BEARER_PREFIX)) {
                continue;
            }
            String token = header.substring(BEARER_PREFIX.length()).trim();
            if (config.matchesToken(token.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Derives the rate-limit key for an exchange.
     *
     * <p>The peer address, except when the peer is a configured proxy. A forwarding header is
     * attacker-controlled and believing one from an arbitrary peer would let a client meter
     * itself as a thousand clients, or spend somebody else's budget; with no proxy configured
     * none is read. Behind one, the peer is the same address for every caller and metering it
     * would put every client in one bucket, so the header is read - from the right, stopping at
     * the first hop this deployment has not vouched for.</p>
     *
     * @param exchange The exchange
     * @param trustedProxies The hops whose forwarding header may be believed
     * @return A stable key for the calling client
     */
    static String clientKey(HttpExchange exchange, TrustedProxies trustedProxies) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        InetAddress peer = remote == null ? null : remote.getAddress();
        if (peer == null || trustedProxies.isEmpty() || !trustedProxies.trusts(peer)) {
            return ClientAddressKey.of(peer);
        }
        List<String> forwarded = exchange.getRequestHeaders().get(FORWARDED_FOR_HEADER);
        byte[] origin = trustedProxies.originBehind(
            forwarded == null ? List.of() : forwarded, peer.getAddress());
        return ClientAddressKey.ofBytes(origin);
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] body = ("{\"error\": \"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        ResponseBody.send(exchange, statusCode, body);
    }
}
