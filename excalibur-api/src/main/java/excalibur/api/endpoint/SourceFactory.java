package excalibur.api.endpoint;

import excalibur.api.security.IngestTargetValidator;
import excalibur.ingest.source.FilesystemSource;
import excalibur.ingest.source.HttpSource;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceDescriptor;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashSet;
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

    /** Configuration key bounding how far a crawl descends. Both source types honour it. */
    public static final String MAX_DEPTH = "max_depth";

    /** Configuration key allowing a filesystem crawl to descend through symbolic links. */
    public static final String FOLLOW_SYMLINKS = "follow_symlinks";

    /** Configuration key allowing a filesystem crawl to read dot-files. */
    public static final String INCLUDE_HIDDEN = "include_hidden";

    /** Configuration key bounding the size of one file a filesystem crawl will read. */
    public static final String MAX_FILE_BYTES = "max_file_bytes";

    /** Configuration key bounding how many pages one crawl fetches. */
    public static final String MAX_DOCUMENTS = "max_documents";

    /** Configuration key bounding the size of one HTTP response body. */
    public static final String MAX_RESPONSE_BYTES = "max_response_bytes";

    /** Configuration key bounding how long one HTTP request may take. */
    public static final String TIMEOUT_SECONDS = "timeout_seconds";

    /** Configuration key listing substrings a crawled URL path must contain. */
    public static final String INCLUDED_PATH_PATTERNS = "included_path_patterns";

    /** Configuration key listing substrings that keep a URL path out of a crawl. */
    public static final String EXCLUDED_PATH_PATTERNS = "excluded_path_patterns";

    /** Configuration key naming the User-Agent a crawl identifies itself with. */
    public static final String USER_AGENT = "user_agent";

    /** Deepest a crawl may be asked to descend. */
    public static final int MAX_CRAWL_DEPTH = 1000;

    /** Largest single file a filesystem crawl may be asked to read, 2 GiB. */
    public static final long FILE_BYTES_LIMIT = 2L * 1024 * 1024 * 1024;

    /** Most pages one crawl may be asked to fetch. */
    public static final int DOCUMENTS_LIMIT = 10_000_000;

    /** Largest single response body a crawl may be asked to hold, 256 MiB. */
    public static final long RESPONSE_BYTES_LIMIT = 256L * 1024 * 1024;

    /** Longest one HTTP request may be given, ten minutes. */
    public static final int TIMEOUT_SECONDS_LIMIT = 600;

    /** Longest User-Agent a caller may set. */
    public static final int USER_AGENT_LIMIT = 200;

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

        Set<String> extensions = listValue(descriptor, INCLUDED_EXTENSIONS);
        if (extensions != null) {
            builder.includedExtensions(extensions);
        }

        Set<String> excluded = listValue(descriptor, EXCLUDED_DIRECTORIES);
        if (excluded != null) {
            builder.excludedDirectories(excluded);
        }

        Integer depth = intValue(descriptor, MAX_DEPTH, 1, MAX_CRAWL_DEPTH);
        if (depth != null) {
            builder.maxDepth(depth);
        }
        Boolean followSymlinks = boolValue(descriptor, FOLLOW_SYMLINKS);
        if (followSymlinks != null) {
            builder.followSymlinks(followSymlinks);
        }
        Boolean includeHidden = boolValue(descriptor, INCLUDE_HIDDEN);
        if (includeHidden != null) {
            builder.includeHidden(includeHidden);
        }
        Long fileBytes = longValue(descriptor, MAX_FILE_BYTES, 1, FILE_BYTES_LIMIT);
        if (fileBytes != null) {
            builder.maxFileSize(fileBytes);
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

        HttpSource.Builder builder = HttpSource.builder()
            .sourceId(descriptor.sourceId())
            .baseUrl(baseUrl)
            .allowedDomains(Set.of(IngestTargetValidator.hostOf(baseUrl)));

        Integer depth = intValue(descriptor, MAX_DEPTH, 0, MAX_CRAWL_DEPTH);
        if (depth != null) {
            builder.maxDepth(depth);
        }
        Integer documents = intValue(descriptor, MAX_DOCUMENTS, 1, DOCUMENTS_LIMIT);
        if (documents != null) {
            builder.maxDocuments(documents);
        }
        Long responseBytes = longValue(descriptor, MAX_RESPONSE_BYTES, 1, RESPONSE_BYTES_LIMIT);
        if (responseBytes != null) {
            builder.maxResponseSize(responseBytes);
        }
        Integer timeout = intValue(descriptor, TIMEOUT_SECONDS, 1, TIMEOUT_SECONDS_LIMIT);
        if (timeout != null) {
            builder.timeoutSeconds(timeout);
        }
        Set<String> included = listValue(descriptor, INCLUDED_PATH_PATTERNS);
        if (included != null) {
            builder.includedPathPatterns(included);
        }
        Set<String> excluded = listValue(descriptor, EXCLUDED_PATH_PATTERNS);
        if (excluded != null) {
            builder.excludedPathPatterns(excluded);
        }
        String userAgent = descriptor.get(USER_AGENT);
        if (userAgent != null && !userAgent.isEmpty()) {
            builder.userAgent(requireHeaderSafe(userAgent));
        }

        return builder.build();
    }

    /**
     * Reads an optional whole-number setting, or null when the registration did not carry one.
     *
     * <p>The bounds are re-applied here and not only at the endpoint, because this factory is
     * also the path a registration takes when it is restored from the catalog at startup, and
     * a catalog is a file on disk that can be edited by hand.</p>
     */
    private static Integer intValue(SourceDescriptor descriptor, String key, int min, int max) {
        Long value = longValue(descriptor, key, min, max);
        return value == null ? null : value.intValue();
    }

    private static Long longValue(SourceDescriptor descriptor, String key, long min, long max) {
        String raw = descriptor.get(key);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Field 'config." + key + "' must be a whole number, got: " + raw, e);
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("Field 'config." + key + "' must be between "
                + min + " and " + max + ", got: " + value);
        }
        return value;
    }

    private static Boolean boolValue(SourceDescriptor descriptor, String key) {
        String raw = descriptor.get(key);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) {
            return Boolean.parseBoolean(raw);
        }
        throw new IllegalArgumentException(
            "Field 'config." + key + "' must be true or false, got: " + raw);
    }

    /**
     * Reads a comma-separated list, or null when the registration did not carry one.
     *
     * <p>An empty list is not the same as an absent one: absent leaves the crawler's own
     * default in place, and an empty list replaces it with nothing, which is how a caller
     * turns the default exclusions off deliberately. Every list field is read this way, so
     * {@code []} means the same thing whichever one it is sent for.</p>
     */
    private static Set<String> listValue(SourceDescriptor descriptor, String key) {
        String raw = descriptor.get(key);
        if (raw == null) {
            return null;
        }
        Set<String> values = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    /**
     * Refuses a User-Agent that could forge a header.
     *
     * <p>The value is written into a request header. A carriage return or newline in it would
     * end that header and begin one the caller chose, so the crawl would carry headers this
     * node never agreed to send.</p>
     */
    private static String requireHeaderSafe(String userAgent) {
        if (userAgent.length() > USER_AGENT_LIMIT) {
            throw new IllegalArgumentException("Field 'config." + USER_AGENT + "' must be at "
                + "most " + USER_AGENT_LIMIT + " characters, got: " + userAgent.length());
        }
        for (int i = 0; i < userAgent.length(); i++) {
            char c = userAgent.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException("Field 'config." + USER_AGENT + "' must not "
                    + "contain control characters: one of them would end the header and let "
                    + "the rest of the value become headers this node did not agree to send");
            }
        }
        return userAgent;
    }
}
