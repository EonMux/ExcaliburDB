package excalibur.api.security;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Transport security for the HTTP API surface.
 *
 * <p>Resolved once at startup and then immutable. Holding it apart from {@link ApiSecurityConfig}
 * keeps the key material and the handshake policy in one place; {@code ApiSecurityConfig} composes
 * it so that "is this server safe to expose" stays a single question with a single answer.
 *
 * <h2>Configuration keys</h2>
 * <table border="1">
 *   <caption>TLS keys</caption>
 *   <tr><th>Key</th><th>Meaning</th><th>Default</th></tr>
 *   <tr><td>{@code excalibur.api.tls-keystore}</td>
 *       <td>Path to the keystore holding the server certificate and private key.
 *           Setting it turns TLS on.</td>
 *       <td>unset - TLS disabled</td></tr>
 *   <tr><td>{@code excalibur.api.tls-keystore-password}</td>
 *       <td>Keystore password. Supply it as {@code EXCALIBUR_API_TLS_KEYSTORE_PASSWORD}.</td>
 *       <td>required when a keystore is set</td></tr>
 *   <tr><td>{@code excalibur.api.tls-keystore-type}</td><td>Keystore format</td><td>PKCS12</td></tr>
 *   <tr><td>{@code excalibur.api.tls-key-password}</td>
 *       <td>Password of the private key entry, when it differs from the keystore password.</td>
 *       <td>the keystore password</td></tr>
 *   <tr><td>{@code excalibur.api.tls-key-alias}</td>
 *       <td>Which key entry to serve, for a keystore holding more than one.</td>
 *       <td>unset - the keystore must hold exactly one key entry</td></tr>
 *   <tr><td>{@code excalibur.api.tls-protocols}</td><td>Comma-separated enabled protocols</td>
 *       <td>TLSv1.3, TLSv1.2</td></tr>
 *   <tr><td>{@code excalibur.api.tls-cipher-suites}</td>
 *       <td>Comma-separated enabled suites, in server preference order</td>
 *       <td>the forward-secret AEAD suites listed below</td></tr>
 *   <tr><td>{@code excalibur.api.tls-client-auth}</td><td>{@code none}, {@code want} or
 *       {@code need}</td><td>none</td></tr>
 *   <tr><td>{@code excalibur.api.tls-truststore}</td>
 *       <td>Path to the truststore of client CAs, for mutual TLS.</td>
 *       <td>unset - the JVM default trust store</td></tr>
 *   <tr><td>{@code excalibur.api.tls-truststore-password}</td>
 *       <td>Truststore password. Supply it as
 *           {@code EXCALIBUR_API_TLS_TRUSTSTORE_PASSWORD}.</td>
 *       <td>unset - integrity of the truststore is not checked</td></tr>
 *   <tr><td>{@code excalibur.api.tls-enabled}</td>
 *       <td>Turns TLS off even though a keystore is configured, for a deployment that
 *           terminates TLS in front of this process.</td>
 *       <td>derived from whether a keystore is set</td></tr>
 * </table>
 *
 * <h2>Domain Correctness</h2>
 * <ul>
 *   <li>No key material is compiled in and no password has a default. A keystore without a
 *       password is a startup failure, not a keystore opened with the empty password.</li>
 *   <li>The requested protocols and suites are intersected with what the JVM actually supports,
 *       and an empty intersection fails at startup rather than at the first handshake.</li>
 *   <li>The keystore is opened, the key entry located and the certificate chain checked while
 *       the server is starting, so a bad path, a wrong password or a missing chain is reported
 *       before a socket is bound.</li>
 *   <li>Passwords are held as {@code char[]}, are never logged, and never appear in
 *       {@link #toString()}.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe. {@link #configurator()} builds a fresh
 * {@link SSLContext} each time it is called and does not mutate the policy.</p>
 */
public final class TlsConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(TlsConfig.class);

    /** Configuration key for the keystore path. Setting it turns TLS on. */
    public static final String KEY_KEYSTORE = "excalibur.api.tls-keystore";
    /** Configuration key for the keystore password. */
    public static final String KEY_KEYSTORE_PASSWORD = "excalibur.api.tls-keystore-password";
    /** Configuration key for the keystore format. */
    public static final String KEY_KEYSTORE_TYPE = "excalibur.api.tls-keystore-type";
    /** Configuration key for the private key entry's password. */
    public static final String KEY_KEY_PASSWORD = "excalibur.api.tls-key-password";
    /** Configuration key selecting which key entry to serve. */
    public static final String KEY_KEY_ALIAS = "excalibur.api.tls-key-alias";
    /** Configuration key for the enabled protocols. */
    public static final String KEY_PROTOCOLS = "excalibur.api.tls-protocols";
    /** Configuration key for the enabled cipher suites. */
    public static final String KEY_CIPHER_SUITES = "excalibur.api.tls-cipher-suites";
    /** Configuration key for the client certificate policy. */
    public static final String KEY_CLIENT_AUTH = "excalibur.api.tls-client-auth";
    /** Configuration key for the truststore of client CAs. */
    public static final String KEY_TRUSTSTORE = "excalibur.api.tls-truststore";
    /** Configuration key for the truststore password. */
    public static final String KEY_TRUSTSTORE_PASSWORD = "excalibur.api.tls-truststore-password";
    /** Configuration key for the truststore format. */
    public static final String KEY_TRUSTSTORE_TYPE = "excalibur.api.tls-truststore-type";
    /** Configuration key that turns TLS off despite a configured keystore. */
    public static final String KEY_ENABLED = "excalibur.api.tls-enabled";

    /** Default keystore format. */
    public static final String DEFAULT_KEYSTORE_TYPE = "PKCS12";

    /**
     * Protocols enabled unless the operator names others.
     *
     * <p>1.3 first, 1.2 retained for clients that cannot speak it yet. Everything below 1.2 is
     * left out: those versions have no configuration that is still sound.
     */
    public static final List<String> DEFAULT_PROTOCOLS = List.of("TLSv1.3", "TLSv1.2");

    /**
     * Cipher suites enabled unless the operator names others, in server preference order.
     *
     * <p>Every entry is an AEAD suite with forward secrecy. The list is deliberately short:
     * the JVM's own default set still contains CBC suites and static-RSA key exchange, which are
     * kept for compatibility with clients this server has no reason to serve.
     */
    public static final List<String> DEFAULT_CIPHER_SUITES = List.of(
        // TLS 1.3
        "TLS_AES_256_GCM_SHA384",
        "TLS_CHACHA20_POLY1305_SHA256",
        "TLS_AES_128_GCM_SHA256",
        // TLS 1.2, ECDHE only
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");

    /** Whether the server asks for, and whether it requires, a client certificate. */
    public enum ClientAuth {
        /** No client certificate is requested. */
        NONE,
        /** A client certificate is requested; a client without one is still served. */
        WANT,
        /** A client certificate is required; the handshake fails without one. */
        NEED;

        /**
         * Parses a configured value.
         *
         * @param raw The configured literal, case-insensitive
         * @return The policy
         * @throws IllegalArgumentException if the value names no policy
         */
        static ClientAuth parse(String raw) {
            for (ClientAuth candidate : values()) {
                if (candidate.name().equalsIgnoreCase(raw)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException(
                KEY_CLIENT_AUTH + " must be one of none, want, need; got: " + raw);
        }
    }

    private final boolean enabled;
    private final Path keystore;
    private final char[] keystorePassword;
    private final String keystoreType;
    private final char[] keyPassword;
    private final String keyAlias;
    private final List<String> protocols;
    private final List<String> cipherSuites;
    private final ClientAuth clientAuth;
    private final Path truststore;
    private final char[] truststorePassword;
    private final String truststoreType;

    private TlsConfig(Builder builder) {
        this.keystore = builder.keystore;
        this.keystorePassword = copyOf(builder.keystorePassword);
        this.keystoreType = builder.keystoreType;
        this.keyPassword = builder.keyPassword != null
            ? copyOf(builder.keyPassword)
            : copyOf(builder.keystorePassword);
        this.keyAlias = builder.keyAlias;
        this.protocols = List.copyOf(builder.protocols);
        this.cipherSuites = List.copyOf(builder.cipherSuites);
        this.clientAuth = builder.clientAuth;
        this.truststore = builder.truststore;
        this.truststorePassword = copyOf(builder.truststorePassword);
        this.truststoreType = builder.truststoreType != null
            ? builder.truststoreType
            : builder.keystoreType;
        this.enabled = builder.resolveEnabled();
        if (this.enabled) {
            validate();
        }
    }

    /** The policy that applies when nothing is configured: TLS off. */
    public static TlsConfig disabled() {
        return builder().build();
    }

    /**
     * Resolves the policy from a properties map.
     *
     * @param properties Effective properties, from {@code Config.getEffectiveProperties()}
     * @return The resolved policy
     * @throws IllegalStateException if TLS is asked for but cannot be configured
     */
    public static TlsConfig fromProperties(Map<String, String> properties) {
        Map<String, String> props = properties != null ? properties : Collections.emptyMap();
        Builder builder = builder();

        String keystore = ConfigText.value(props, KEY_KEYSTORE);
        if (keystore != null) {
            builder.keystore(Path.of(keystore));
        }
        builder.keystorePassword(charsOf(ConfigText.value(props, KEY_KEYSTORE_PASSWORD)));
        builder.keyPassword(charsOf(ConfigText.value(props, KEY_KEY_PASSWORD)));

        String type = ConfigText.value(props, KEY_KEYSTORE_TYPE);
        if (type != null) {
            builder.keystoreType(type);
        }
        builder.keyAlias(ConfigText.value(props, KEY_KEY_ALIAS));

        List<String> protocols = ConfigText.splitList(props.get(KEY_PROTOCOLS));
        if (!protocols.isEmpty()) {
            builder.protocols(protocols);
        }
        List<String> suites = ConfigText.splitList(props.get(KEY_CIPHER_SUITES));
        if (!suites.isEmpty()) {
            builder.cipherSuites(suites);
        }

        String clientAuth = ConfigText.value(props, KEY_CLIENT_AUTH);
        if (clientAuth != null) {
            builder.clientAuth(ClientAuth.parse(clientAuth));
        }

        String truststore = ConfigText.value(props, KEY_TRUSTSTORE);
        if (truststore != null) {
            builder.truststore(Path.of(truststore));
        }
        builder.truststorePassword(charsOf(ConfigText.value(props, KEY_TRUSTSTORE_PASSWORD)));
        builder.truststoreType(ConfigText.value(props, KEY_TRUSTSTORE_TYPE));

        String explicit = ConfigText.value(props, KEY_ENABLED);
        if (explicit != null) {
            builder.enabled(ConfigText.flag(props, KEY_ENABLED, true));
        }
        return builder.build();
    }

    /** A builder starting from TLS disabled. */
    public static Builder builder() {
        return new Builder();
    }

    /** Whether the listener should speak TLS. */
    public boolean isEnabled() {
        return enabled;
    }

    /** The configured keystore, or null when TLS is off. */
    public Path keystore() {
        return keystore;
    }

    /** The keystore format. */
    public String keystoreType() {
        return keystoreType;
    }

    /** The selected key entry, or null when the keystore holds exactly one. */
    public String keyAlias() {
        return keyAlias;
    }

    /** The protocols the operator asked for, before intersection with the JVM's. */
    public List<String> requestedProtocols() {
        return protocols;
    }

    /** The suites the operator asked for, before intersection with the JVM's. */
    public List<String> requestedCipherSuites() {
        return cipherSuites;
    }

    /** The client certificate policy. */
    public ClientAuth clientAuth() {
        return clientAuth;
    }

    /** The truststore of client CAs, or null to use the JVM default. */
    public Path truststore() {
        return truststore;
    }

    /**
     * Builds the configurator the HTTPS listener is started with.
     *
     * <p>Everything that can fail happens here, before a socket is bound: the keystore is read,
     * the key entry located, the trust material loaded, and the protocol and suite lists reduced
     * to what this JVM supports.
     *
     * @return A configurator pinned to this policy
     * @throws IOException if the keystore or truststore cannot be read
     * @throws GeneralSecurityException if the key material is unusable
     * @throws IllegalStateException if TLS is not enabled, or nothing is left after intersection
     */
    public HttpsConfigurator configurator() throws IOException, GeneralSecurityException {
        if (!enabled) {
            throw new IllegalStateException("TLS is not enabled; nothing to configure");
        }
        SSLContext context = createSslContext();
        SSLParameters supported = context.getSupportedSSLParameters();

        String[] enabledProtocols = intersect(protocols, supported.getProtocols(),
            KEY_PROTOCOLS, "protocol");
        String[] enabledSuites = intersect(cipherSuites, supported.getCipherSuites(),
            KEY_CIPHER_SUITES, "cipher suite");
        ClientAuth auth = clientAuth;

        LOGGER.info("TLS enabled: protocols={}, cipherSuites={}, clientAuth={}",
            Arrays.toString(enabledProtocols), enabledSuites.length, auth);

        return new HttpsConfigurator(context) {
            @Override
            public void configure(HttpsParameters params) {
                SSLParameters ssl = getSSLContext().getDefaultSSLParameters();
                ssl.setProtocols(enabledProtocols);
                ssl.setCipherSuites(enabledSuites);
                // Server preference order, so the ordering above is the ordering that applies
                // rather than whatever the client happens to list first.
                ssl.setUseCipherSuitesOrder(true);
                // Set on the SSLParameters, not on the HttpsParameters. When
                // HttpsParameters.setSSLParameters is called the JDK applies those parameters
                // wholesale and never reads HttpsParameters.getNeedClientAuth, so a policy set
                // on the wrong object is silently discarded and the server asks for nothing.
                switch (auth) {
                    case NEED -> ssl.setNeedClientAuth(true);
                    case WANT -> ssl.setWantClientAuth(true);
                    case NONE -> {
                        // Neither flag set: no certificate is requested.
                    }
                }
                params.setSSLParameters(ssl);
            }
        };
    }

    /**
     * Loads the key and trust material into a context.
     *
     * @return An initialised context
     * @throws IOException if a store cannot be read
     * @throws GeneralSecurityException if the material is unusable
     */
    SSLContext createSslContext() throws IOException, GeneralSecurityException {
        KeyStore store = loadStore(keystore, keystoreType, keystorePassword, KEY_KEYSTORE);
        KeyStore serving = keyAlias != null
            ? restrictTo(store, keyAlias)
            : requireExactlyOneKey(store);

        KeyManagerFactory keys =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        try {
            keys.init(serving, keyPassword);
        } catch (UnrecoverableKeyException e) {
            UnrecoverableKeyException explained = new UnrecoverableKeyException(
                "The private key in " + keystore + " could not be recovered. Set "
                    + KEY_KEY_PASSWORD + " if the key password differs from the keystore password.");
            explained.initCause(e);
            throw explained;
        }

        TrustManager[] trust = null;
        if (truststore != null) {
            KeyStore trustStore =
                loadStore(truststore, truststoreType, truststorePassword, KEY_TRUSTSTORE);
            TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trustStore);
            trust = factory.getTrustManagers();
        }

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), trust, null);
        return context;
    }

    private KeyStore loadStore(Path path, String type, char[] password, String key)
            throws IOException, GeneralSecurityException {
        if (!Files.isReadable(path)) {
            throw new IOException(key + " is not readable: " + path);
        }
        KeyStore store = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, password);
        }
        return store;
    }

    /**
     * A keystore holding only the named entry.
     *
     * <p>Copying the one entry into a fresh store, rather than wrapping the key manager to pin an
     * alias, is what makes the selection reliable: the JDK's key manager implementations rewrite
     * aliases - one lower-cases them, another prefixes them with an index - so an alias compared
     * against what the key manager reports would match for some providers and not others. A store
     * with one entry leaves the key manager no choice to get wrong.
     */
    private KeyStore restrictTo(KeyStore source, String alias)
            throws IOException, GeneralSecurityException {
        if (!source.containsAlias(alias)) {
            throw new KeyStoreException(KEY_KEY_ALIAS + "=" + alias
                + " is not in " + keystore + ". Available aliases: " + aliasesOf(source));
        }
        if (!source.isKeyEntry(alias)) {
            throw new KeyStoreException(KEY_KEY_ALIAS + "=" + alias + " in " + keystore
                + " is a certificate entry, not a private key entry.");
        }
        Key key = source.getKey(alias, keyPassword);
        Certificate[] chain = source.getCertificateChain(alias);
        if (chain == null || chain.length == 0) {
            throw new KeyStoreException(KEY_KEY_ALIAS + "=" + alias + " in " + keystore
                + " has no certificate chain; a server key must be presented with its chain.");
        }
        KeyStore single = KeyStore.getInstance(keystoreType);
        single.load(null, null);
        single.setKeyEntry(alias, key, keyPassword, chain);
        return single;
    }

    /**
     * The store itself, once it is known to hold exactly one usable server key.
     *
     * <p>Zero is a misconfiguration that would otherwise surface as a handshake failure with no
     * server-side explanation. More than one is ambiguous, and picking the first would make which
     * certificate the server presents depend on keystore iteration order.
     */
    private KeyStore requireExactlyOneKey(KeyStore store) throws GeneralSecurityException {
        List<String> keyEntries = new ArrayList<>();
        for (String alias : Collections.list(store.aliases())) {
            if (store.isKeyEntry(alias)) {
                keyEntries.add(alias);
            }
        }
        if (keyEntries.isEmpty()) {
            throw new KeyStoreException("No private key entry in " + keystore
                + ". A server keystore must hold the server's key and certificate chain;"
                + " entries found: " + aliasesOf(store));
        }
        if (keyEntries.size() > 1) {
            throw new KeyStoreException(keystore + " holds " + keyEntries.size()
                + " private key entries " + keyEntries + "; set " + KEY_KEY_ALIAS
                + " to choose which certificate this server presents.");
        }
        return store;
    }

    private static List<String> aliasesOf(KeyStore store) {
        try {
            return Collections.list(store.aliases());
        } catch (KeyStoreException e) {
            return List.of();
        }
    }

    /**
     * The requested names that this JVM supports, in the requested order.
     *
     * <p>Silently dropping an unsupported name would leave the server running with a policy the
     * operator did not configure, so each drop is reported; an empty result fails the startup,
     * because the alternative is a listener that accepts a connection and then cannot complete a
     * single handshake.
     */
    private static String[] intersect(List<String> requested, String[] supportedNames,
                                      String key, String what) {
        Set<String> supported = new LinkedHashSet<>(Arrays.asList(supportedNames));
        List<String> kept = new ArrayList<>(requested.size());
        List<String> dropped = new ArrayList<>();
        for (String name : requested) {
            if (supported.contains(name)) {
                kept.add(name);
            } else {
                dropped.add(name);
            }
        }
        if (!dropped.isEmpty()) {
            LOGGER.warn("Ignoring {} {}(s) this JVM does not support: {}", key, what, dropped);
        }
        if (kept.isEmpty()) {
            throw new IllegalStateException("None of the " + what + "s configured by " + key
                + " are supported by this JVM: " + requested);
        }
        return kept.toArray(new String[0]);
    }

    private void validate() {
        if (keystore == null) {
            throw new IllegalStateException("TLS is enabled but " + KEY_KEYSTORE
                + " is not set. Point it at a keystore holding the server certificate and"
                + " private key, or leave " + KEY_ENABLED + " unset to serve plaintext.");
        }
        if (keystorePassword == null || keystorePassword.length == 0) {
            throw new IllegalStateException(KEY_KEYSTORE_PASSWORD + " is required when "
                + KEY_KEYSTORE + " is set. Supply it as EXCALIBUR_API_TLS_KEYSTORE_PASSWORD"
                + " rather than writing it into the properties file.");
        }
        if (!Files.isReadable(keystore)) {
            throw new IllegalStateException(KEY_KEYSTORE + " is not readable: " + keystore);
        }
        if (truststore != null && !Files.isReadable(truststore)) {
            throw new IllegalStateException(KEY_TRUSTSTORE + " is not readable: " + truststore);
        }
        if (clientAuth != ClientAuth.NONE && truststore == null) {
            LOGGER.warn("{}={} with no {} configured: client certificates will be checked against"
                + " the JVM default trust store, which trusts the public web CAs.",
                KEY_CLIENT_AUTH, clientAuth.name().toLowerCase(Locale.ROOT), KEY_TRUSTSTORE);
        }
    }

    private static char[] charsOf(String value) {
        return value != null ? value.toCharArray() : null;
    }

    private static char[] copyOf(char[] value) {
        return value != null ? value.clone() : null;
    }

    @Override
    public String toString() {
        if (!enabled) {
            return "TlsConfig{disabled}";
        }
        return "TlsConfig{keystore=" + keystore
            + ", type=" + keystoreType
            + ", alias=" + (keyAlias != null ? keyAlias : "<only entry>")
            + ", protocols=" + protocols
            + ", cipherSuites=" + cipherSuites.size()
            + ", clientAuth=" + clientAuth
            + ", truststore=" + (truststore != null ? truststore + " (" + truststoreType + ")"
                : "<jvm default>")
            + "}";
    }

    /**
     * Builder for {@link TlsConfig}.
     *
     * <p>An untouched builder produces a disabled policy, so a deployment that configures nothing
     * gets plaintext on loopback rather than a half-configured listener.
     */
    public static final class Builder {

        private Path keystore;
        private char[] keystorePassword;
        private String keystoreType = DEFAULT_KEYSTORE_TYPE;
        private char[] keyPassword;
        private String keyAlias;
        private List<String> protocols = DEFAULT_PROTOCOLS;
        private List<String> cipherSuites = DEFAULT_CIPHER_SUITES;
        private ClientAuth clientAuth = ClientAuth.NONE;
        private Path truststore;
        private char[] truststorePassword;
        private String truststoreType;
        private Boolean enabled;

        private Builder() {
        }

        /** Sets the keystore holding the server key and chain. Setting it turns TLS on. */
        public Builder keystore(Path path) {
            this.keystore = path;
            return this;
        }

        /** Sets the keystore password. The array is copied. */
        public Builder keystorePassword(char[] password) {
            this.keystorePassword = copyOf(password);
            return this;
        }

        /** Sets the keystore format. */
        public Builder keystoreType(String type) {
            String trimmed = ConfigText.trimToNull(type);
            if (trimmed != null) {
                this.keystoreType = trimmed;
            }
            return this;
        }

        /** Sets the private key entry's password, when it differs from the keystore's. */
        public Builder keyPassword(char[] password) {
            this.keyPassword = copyOf(password);
            return this;
        }

        /** Selects which key entry to serve. */
        public Builder keyAlias(String alias) {
            this.keyAlias = ConfigText.trimToNull(alias);
            return this;
        }

        /** Sets the enabled protocols, in preference order. */
        public Builder protocols(List<String> protocols) {
            if (protocols != null && !protocols.isEmpty()) {
                this.protocols = List.copyOf(protocols);
            }
            return this;
        }

        /** Sets the enabled cipher suites, in server preference order. */
        public Builder cipherSuites(List<String> suites) {
            if (suites != null && !suites.isEmpty()) {
                this.cipherSuites = List.copyOf(suites);
            }
            return this;
        }

        /** Sets the client certificate policy. */
        public Builder clientAuth(ClientAuth clientAuth) {
            if (clientAuth != null) {
                this.clientAuth = clientAuth;
            }
            return this;
        }

        /** Sets the truststore of client CAs, for mutual TLS. */
        public Builder truststore(Path path) {
            this.truststore = path;
            return this;
        }

        /** Sets the truststore password. The array is copied. */
        public Builder truststorePassword(char[] password) {
            this.truststorePassword = copyOf(password);
            return this;
        }

        /** Sets the truststore format; defaults to the keystore's format. */
        public Builder truststoreType(String type) {
            this.truststoreType = ConfigText.trimToNull(type);
            return this;
        }

        /** Overrides whether TLS is on, independently of whether a keystore is configured. */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        private boolean resolveEnabled() {
            return enabled != null ? enabled : keystore != null;
        }

        /**
         * Builds the immutable policy.
         *
         * @return The policy
         * @throws IllegalStateException if TLS is enabled but cannot be configured
         */
        public TlsConfig build() {
            return new TlsConfig(this);
        }
    }
}
