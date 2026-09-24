package com.eonmux.excalibur.api.security;

import com.eonmux.excalibur.ingest.source.PublicAddresses;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Validates the ingest targets a client may ask the server to read.
 *
 * <p>Both checks are allow-list based and both treat an empty allow-list as
 * "disabled", never as "unrestricted".</p>
 *
 * <h2>Filesystem targets</h2>
 * <p>The requested path is canonicalised with {@link Path#toRealPath} <em>first</em>
 * and only then compared against the configured roots, which were canonicalised the
 * same way. Comparing before canonicalisation is what lets {@code ..} segments and
 * symlinks walk straight out of an allowed root.</p>
 *
 * <h2>URL targets</h2>
 * <p>The scheme must be http or https, the host must be allow-listed, and every
 * address the host resolves to must be public by {@link PublicAddresses}'s rule: not
 * loopback, private, link-local, shared (carrier NAT), unique-local, multicast, reserved or
 * documentation space, and not an IPv6 form - mapped, compatible, NAT64 or 6to4 - carrying an
 * IPv4 address that is any of those. That is what keeps the crawler off cloud metadata
 * endpoints (169.254.169.254) and off internal services that are only reachable from the
 * server.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is immutable and thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public final class IngestTargetValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestTargetValidator.class);

    private static final String FILESYSTEM_DISABLED =
        "Filesystem ingest is disabled: no allowed roots are configured";
    private static final String PATH_REJECTED =
        "root_path is not inside an allowed ingest root";
    private static final String URL_DISABLED =
        "URL ingest is disabled: no allowed hosts are configured";
    private static final String URL_REJECTED =
        "base_url is not an allowed ingest target";

    private final ApiSecurityConfig config;

    /**
     * Creates a validator for the given policy.
     *
     * @param config The security policy holding both allow-lists
     */
    public IngestTargetValidator(ApiSecurityConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    /**
     * Resolves a client-supplied filesystem root against the allow-list.
     *
     * @param requestedPath The {@code root_path} from the request
     * @return The canonicalised, allowed path
     * @throws IngestTargetRejectedException if filesystem ingest is disabled or the
     *         path resolves outside every allowed root
     */
    public Path resolveFilesystemRoot(String requestedPath) {
        if (config.allowedIngestRoots().isEmpty()) {
            LOGGER.warn("Rejected filesystem ingest request: {} is empty",
                ApiSecurityConfig.KEY_ALLOWED_ROOTS);
            throw new IngestTargetRejectedException(FILESYSTEM_DISABLED);
        }
        if (requestedPath == null || requestedPath.isBlank()) {
            throw new IngestTargetRejectedException(PATH_REJECTED);
        }

        Path real;
        try {
            real = Path.of(requestedPath).toRealPath();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Rejected filesystem ingest request: path '{}' could not be resolved ({})",
                requestedPath, e.getClass().getSimpleName());
            throw new IngestTargetRejectedException(PATH_REJECTED);
        }

        for (Path root : config.allowedIngestRoots()) {
            if (real.startsWith(root)) {
                return real;
            }
        }
        LOGGER.warn("Rejected filesystem ingest request: resolved path {} is outside every allowed root",
            real);
        throw new IngestTargetRejectedException(PATH_REJECTED);
    }

    /**
     * Validates a client-supplied crawl URL against the allow-list and refuses any
     * host that resolves into the server's own network.
     *
     * @param requestedUrl The {@code base_url} from the request
     * @return The parsed URI
     * @throws IngestTargetRejectedException if URL ingest is disabled or the URL is
     *         not an allowed public target
     */
    public URI resolveUrl(String requestedUrl) {
        Set<String> allowedHosts = config.allowedIngestHosts();
        if (allowedHosts.isEmpty()) {
            LOGGER.warn("Rejected URL ingest request: {} is empty",
                ApiSecurityConfig.KEY_ALLOWED_HOSTS);
            throw new IngestTargetRejectedException(URL_DISABLED);
        }

        URI uri = parse(requestedUrl);
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : null;
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            LOGGER.warn("Rejected URL ingest request: scheme '{}' is not http or https", scheme);
            throw new IngestTargetRejectedException(URL_REJECTED);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            LOGGER.warn("Rejected URL ingest request: no host in '{}'", requestedUrl);
            throw new IngestTargetRejectedException(URL_REJECTED);
        }
        String normalisedHost = host.toLowerCase(Locale.ROOT);
        if (!allowedHosts.contains(normalisedHost)) {
            LOGGER.warn("Rejected URL ingest request: host {} is not allow-listed", normalisedHost);
            throw new IngestTargetRejectedException(URL_REJECTED);
        }

        requirePublicAddresses(normalisedHost);
        return uri;
    }

    /**
     * Returns the host of a URL that has already passed {@link #resolveUrl}.
     *
     * @param uri The validated URI
     * @return The lower-cased host
     */
    public static String hostOf(URI uri) {
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    private URI parse(String requestedUrl) {
        if (requestedUrl == null || requestedUrl.isBlank()) {
            throw new IngestTargetRejectedException(URL_REJECTED);
        }
        try {
            return new URI(requestedUrl.trim());
        } catch (URISyntaxException e) {
            LOGGER.warn("Rejected URL ingest request: '{}' is not a valid URI", requestedUrl);
            throw new IngestTargetRejectedException(URL_REJECTED);
        }
    }

    private void requirePublicAddresses(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            LOGGER.warn("Rejected URL ingest request: host {} does not resolve", host);
            throw new IngestTargetRejectedException(URL_REJECTED);
        }
        if (addresses.length == 0) {
            LOGGER.warn("Rejected URL ingest request: host {} resolved to no addresses", host);
            throw new IngestTargetRejectedException(URL_REJECTED);
        }
        for (InetAddress address : addresses) {
            if (!PublicAddresses.isPublic(address)) {
                LOGGER.warn("Rejected URL ingest request: host {} resolves to internal address {}",
                    host, address.getHostAddress());
                throw new IngestTargetRejectedException(URL_REJECTED);
            }
        }
    }
}
