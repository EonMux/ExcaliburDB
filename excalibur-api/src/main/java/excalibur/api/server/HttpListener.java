package excalibur.api.server;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import excalibur.api.security.ApiSecurityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.util.Objects;

/**
 * Opens the socket the node is served on, cleartext or TLS as the policy asks.
 *
 * <p>Separate from {@link ExcaliburDBServer} because binding has a failure mode the rest of
 * startup does not: once a socket is bound, every later failure has to give it back, and the
 * server's own {@code stop()} will not, because it does nothing until the node is marked
 * running. Keeping the bind in one place is what lets the order below be stated once and read
 * once, instead of being re-derived by whoever next edits {@code start()}.</p>
 *
 * <p>{@link ListenPort} answers which port; this answers what is opened on it.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless, and every method is static. The server it returns is the caller's from that
 * point on, including the obligation to stop it.</p>
 *
 * @author ExcaliburDB
 */
final class HttpListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpListener.class);

    private HttpListener() {
    }

    /**
     * Binds the listening socket, with TLS where the policy asks for it.
     *
     * <p>The TLS configurator is built before the bind, not after: reading the keystore,
     * locating the key entry and reducing the protocol and suite lists to what this JVM
     * supports can all fail, and a failure after {@code HttpsServer.create} leaves a listening
     * socket behind that nothing is holding a reference to.</p>
     *
     * @param security The policy naming the bind address and the TLS settings
     * @param port     The port to bind, 0 for any free port
     * @param backlog  The connection backlog, 0 for the system default
     * @return A bound server, not yet accepting
     * @throws IOException if TLS cannot be initialised or the port cannot be bound
     * @throws NullPointerException if security is null
     */
    static HttpServer open(ApiSecurityConfig security, int port, int backlog) throws IOException {
        Objects.requireNonNull(security, "security cannot be null");

        HttpsConfigurator tls = null;
        if (security.tls().isEnabled()) {
            try {
                tls = security.tls().configurator();
            } catch (GeneralSecurityException e) {
                throw new IOException("TLS is configured but could not be initialised: "
                    + e.getMessage(), e);
            }
        }

        InetSocketAddress bindTo = new InetSocketAddress(security.bindInetAddress(), port);
        LOGGER.info("Starting ExcaliburDB API server on {}://{}:{}",
            tls != null ? "https" : "http", security.bindAddress(), port);

        if (tls == null) {
            return HttpServer.create(bindTo, backlog);
        }
        HttpsServer https = HttpsServer.create(bindTo, backlog);
        https.setHttpsConfigurator(tls);
        return https;
    }
}
