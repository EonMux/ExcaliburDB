package com.eonmux.excalibur.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>The path is read from the filesystem, absolute or relative to the working directory, and
 * from nowhere else. There is no classpath lookup: a name that resolved to a copy packaged in
 * the jar whenever the file was missing would turn a mistyped path into a node running on a
 * file the operator never wrote, with nothing to say so. A file that is absent is reported as
 * {@link NoSuchFileException}, distinct from one that is present and cannot be read, so a
 * caller can tell "there is no file" from "there is a file and it is not this node's".</p>
 *
 * @author ExcaliburDB
 */
public final class ConfigLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigLoader.class);

    /**
     * Loads configuration from a properties file with environment variable overrides.
     *
     * @param configPath Path to the properties file, absolute or relative to the working directory
     * @param envOverrides Additional environment variable overrides
     * @return Loaded configuration object
     * @throws NoSuchFileException if there is no file at the path
     * @throws IOException if the file is there and cannot be read
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

        Map<String, String> properties = new HashMap<>();

        // notExists rather than !exists: a file whose existence cannot be determined - a directory
        // this process may not search - is reported by the read below as the failure it is, not
        // mistaken for the absence of a file.
        if (Files.notExists(configFilePath)) {
            throw new NoSuchFileException(configFilePath.toString(), null,
                "Configuration file not found: " + configPath);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Files.newInputStream(configFilePath), StandardCharsets.UTF_8))) {
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
        Config config = new Config(configPath, envOverrides, properties);

        // The file and the environment separately, so each warning names what the operator
        // typed: the same typo is as easy to make in a deployment manifest as in a properties
        // file, and a key that arrived as EXCALIBUR_INGEST_CHUNK_TARGET_TOKNS is exactly as
        // silently ignored - but it is fixed in the manifest, under its variable's name.
        reportUnrecognisedKeys(configPath, properties);
        reportUnrecognisedVariables(envOverrides);
        return config;
    }

    /**
     * Warns about an <code>EXCALIBUR_*</code> variable that would set a key no setting answers to.
     *
     * <p>The environment's half of {@link #reportUnrecognisedKeys}, for every path that applies
     * one: the server's, whether or not it found a file, and an embedded node's. The warning names
     * the variable and, where one is close, the variable that was probably meant.</p>
     *
     * @param environment Environment variables, keyed by variable name; null is no environment
     */
    public static void reportUnrecognisedVariables(Map<String, String> environment) {
        if (environment == null) {
            return;
        }
        for (String variable : EnvOverrides.unrecognisedVariables(environment)) {
            String nearest = ConfigKeys.nearest(EnvOverrides.toPropertyKey(variable));
            LOGGER.warn("No setting is named by the environment variable {}; it is being ignored{}",
                variable,
                nearest == null ? "" : ". Did you mean " + EnvOverrides.toVariableName(nearest) + "?");
        }
    }

    /**
     * Warns about a key in a section this module parses that no setting answers to.
     *
     * <p>Such a key is not an error, it is silence: it is parsed, stored, and never consulted,
     * and the node starts on the shipped default with nothing anywhere saying the setting did
     * not take. A deployment can run for a year on a queue limit it believes it changed.</p>
     *
     * @param configPath Where the properties came from, for the message
     * @param properties The settings read from there
     */
    public static void reportUnrecognisedKeys(String configPath, Map<String, String> properties) {
        for (String key : properties.keySet()) {
            if (!ConfigKeys.isOwned(key) || ConfigKeys.isKnown(key)) {
                continue;
            }
            String nearest = ConfigKeys.nearest(key);
            LOGGER.warn("No setting named {} in the configuration for {}; it is being ignored{}",
                key, configPath,
                nearest == null ? "" : ". Did you mean " + nearest + "?");
        }
    }
}
