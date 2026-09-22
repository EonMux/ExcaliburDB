package com.eonmux.excalibur.core.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility class for file and path operations.
 *
 * <h2>Thread Safety</h2>
 * <p>All methods are thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public class PathUtils {

    private PathUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Normalizes and resolves a path to absolute form.
     *
     * @param path Path string
     * @return Normalized absolute path
     */
    public static Path normalize(String path) {
        return java.nio.file.Paths.get(path).normalize().toAbsolutePath();
    }

    /**
     * Normalizes and joins path components.
     *
     * @param pathComponents Path components to join
     * @return Normalized absolute path
     */
    public static Path normalize(String... pathComponents) {
        if (pathComponents == null || pathComponents.length == 0) {
            throw new IllegalArgumentException("Path components cannot be null or empty");
        }
        Path result = java.nio.file.Paths.get(pathComponents[0]);
        for (int i = 1; i < pathComponents.length; i++) {
            result = result.resolve(pathComponents[i]);
        }
        return result.normalize().toAbsolutePath();
    }

    /**
     * Checks if a path exists.
     *
     * @param path Path string
     * @return true if path exists, false otherwise
     */
    public static boolean exists(String path) {
        return Files.exists(normalize(path));
    }

    /**
     * Creates a file with the specified size.
     *
     * @param path Path for the file
     * @param size Initial size in bytes
     * @return The created file
     * @throws IOException if creation fails
     */
    public static File createFile(String path, long size) throws IOException {
        Path p = normalize(path);

        Path parent = p.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        // Create file with specified size using FileChannel.
        // Note: truncate() only shrinks; to extend, write a byte at the last position.
        try (FileOutputStream fos = new FileOutputStream(p.toFile())) {
            if (size > 0) {
                fos.getChannel().write(java.nio.ByteBuffer.allocate(1), size - 1);
            }
        }

        return p.toFile();
    }

    /**
     * Creates an output stream for a file, creating parent directories if needed.
     *
     * @param path Path for the file
     * @return FileOutputStream for writing
     * @throws IOException if creation fails
     */
    public static FileOutputStream createFileStream(String path) throws IOException {
        Path p = normalize(path);
        Path parent = p.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        return new FileOutputStream(p.toFile());
    }

    /**
     * Joins path components to a base path.
     *
     * @param base          Base path
     * @param pathComponents Additional components to join
     * @return Joined and normalized path
     */
    public static Path join(Path base, String... pathComponents) {
        if (base == null) {
            throw new IllegalArgumentException("Base path cannot be null");
        }
        Path result = base;
        for (String component : pathComponents) {
            result = result.resolve(component);
        }
        return result.normalize().toAbsolutePath();
    }

    /**
     * Joins path components.
     *
     * @param pathComponents Path components to join
     * @return Joined and normalized path
     */
    public static Path join(String... pathComponents) {
        return normalize(pathComponents);
    }

    /**
     * Converts a path to string.
     *
     * @param path Path to convert
     * @return String representation
     */
    public static String toString(Path path) {
        return path != null ? path.toString() : null;
    }
}
