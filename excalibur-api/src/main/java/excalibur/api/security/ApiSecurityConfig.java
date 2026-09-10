package excalibur.api.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Security policy for the HTTP API surface.
 *
 * <p>Everything the API layer needs in order to decide who may talk to it, how
 * often, and which ingest targets it will touch. The policy is immutable and is
 * resolved once at startup from the properties file and the environment.</p>
 *
 * <h2>Configuration keys</h2>
 * <table border="1">
 *   <caption>API security keys</caption>
 *   <tr><th>Key</th><th>Meaning</th><th>Default</th></tr>
 *   <tr><td>{@code excalibur.api.auth-token}</td>
 *       <td>Bearer token required by every endpoint except the liveness probe.
 *           Supply it as {@code EXCALIBUR_API_AUTH_TOKEN}.</td>
 *       <td>unset - authentication disabled, loopback binding enforced</td></tr>
 *   <tr><td>{@code excalibur.api.bind-address}</td><td>Listen address</td><td>127.0.0.1</td></tr>
 *   <tr><td>{@code excalibur.api.allow-plaintext}</td>
 *       <td>Permits a non-loopback bind without TLS, for a deployment where something in
 *           front of this process terminates TLS.</td>
 *       <td>false - a non-loopback bind requires TLS</td></tr>
 *   <tr><td>{@code excalibur.api.read-rate-per-second}</td><td>Read token-bucket refill</td><td>50</td></tr>
 *   <tr><td>{@code excalibur.api.read-burst}</td><td>Read token-bucket capacity</td><td>200</td></tr>
 *   <tr><td>{@code excalibur.api.mutate-rate-per-second}</td><td>Mutate refill</td><td>5</td></tr>
 *   <tr><td>{@code excalibur.api.mutate-burst}</td><td>Mutate capacity</td><td>20</td></tr>
 *   <tr><td>{@code excalibur.api.rate-limit-max-clients}</td><td>Bound on tracked clients</td><td>10000</td></tr>
 *   <tr><td>{@code excalibur.api.max-top-k}</td><td>Upper bound on {@code top_k}</td><td>1000</td></tr>
 *   <tr><td>{@code excalibur.api.max-query-timeout-ms}</td><td>Upper bound on {@code timeout_ms}</td><td>60000</td></tr>
 *   <tr><td>{@code excalibur.api.max-query-chars}</td>
 *       <td>Upper bound on the length of {@code query}</td><td>8192</td></tr>
 *   <tr><td>{@code excalibur.ingest.allowed-roots}</td>
 *       <td>Comma-separated absolute directories filesystem ingest may read</td>
 *       <td>empty - filesystem ingest disabled</td></tr>
 *   <tr><td>{@code excalibur.ingest.allowed-hosts}</td>
 *       <td>Comma-separated hostnames URL ingest may crawl</td>
 *       <td>empty - URL ingest disabled</td></tr>
 *   <tr><td>{@code excalibur.ingest.max-sources}</td><td>Bound on registered sources</td><td>256</td></tr>
 * </table>
 *
 * <p>Transport security has its own keys; see {@link TlsConfig}.
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>No token is ever compiled in. An absent token disables authentication and,
 *       with it, any non-loopback bind.</li>
 *   <li>Allowed roots are resolved with {@link Path#toRealPath} when the policy is
 *       built, so containment comparisons are made between real paths.</li>
 *   <li>An empty allow-list means the corresponding ingest mode is disabled, never
 *       unrestricted.</li>
 *   <li>A non-loopback bind requires both a token and TLS. Authenticating over cleartext
 *       would put the bearer token on the wire in every request, so the two checks are one
 *       decision rather than two independent ones.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public final class ApiSecurityConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiSecurityConfig.class);

    /** Configuration key for the bearer token required by authenticated endpoints. */
    public static final String KEY_AUTH_TOKEN = "excalibur.api.auth-token";
    /** Configuration key for the listen address. */
    public static final String KEY_BIND_ADDRESS = "excalibur.api.bind-address";
    /** Configuration key for the read token-bucket refill rate. */
    public static final String KEY_READ_RATE = "excalibur.api.read-rate-per-second";
    /** Configuration key for the read token-bucket capacity. */
    public static final String KEY_READ_BURST = "excalibur.api.read-burst";
    /** Configuration key for the mutate token-bucket refill rate. */
    public static final String KEY_MUTATE_RATE = "excalibur.api.mutate-rate-per-second";
    /** Configuration key for the mutate token-bucket capacity. */
    public static final String KEY_MUTATE_BURST = "excalibur.api.mutate-burst";
    /** Configuration key bounding the number of tracked rate-limit clients. */
    public static final String KEY_RATE_LIMIT_MAX_CLIENTS = "excalibur.api.rate-limit-max-clients";
    /** Configuration key bounding {@code top_k} on the query endpoint. */
    public static final String KEY_MAX_TOP_K = "excalibur.api.max-top-k";
    /** Configuration key bounding {@code timeout_ms} on the query endpoint. */
    public static final String KEY_MAX_QUERY_TIMEOUT_MS = "excalibur.api.max-query-timeout-ms";
    /** Configuration key bounding the length of {@code query} on the query endpoint. */
    public static final String KEY_MAX_QUERY_CHARS = "excalibur.api.max-query-chars";
    /** Configuration key listing the directories filesystem ingest may read. */
    public static final String KEY_ALLOWED_ROOTS = "excalibur.ingest.allowed-roots";
    /** Configuration key listing the hosts URL ingest may crawl. */
    public static final String KEY_ALLOWED_HOSTS = "excalibur.ingest.allowed-hosts";
    /** Configuration key bounding the number of registered ingestion sources. */
    public static final String KEY_MAX_SOURCES = "excalibur.ingest.max-sources";
    /** Configuration key permitting a non-loopback bind without TLS. */
    public static final String KEY_ALLOW_PLAINTEXT = "excalibur.api.allow-plaintext";
    /** Configuration key listing the proxies whose forwarding header may be believed. */
    public static final String KEY_TRUSTED_PROXIES = "excalibur.api.trusted-proxies";

    /** Default listen address: loopback only. */
    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";
    /** Default read refill rate, requests per second. */
    public static final double DEFAULT_READ_RATE = 50.0;
    /** Default read burst capacity. */
    public static final double DEFAULT_READ_BURST = 200.0;
    /** Default mutate refill rate, requests per second. */
    public static final double DEFAULT_MUTATE_RATE = 5.0;
    /** Default mutate burst capacity. */
    public static final double DEFAULT_MUTATE_BURST = 20.0;
    /** Default bound on tracked rate-limit clients. */
    public static final int DEFAULT_RATE_LIMIT_MAX_CLIENTS = 10_000;
    /** Default upper bound on {@code top_k}. */
    public static final int DEFAULT_MAX_TOP_K = 1_000;
    /** Default upper bound on {@code timeout_ms}. */
    public static final long DEFAULT_MAX_QUERY_TIMEOUT_MS = 60_000L;
    /**
     * Default upper bound on the length of {@code query}, in characters.
     *
     * <p>Four times the largest thing in the index: the corpus is embedded a chunk at a time and
     * a chunk is about five hundred tokens, so a query several times that size has nothing of its
     * own shape to match against. It is also well inside the input limit of every embedding model
     * this talks to, which is what keeps an over-long query a rejected request rather than a
     * provider error halfway through one.</p>
     */
    public static final int DEFAULT_MAX_QUERY_CHARS = 8_192;
    /** Default bound on registered ingestion sources. */
    public static final int DEFAULT_MAX_SOURCES = 256;

    private final byte[] authToken;
    private final String bindAddress;
    private final boolean bindAddressExplicit;
    private final double readRatePerSecond;
    private final double readBurst;
    private final double mutateRatePerSecond;
    private final double mutateBurst;
    private final int rateLimitMaxClients;
    private final int maxTopK;
    private final long maxQueryTimeoutMs;
    private final int maxQueryChars;
    private final List<Path> allowedIngestRoots;
    private final Set<String> allowedIngestHosts;
    private final int maxRegisteredSources;
    private final TlsConfig tls;
    private final boolean allowPlaintext;
    private final TrustedProxies trustedProxies;

    private ApiSecurityConfig(Builder builder) {
        this.authToken = builder.authToken != null ? builder.authToken.clone() : null;
        this.bindAddress = builder.bindAddress;
        this.bindAddressExplicit = builder.bindAddressExplicit;
        this.readRatePerSecond = builder.readRatePerSecond;
        this.readBurst = builder.readBurst;
        this.mutateRatePerSecond = builder.mutateRatePerSecond;
        this.mutateBurst = builder.mutateBurst;
        this.rateLimitMaxClients = builder.rateLimitMaxClients;
        this.maxTopK = builder.maxTopK;
        this.maxQueryTimeoutMs = builder.maxQueryTimeoutMs;
        this.maxQueryChars = builder.maxQueryChars;
        this.allowedIngestRoots = List.copyOf(builder.allowedIngestRoots);
        this.allowedIngestHosts = Set.copyOf(builder.allowedIngestHosts);
        this.maxRegisteredSources = builder.maxRegisteredSources;
        this.tls = builder.tls;
        this.allowPlaintext = builder.allowPlaintext;
        this.trustedProxies = TrustedProxies.of(builder.trustedProxies);
    }

    /**
     * Returns the policy that applies when nothing has been configured: loopback
     * binding, no authentication, both ingest allow-lists empty.
     *
     * @return The default policy
     */
    public static ApiSecurityConfig defaults() {
        return builder().build();
    }

    /**
     * Resolves the policy from a properties map.
     *
     * @param properties Effective properties, from {@code Config.getEffectiveProperties()}
     * @return The resolved policy
     */
    public static ApiSecurityConfig fromProperties(Map<String, String> properties) {
        Map<String, String> props = properties != null ? properties : Collections.emptyMap();
        Builder builder = builder();

        String token = ConfigText.trimToNull(props.get(KEY_AUTH_TOKEN));
        if (token != null) {
            builder.authToken(token);
        }

        String bind = ConfigText.trimToNull(props.get(KEY_BIND_ADDRESS));
        if (bind != null) {
            builder.bindAddress(bind);
        }

        builder.readBudget(
            ConfigText.positiveDouble(props, KEY_READ_RATE, DEFAULT_READ_RATE),
            ConfigText.positiveDouble(props, KEY_READ_BURST, DEFAULT_READ_BURST));
        builder.mutateBudget(
            ConfigText.positiveDouble(props, KEY_MUTATE_RATE, DEFAULT_MUTATE_RATE),
            ConfigText.positiveDouble(props, KEY_MUTATE_BURST, DEFAULT_MUTATE_BURST));
        builder.rateLimitMaxClients(
            (int) ConfigText.positiveLong(props, KEY_RATE_LIMIT_MAX_CLIENTS, DEFAULT_RATE_LIMIT_MAX_CLIENTS));
        builder.maxTopK((int) ConfigText.positiveLong(props, KEY_MAX_TOP_K, DEFAULT_MAX_TOP_K));
        builder.maxQueryTimeoutMs(ConfigText.positiveLong(props, KEY_MAX_QUERY_TIMEOUT_MS, DEFAULT_MAX_QUERY_TIMEOUT_MS));
        builder.maxQueryChars((int) ConfigText.positiveLong(props, KEY_MAX_QUERY_CHARS, DEFAULT_MAX_QUERY_CHARS));
        builder.maxRegisteredSources((int) ConfigText.positiveLong(props, KEY_MAX_SOURCES, DEFAULT_MAX_SOURCES));

        for (String root : ConfigText.splitList(props.get(KEY_ALLOWED_ROOTS))) {
            builder.allowIngestRoot(root);
        }
        for (String host : ConfigText.splitList(props.get(KEY_ALLOWED_HOSTS))) {
            builder.allowIngestHost(host);
        }

        for (String proxy : ConfigText.splitList(props.get(KEY_TRUSTED_PROXIES))) {
            builder.trustProxy(proxy);
        }

        builder.tls(TlsConfig.fromProperties(props));
        builder.allowPlaintext(ConfigText.flag(props, KEY_ALLOW_PLAINTEXT, false));
        return builder.build();
    }

    /**
     * Creates a builder pre-loaded with the defaults.
     *
     * @return A new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Indicates whether authenticated endpoints must present a bearer token.
     *
     * @return true when a token is configured
     */
    public boolean isAuthenticationRequired() {
        return authToken != null;
    }

    /**
     * Compares a presented token against the configured one in constant time.
     *
     * @param presented The token bytes presented by the client (may be null)
     * @return true when the token matches
     */
    public boolean matchesToken(byte[] presented) {
        if (authToken == null || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(authToken, presented);
    }

    /**
     * Returns the configured listen address.
     *
     * @return The bind address literal
     */
    public String bindAddress() {
        return bindAddress;
    }

    /**
     * Resolves the listen address.
     *
     * @return The resolved address
     * @throws UnknownHostException if the configured address cannot be resolved
     */
    public InetAddress bindInetAddress() throws UnknownHostException {
        return InetAddress.getByName(bindAddress);
    }

    /**
     * Fails fast on a configuration that would expose the server unsafely.
     *
     * <p>A non-loopback bind requires an explicit {@code excalibur.api.bind-address}, a
     * configured token, and TLS. The three are one decision: a token sent over cleartext is
     * a credential on the wire in every single request, so authenticating without encrypting
     * buys very little and reads as though it bought a lot.</p>
     *
     * <p>{@code excalibur.api.allow-plaintext} relaxes only the TLS half, for the deployment
     * where that is genuinely correct - a sidecar or ingress that terminates TLS and forwards
     * over a loopback or private link. It is deliberately a separate, explicit key rather than
     * an inference from the environment, because the two cases look identical from inside this
     * process and only the operator knows which one it is.</p>
     *
     * @throws IllegalStateException if the policy would expose an unauthenticated or
     *         cleartext listener
     * @throws UnknownHostException if the configured bind address cannot be resolved
     */
    public void requireSafeToServe() throws UnknownHostException {
        InetAddress address = bindInetAddress();
        boolean loopbackOnly = address.isLoopbackAddress();
        if (!loopbackOnly && !isAuthenticationRequired()) {
            throw new IllegalStateException(
                "Refusing to bind " + bindAddress + " without authentication. Set "
                    + KEY_AUTH_TOKEN + " (environment: EXCALIBUR_API_AUTH_TOKEN) before "
                    + "binding a non-loopback address, or remove " + KEY_BIND_ADDRESS
                    + " to listen on " + DEFAULT_BIND_ADDRESS + " only.");
        }
        if (!loopbackOnly && !tls.isEnabled() && !allowPlaintext) {
            throw new IllegalStateException(
                "Refusing to bind " + bindAddress + " without TLS: the bearer token would be "
                    + "sent in cleartext with every request. Set " + TlsConfig.KEY_KEYSTORE
                    + " (with " + TlsConfig.KEY_KEYSTORE_PASSWORD + " from "
                    + "EXCALIBUR_API_TLS_KEYSTORE_PASSWORD), or set " + KEY_ALLOW_PLAINTEXT
                    + "=true if TLS is terminated in front of this process.");
        }
        if (!isAuthenticationRequired()) {
            LOGGER.warn("No {} configured: the API is unauthenticated and will accept requests "
                + "on {} only. Set EXCALIBUR_API_AUTH_TOKEN before exposing this server.",
                KEY_AUTH_TOKEN, bindAddress);
        }
        if (!loopbackOnly && allowPlaintext && !tls.isEnabled()) {
            LOGGER.warn("{}=true: serving cleartext on {}. Anything that can observe this link "
                + "can read the bearer token and every query.", KEY_ALLOW_PLAINTEXT, bindAddress);
        }
        if (!bindAddressExplicit && !loopbackOnly) {
            LOGGER.warn("Default bind address {} did not resolve to loopback", bindAddress);
        }
    }

    /** @return read token-bucket refill rate, requests per second */
    public double readRatePerSecond() {
        return readRatePerSecond;
    }

    /** @return read token-bucket capacity */
    public double readBurst() {
        return readBurst;
    }

    /** @return mutate token-bucket refill rate, requests per second */
    public double mutateRatePerSecond() {
        return mutateRatePerSecond;
    }

    /** @return mutate token-bucket capacity */
    public double mutateBurst() {
        return mutateBurst;
    }

    /** @return bound on the number of clients the rate limiter tracks */
    public int rateLimitMaxClients() {
        return rateLimitMaxClients;
    }

    /** @return upper bound accepted for {@code top_k} */
    public int maxTopK() {
        return maxTopK;
    }

    /** @return upper bound accepted for {@code timeout_ms} */
    public long maxQueryTimeoutMs() {
        return maxQueryTimeoutMs;
    }

    /** @return upper bound accepted for the length of {@code query}, in characters */
    public int maxQueryChars() {
        return maxQueryChars;
    }

    /** @return real paths filesystem ingest may read; empty means disabled */
    public List<Path> allowedIngestRoots() {
        return allowedIngestRoots;
    }

    /** @return lower-cased hosts URL ingest may crawl; empty means disabled */
    public Set<String> allowedIngestHosts() {
        return allowedIngestHosts;
    }

    /** @return bound on the number of registered ingestion sources */
    public int maxRegisteredSources() {
        return maxRegisteredSources;
    }

    /** @return the transport security policy; never null, possibly disabled */
    public TlsConfig tls() {
        return tls;
    }

    /**
     * The hops whose forwarding header names the client to rate limit.
     *
     * @return The trusted proxies; empty unless configured, meaning only the peer is believed
     */
    public TrustedProxies trustedProxies() {
        return trustedProxies;
    }

    /** @return true when a non-loopback bind may serve cleartext */
    public boolean allowsPlaintext() {
        return allowPlaintext;
    }

    @Override
    public String toString() {
        return "ApiSecurityConfig{bindAddress=" + bindAddress
            + ", authentication=" + (isAuthenticationRequired() ? "required" : "disabled")
            + ", readRate=" + readRatePerSecond + "/s burst=" + readBurst
            + ", mutateRate=" + mutateRatePerSecond + "/s burst=" + mutateBurst
            + ", allowedIngestRoots=" + allowedIngestRoots.size()
            + ", allowedIngestHosts=" + allowedIngestHosts.size()
            + ", tls=" + (tls.isEnabled() ? "enabled" : "disabled")
            + (allowPlaintext ? ", allowPlaintext=true" : "") + "}";
    }

    /**
     * Builder for {@link ApiSecurityConfig}.
     *
     * <p>Every setter starts from the safe default, so a builder that is never
     * touched produces a loopback-only, unauthenticated policy with both ingest
     * modes disabled.</p>
     */
    public static final class Builder {

        private byte[] authToken;
        private String bindAddress = DEFAULT_BIND_ADDRESS;
        private boolean bindAddressExplicit;
        private double readRatePerSecond = DEFAULT_READ_RATE;
        private double readBurst = DEFAULT_READ_BURST;
        private double mutateRatePerSecond = DEFAULT_MUTATE_RATE;
        private double mutateBurst = DEFAULT_MUTATE_BURST;
        private int rateLimitMaxClients = DEFAULT_RATE_LIMIT_MAX_CLIENTS;
        private int maxTopK = DEFAULT_MAX_TOP_K;
        private long maxQueryTimeoutMs = DEFAULT_MAX_QUERY_TIMEOUT_MS;
        private int maxQueryChars = DEFAULT_MAX_QUERY_CHARS;
        private final List<Path> allowedIngestRoots = new ArrayList<>();
        private final Set<String> allowedIngestHosts = new LinkedHashSet<>();
        private int maxRegisteredSources = DEFAULT_MAX_SOURCES;
        private TlsConfig tls = TlsConfig.disabled();
        private boolean allowPlaintext;
        private final List<String> trustedProxies = new ArrayList<>();

        private Builder() {
        }

        /**
         * Sets the bearer token required by authenticated endpoints.
         *
         * @param token The token; null or blank leaves authentication disabled
         * @return this builder
         */
        public Builder authToken(String token) {
            String trimmed = ConfigText.trimToNull(token);
            this.authToken = trimmed != null ? trimmed.getBytes(StandardCharsets.UTF_8) : null;
            return this;
        }

        /**
         * Sets the listen address explicitly.
         *
         * @param address The address literal
         * @return this builder
         */
        public Builder bindAddress(String address) {
            String trimmed = ConfigText.trimToNull(address);
            if (trimmed != null) {
                this.bindAddress = trimmed;
                this.bindAddressExplicit = true;
            }
            return this;
        }

        /**
         * Sets the read token-bucket budget.
         *
         * @param ratePerSecond Refill rate
         * @param burst Bucket capacity
         * @return this builder
         */
        public Builder readBudget(double ratePerSecond, double burst) {
            this.readRatePerSecond = ratePerSecond;
            this.readBurst = burst;
            return this;
        }

        /**
         * Sets the mutate token-bucket budget.
         *
         * @param ratePerSecond Refill rate
         * @param burst Bucket capacity
         * @return this builder
         */
        public Builder mutateBudget(double ratePerSecond, double burst) {
            this.mutateRatePerSecond = ratePerSecond;
            this.mutateBurst = burst;
            return this;
        }

        /**
         * Bounds the number of clients the rate limiter tracks.
         *
         * @param maxClients Maximum tracked clients
         * @return this builder
         */
        public Builder rateLimitMaxClients(int maxClients) {
            this.rateLimitMaxClients = maxClients;
            return this;
        }

        /**
         * Bounds {@code top_k}.
         *
         * @param maxTopK Maximum accepted top_k
         * @return this builder
         */
        public Builder maxTopK(int maxTopK) {
            this.maxTopK = maxTopK;
            return this;
        }

        /**
         * Bounds {@code timeout_ms}.
         *
         * @param maxQueryTimeoutMs Maximum accepted timeout
         * @return this builder
         */
        public Builder maxQueryTimeoutMs(long maxQueryTimeoutMs) {
            this.maxQueryTimeoutMs = maxQueryTimeoutMs;
            return this;
        }

        /**
         * Bounds the length of {@code query}.
         *
         * @param maxQueryChars Maximum accepted query length in characters
         * @return this builder
         */
        public Builder maxQueryChars(int maxQueryChars) {
            this.maxQueryChars = maxQueryChars;
            return this;
        }

        /**
         * Bounds the number of registered ingestion sources.
         *
         * @param maxRegisteredSources Maximum registered sources
         * @return this builder
         */
        public Builder maxRegisteredSources(int maxRegisteredSources) {
            this.maxRegisteredSources = maxRegisteredSources;
            return this;
        }

        /**
         * Adds a directory filesystem ingest may read.
         *
         * <p>The path is canonicalised with {@link Path#toRealPath} so that later
         * containment checks compare real paths. A path that cannot be resolved is
         * dropped: it cannot contain anything.</p>
         *
         * @param root Absolute directory
         * @return this builder
         */
        public Builder allowIngestRoot(String root) {
            String trimmed = ConfigText.trimToNull(root);
            if (trimmed == null) {
                return this;
            }
            return allowIngestRoot(Path.of(trimmed));
        }

        /**
         * Adds a directory filesystem ingest may read.
         *
         * @param root Absolute directory
         * @return this builder
         */
        public Builder allowIngestRoot(Path root) {
            if (root == null) {
                return this;
            }
            try {
                Path real = root.toRealPath();
                if (!allowedIngestRoots.contains(real)) {
                    allowedIngestRoots.add(real);
                }
            } catch (IOException e) {
                LOGGER.warn("Ignoring ingest root that cannot be resolved: {} ({})",
                    root, e.getClass().getSimpleName());
            }
            return this;
        }

        /**
         * Adds a host URL ingest may crawl.
         *
         * @param host Hostname
         * @return this builder
         */
        public Builder allowIngestHost(String host) {
            String trimmed = ConfigText.trimToNull(host);
            if (trimmed != null) {
                allowedIngestHosts.add(trimmed.toLowerCase(Locale.ROOT));
            }
            return this;
        }

        /**
         * Believes the forwarding header of one proxy address or range.
         *
         * <p>Name only hops this deployment actually puts in front of the node. Anything named
         * here can tell the rate limiter which client to charge, so a range wider than the
         * proxies it is meant to cover hands that decision to whoever else is inside it.</p>
         *
         * @param proxy An address or CIDR range; null or blank is ignored
         * @return this builder
         * @throws IllegalArgumentException if the entry is not an address or range
         */
        public Builder trustProxy(String proxy) {
            String trimmed = ConfigText.trimToNull(proxy);
            if (trimmed != null) {
                trustedProxies.add(trimmed);
            }
            return this;
        }

        /**
         * Sets the transport security policy.
         *
         * @param tls The policy; null leaves TLS disabled
         * @return this builder
         */
        public Builder tls(TlsConfig tls) {
            this.tls = tls != null ? tls : TlsConfig.disabled();
            return this;
        }

        /**
         * Permits a non-loopback bind to serve cleartext.
         *
         * @param allowPlaintext true to allow it
         * @return this builder
         */
        public Builder allowPlaintext(boolean allowPlaintext) {
            this.allowPlaintext = allowPlaintext;
            return this;
        }

        /**
         * Builds the immutable policy.
         *
         * @return The policy
         */
        public ApiSecurityConfig build() {
            return new ApiSecurityConfig(this);
        }
    }
}
