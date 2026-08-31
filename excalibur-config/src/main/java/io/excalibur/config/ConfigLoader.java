package io.excalibur.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration loader that reads properties files and applies environment variable overrides.
 *
 * <h2>Thread-Safety</h2>
 * <p>This class is NOT thread-safe. Configuration loading is a blocking operation.</p>
 * <p>The resulting Config object is immutable and thread-safe.</p>
 *
 * <h2>Override Resolution</h2>
 * <p>Environment variables override properties file values. The following pattern is used:
 * <ul>
 *   <li>Property key: <code>excalibur.visibility.epoch-tick-ms</code></li>
 *   <li>Environment variable: <code>EXCALIBUR_VISIBILITY_EPOCH_TICK_MS</code></li>
 * </ul>
 * The key transformation replaces <code>-</code> with <code>_</code> and converts to uppercase.
 * The merge itself is performed by the {@link Config} constructor, so a Config built
 * without this loader resolves overrides the same way.</p>
 *
 * <h2>File Format</h2>
 * <p>One <code>key=value</code> pair per line. A line whose first non-blank character is
 * <code>#</code> or <code>;</code> is a comment. Inline trailing comments are not
 * supported: everything after the first <code>=</code> is part of the value.</p>
 *
 * <h2>Loading Strategy</h2>
 * <p>Configuration files are loaded in the following order:
 * <ol>
 *   <li>First, try to load from filesystem path (absolute or relative)</li>
 *   <li>If not found on filesystem, try to load from classpath</li>
 *   <li>If neither location has the file, throw IOException</li>
 * </ol>
 * </p>
 *
 * @author ExcaliburDB
 */
public final class ConfigLoader {

    /**
     * Loads configuration from a properties file with environment variable overrides.
     *
     * <p>The loader first attempts to read from the filesystem path. If the file
     * does not exist on the filesystem, it falls back to loading from the classpath.</p>
     *
     * @param configPath Path to the properties file (filesystem path or classpath resource)
     * @param envOverrides Additional environment variable overrides
     * @return Loaded configuration object
     * @throws IOException if the file cannot be read from filesystem or classpath
     * @throws IllegalStateException if the file format is invalid
     * @throws IllegalArgumentException if a property value cannot be parsed or violates
     *         a domain constraint
     */
    public static Config load(String configPath, Map<String, String> envOverrides) throws IOException {
        if (configPath == null || configPath.isEmpty()) {
            throw new IllegalArgumentException("Config path cannot be null or empty");
        }

        // Resolve path relative to current directory if not absolute
        Path configFilePath = Paths.get(configPath).toAbsolutePath();

        // Read properties from file or classpath
        Map<String, String> properties = new HashMap<>();

        InputStream inputStream;
        if (Files.exists(configFilePath)) {
            // Load from filesystem
            inputStream = Files.newInputStream(configFilePath);
        } else {
            // Fall back to classpath
            inputStream = ConfigLoader.class.getClassLoader().getResourceAsStream(configPath);
            if (inputStream == null) {
                throw new IOException("Configuration file not found: " + configPath +
                    " (tried filesystem: " + configFilePath + " and classpath)");
            }
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();

                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue;
                }

                // Parse key=value pairs. The offending line is reported by position
                // only: this file is the documented home of excalibur.llm.api-key, and
                // this exception reaches the startup log with its full stack trace.
                int equalsIndex = line.indexOf('=');
                if (equalsIndex <= 0) {
                    throw new IllegalStateException("Invalid property format at " + configPath +
                        ":" + lineNumber + " (expected key=value)");
                }

                String key = line.substring(0, equalsIndex).trim();
                String value = line.substring(equalsIndex + 1).trim();

                // Remove surrounding quotes from value
                if (value.length() >= 2 &&
                    ((value.startsWith("\"") && value.endsWith("\"")) ||
                     (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }

                properties.put(key, value);
            }
        }

        // The Config constructor overlays the environment onto the file properties,
        // so every construction path resolves overrides identically.
        return new Config(configPath, envOverrides, properties);
    }
}
