package com.eonmux.excalibur.ocr.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A directory of pinned model files, filled from their source the first time they are needed.
 *
 * <h2>What is trusted</h2>
 * <p>A file is used only when its length and SHA-256 digest are the pinned ones (see
 * {@link ModelFile}), whether it was downloaded here, copied in by an operator, or is a link into
 * another tool's cache. Each file is checked once per process, the first time it is asked for.
 * A file that fails the check is downloaded again when downloads are allowed, and refused when
 * they are not.</p>
 *
 * <h2>How a file is downloaded</h2>
 * <p>Into a temporary file beside its final name, which is checked and then moved into place in
 * one step, so the directory never holds a partial or unverified file under a model's name. A
 * process that downloads holds a lock file in the directory while it does, so two nodes sharing
 * one directory download each file once; threads of one process take turns.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Thread-safe.</p>
 */
public final class ModelStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelStore.class);

    /** The longest one file may take to download: about 10 KB/s for the largest. */
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(30);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private static final String LOCK_FILE = ".download.lock";

    /** One monitor per directory, so two stores over the same directory take turns. */
    private static final ConcurrentHashMap<Path, Object> MONITORS = new ConcurrentHashMap<>();

    private final Path directory;
    private final URI source;
    private final HttpClient client;
    private final Set<ModelFile> verified = ConcurrentHashMap.newKeySet();

    private ModelStore(Path directory, URI source) {
        this.directory = directory.toAbsolutePath().normalize();
        this.source = source;
        this.client = source == null ? null : HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(ProxySelector.getDefault())
            .build();
    }

    /**
     * A store that downloads what it lacks from the pinned revision's repository.
     *
     * @param directory Where the files are kept; created when first needed
     * @return The store
     */
    public static ModelStore downloading(Path directory) {
        return downloading(directory, IndraModels.SOURCE);
    }

    /**
     * A store that downloads what it lacks from another copy of the repository's files, such as
     * a mirror. What it delivers is checked against the same digests.
     *
     * @param directory Where the files are kept; created when first needed
     * @param source    The URL the file names are resolved against, ending in a slash
     * @return The store
     * @throws IllegalArgumentException if the source is not an http or https URL ending in "/"
     */
    public static ModelStore downloading(Path directory, URI source) {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(source, "source must not be null");
        String scheme = source.getScheme();
        if (!("https".equals(scheme) || "http".equals(scheme)) || !source.getPath().endsWith("/")) {
            throw new IllegalArgumentException("the source is an http(s) URL ending in '/', got: "
                + source);
        }
        return new ModelStore(directory, source);
    }

    /**
     * A store that only reads what an operator put in the directory, and never downloads.
     *
     * @param directory Where the files are
     * @return The store
     */
    public static ModelStore local(Path directory) {
        return new ModelStore(Objects.requireNonNull(directory, "directory must not be null"),
            null);
    }

    /**
     * Where a node keeps the models when no directory is configured: {@code excalibur/indra-ocr/}
     * and the revision under {@code $XDG_CACHE_HOME}, or under {@code ~/.cache} when that is not
     * set. A cache, not the data directory: the models are not the node's data, and need not be
     * backed up or snapshotted with it.
     *
     * @return The directory
     */
    public static Path defaultDirectory() {
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path cache = xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute() ? Path.of(xdg)
            : Path.of(System.getProperty("user.home"), ".cache");
        return cache.resolve("excalibur").resolve("indra-ocr").resolve(IndraModels.REVISION);
    }

    /**
     * The directory the files are kept in.
     *
     * @return The absolute directory
     */
    public Path directory() {
        return directory;
    }

    /**
     * Whether files that are missing are downloaded.
     *
     * @return true for a store that downloads
     */
    public boolean downloads() {
        return source != null;
    }

    /**
     * The files that are not in the directory at their pinned length; a quick look that reads no
     * file, for reporting at startup what the first conversion will have to fetch.
     *
     * @param files The files wanted
     * @return Those absent or of another length, in the given order
     */
    public List<ModelFile> missing(List<ModelFile> files) {
        List<ModelFile> missing = new ArrayList<>();
        for (ModelFile file : files) {
            Path path = directory.resolve(file.name());
            try {
                if (!Files.isRegularFile(path) || Files.size(path) != file.size()) {
                    missing.add(file);
                }
            } catch (IOException e) {
                missing.add(file);
            }
        }
        return missing;
    }

    /**
     * Makes sure every file is in the directory and is the pinned one, downloading those that
     * are not when this store downloads.
     *
     * @param files The files wanted
     * @return The directory, holding every file
     * @throws ModelUnavailableException if a file is missing or wrong and cannot be downloaded
     * @throws InterruptedException      if the thread is interrupted while waiting for another
     *                                   download or while downloading
     */
    public Path ensure(List<ModelFile> files) throws ModelUnavailableException,
            InterruptedException {
        Objects.requireNonNull(files, "files must not be null");
        if (verified.containsAll(files)) {
            return directory;
        }
        synchronized (MONITORS.computeIfAbsent(directory, d -> new Object())) {
            List<ModelFile> invalid = unverified(files);
            if (invalid.isEmpty()) {
                return directory;
            }
            if (source == null) {
                throw new ModelUnavailableException("The model directory " + directory
                    + " lacks " + names(invalid) + " of " + IndraModels.REPOSITORY + " "
                    + IndraModels.REVISION + " (or holds other files under those names), and "
                    + "downloading them is turned off");
            }
            downloadUnderLock(invalid);
        }
        return directory;
    }

    /** The files not yet verified that fail verification now; those that pass are recorded. */
    private List<ModelFile> unverified(List<ModelFile> files) throws ModelUnavailableException {
        List<ModelFile> invalid = new ArrayList<>();
        for (ModelFile file : files) {
            if (verified.contains(file)) {
                continue;
            }
            if (matches(directory.resolve(file.name()), file)) {
                verified.add(file);
            } else {
                invalid.add(file);
            }
        }
        return invalid;
    }

    private void downloadUnderLock(List<ModelFile> invalid) throws ModelUnavailableException,
            InterruptedException {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new ModelUnavailableException("Could not create the model directory "
                + directory + ": " + e.getMessage(), e);
        }
        try (FileChannel channel = FileChannel.open(directory.resolve(LOCK_FILE),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            // Another process may have downloaded them while this one waited for the lock.
            List<ModelFile> still = unverified(invalid);
            if (still.isEmpty()) {
                return;
            }
            announce(still);
            for (ModelFile file : still) {
                download(file);
                verified.add(file);
            }
            LOGGER.info("Indra OCR's models are in {}", directory);
        } catch (java.nio.channels.FileLockInterruptionException
                 | java.nio.channels.ClosedByInterruptException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedException("Interrupted waiting to download models into "
                + directory);
        } catch (ModelUnavailableException e) {
            throw e;
        } catch (IOException e) {
            throw new ModelUnavailableException("Could not lock the model directory " + directory
                + " to download into it: " + e.getMessage(), e);
        }
    }

    private void announce(List<ModelFile> files) {
        long bytes = files.stream().mapToLong(ModelFile::size).sum();
        LOGGER.warn("Downloading {} of Indra OCR's model files ({} MB) from {} into {}. The "
                + "models are published under the {}, not under ExcaliburDB's Apache License: its "
                + "terms bind whoever uses them. Its text is saved beside them as {}",
            files.size(), (bytes + 999_999) / 1_000_000, source, directory,
            IndraModels.LICENSE_NAME, IndraModels.LICENSE_FILE);
    }

    private void download(ModelFile file) throws ModelUnavailableException,
            InterruptedException {
        URI url = source.resolve(file.name());
        Path temporary;
        try {
            temporary = Files.createTempFile(directory, "." + file.name() + ".", ".part");
        } catch (IOException e) {
            throw new ModelUnavailableException("Could not write into the model directory "
                + directory + ": " + e.getMessage(), e);
        }
        try {
            fetch(url, temporary, file);
            if (!matches(temporary, file)) {
                throw new ModelUnavailableException(url + " delivered other bytes than "
                    + file.name() + " of " + IndraModels.REVISION + " (" + describe(temporary)
                    + ", expected " + file.size() + " bytes with SHA-256 " + file.sha256() + ")");
            }
            Files.move(temporary, directory.resolve(file.name()),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            if (e instanceof ModelUnavailableException unavailable) {
                throw unavailable;
            }
            throw new ModelUnavailableException("Could not save " + file.name() + " in "
                + directory + ": " + e.getMessage(), e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException e) {
                LOGGER.warn("Could not delete the partial download {}: {}", temporary,
                    e.getMessage());
            }
        }
    }

    private void fetch(URI url, Path target, ModelFile file) throws ModelUnavailableException,
            InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(url)
            .timeout(CONNECT_TIMEOUT.multipliedBy(2))
            .header("User-Agent", "ExcaliburDB")
            .GET()
            .build();
        CompletableFuture<HttpResponse<Path>> pending = client.sendAsync(request,
            HttpResponse.BodyHandlers.ofFile(target));
        HttpResponse<Path> response;
        try {
            response = pending.get(DOWNLOAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new ModelUnavailableException("Downloading " + url + " took longer than "
                + DOWNLOAD_TIMEOUT.toMinutes() + " minutes", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ModelUnavailableException("Could not download " + url + ": "
                + cause.getClass().getSimpleName() + ": " + cause.getMessage()
                + ". Check the network, or put the files of " + IndraModels.REPOSITORY + " "
                + IndraModels.REVISION + " in " + directory + " yourself", cause);
        }
        if (response.statusCode() != 200) {
            throw new ModelUnavailableException("Downloading " + file.name() + " from " + url
                + " answered HTTP " + response.statusCode());
        }
    }

    /** Whether a file has the pinned length and digest. */
    private static boolean matches(Path path, ModelFile file) throws ModelUnavailableException {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) != file.size()) {
                return false;
            }
            return MessageDigest.isEqual(sha256(path), file.digest());
        } catch (IOException e) {
            throw new ModelUnavailableException("Could not read " + path + ": " + e.getMessage(),
                e);
        }
    }

    private static String describe(Path path) {
        try {
            return Files.size(path) + " bytes with SHA-256 "
                + HexFormat.of().formatHex(sha256(path));
        } catch (IOException e) {
            return "unreadable: " + e.getMessage();
        }
    }

    private static byte[] sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform provides SHA-256", e);
        }
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(path)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        return digest.digest();
    }

    private static String names(List<ModelFile> files) {
        return String.join(", ", files.stream().map(ModelFile::name).toList());
    }
}
