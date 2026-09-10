package excalibur.api.endpoint;

import excalibur.api.security.IngestTargetValidator;
import excalibur.ingest.source.FilesystemSource;
import excalibur.ingest.source.HttpSource;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceDescriptor;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Turns a registration into the crawler it describes.
 *
 * <p>Two callers build sources: {@code POST /v1/ingest}, from the request a client just sent, and
 * startup, from the registrations a previous run recorded. They must build them the same way. The
 * allow-lists are the reason - a root or a host is checked here, on the way in, and a restart that
 * skipped the check would let a target the operator has since revoked back in through a file the
 * node wrote itself.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable and thread-safe; the validator it holds is too.</p>
 */
public final class SourceFactory {

    /** Configuration key naming a filesystem source's directory. */
    public static final String ROOT_PATH = "root_path";

    /** Configuration key naming an HTTP source's starting URL. */
    public static final String BASE_URL = "base_url";

    /** Configuration key listing the file extensions a filesystem crawl accepts. */
    public static final String INCLUDED_EXTENSIONS = "included_extensions";

    /** Configuration key listing the directory names a filesystem crawl skips. */
    public static final String EXCLUDED_DIRECTORIES = "excluded_directories";

    /** Source type naming a local directory. */
    public static final String TYPE_FILESYSTEM = "filesystem";

    /** Source type naming an HTTP(S) crawl. */
    public static final String TYPE_HTTP = "http";

    private final IngestTargetValidator targetValidator;

    /**
     * Creates a factory bound to an allow-list policy.
     *
     * @param targetValidator Validates the targets a registration names
     * @throws NullPointerException if the validator is null
     */
    public SourceFactory(IngestTargetValidator targetValidator) {
        this.targetValidator = Objects.requireNonNull(targetValidator,
            "targetValidator must not be null");
    }

    /**
     * Builds the source a registration describes.
     *
     * @param descriptor What the source was registered as
     * @return A crawler the caller owns and must close
     * @throws NullPointerException     if the descriptor is null
     * @throws excalibur.api.security.IngestTargetRejectedException if the target is not allow-listed
     * @throws IllegalArgumentException if the type is unknown or the configuration is incomplete
     */
    public Source create(SourceDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        return switch (descriptor.sourceType().toLowerCase(Locale.ROOT)) {
            case TYPE_FILESYSTEM -> createFilesystemSource(descriptor);
            case TYPE_HTTP -> createHttpSource(descriptor);
            default -> throw new IllegalArgumentException(
                "Unknown source type: " + descriptor.sourceType());
        };
    }

    /**
     * Builds a filesystem crawler.
     *
     * <p>The requested root is canonicalised and matched against the configured allow-list before
     * a crawler exists, and the crawler is given the resolved real path so it cannot be re-pointed
     * by a symlink swapped in afterwards.</p>
     */
    private FilesystemSource createFilesystemSource(SourceDescriptor descriptor) {
        Path rootPath = targetValidator.resolveFilesystemRoot(descriptor.get(ROOT_PATH));

        FilesystemSource.Builder builder = FilesystemSource.builder()
            .sourceId(descriptor.sourceId())
            .rootPath(rootPath);

        String extensions = descriptor.get(INCLUDED_EXTENSIONS);
        if (extensions != null && !extensions.isEmpty()) {
            builder.includedExtensions(extensions.split(","));
        }

        String excluded = descriptor.get(EXCLUDED_DIRECTORIES);
        if (excluded != null && !excluded.isEmpty()) {
            builder.excludedDirectories(excluded.split(","));
        }

        return builder.build();
    }

    /**
     * Builds an HTTP crawler.
     *
     * <p>The URL is checked against the host allow-list and every address the host resolves to
     * must be public, which is what keeps the crawler off cloud metadata endpoints and internal
     * services. The crawl is then pinned to that single host so a redirect cannot walk it
     * somewhere else: {@link HttpSource} follows redirects itself, with {@code Redirect.NEVER} on
     * its client, and re-applies its domain policy to every hop.</p>
     */
    private HttpSource createHttpSource(SourceDescriptor descriptor) {
        URI baseUrl = targetValidator.resolveUrl(descriptor.get(BASE_URL));

        return HttpSource.builder()
            .sourceId(descriptor.sourceId())
            .baseUrl(baseUrl)
            .allowedDomains(Set.of(IngestTargetValidator.hostOf(baseUrl)))
            .build();
    }
}
