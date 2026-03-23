package io.excalibur.config;

import java.io.BufferedReader;
import java.io.IOException;
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
 * The key transformation replaces <code>-</code> with <code>_</code> and converts to uppercase.</p>
 *
 * @author ExcaliburDB
 */
public final class ConfigLoader {

    /**
     * Loads configuration from a properties file with environment variable overrides.
     *
     * @param configPath Path to the properties file
     * @param envOverrides Additional environment variable overrides
     * @return Loaded configuration object
     * @throws IOException if the file cannot be read
     * @throws IllegalStateException if the file format is invalid
     */
    public static Config load(String configPath, Map<String, String> envOverrides) throws IOException {
        if (configPath == null || configPath.isEmpty()) {
            throw new IllegalArgumentException("Config path cannot be null or empty");
        }

        // Resolve path relative to current directory if not absolute
        Path configFilePath = Paths.get(configPath).toAbsolutePath();

        // Read properties from file
        Map<String, String> properties = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(configFilePath)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue;
                }

                // Parse key=value pairs
                int equalsIndex = line.indexOf('=');
                if (equalsIndex <= 0) {
                    throw new IllegalStateException("Invalid property format: " + line);
                }

                String key = line.substring(0, equalsIndex).trim();
                String value = line.substring(equalsIndex + 1).trim();

                // Remove surrounding quotes from value
                if (value.length() >= 2 &&
                    (value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }

                properties.put(key, value);
            }
        }

        // Apply environment variable overrides
        Map<String, String> effectiveProperties = applyEnvOverrides(properties, envOverrides);

        // Create immutable config instance with the effective properties
        return new Config(configPath, envOverrides, effectiveProperties);
    }

    /**
     * Applies environment variable overrides to configuration overrides.
     *
     * <p>Environment variables can set values even for keys not in the properties file.</p>
     *
     * @param baseOverrides Base overrides (from properties file)
     * @param envOverrides Additional environment variable overrides
     * @return Merged overrides with env vars taking precedence
     */
    private static Map<String, String> applyEnvOverrides(Map<String, String> baseOverrides,
                                                          Map<String, String> envOverrides) {
        Map<String, String> result = new HashMap<>(baseOverrides);

        if (envOverrides != null) {
            for (Map.Entry<String, String> entry : envOverrides.entrySet()) {
                String propKey = transformEnvKey(entry.getKey());
                // Always apply override, even for keys not in properties file
                result.put(propKey, entry.getValue());
            }
        }

        return result;
    }

    /**
     * Transforms environment variable name to property key format.
     *
     * <p>E.g., <code>EXCALIBUR_VISIBILITY_EPOCH_TICK_MS</code> -> <code>excalibur.visibility.epoch-tick-ms</code></p>
     * <p>The property key format is: excalibur.&lt;section&gt;.&lt;value-name&gt;</p>
     * <p>where section is a single word and value-name uses hyphens.</p>
     *
     * @param envKey Environment variable key
     * @return Property key format
     */
    private static String transformEnvKey(String envKey) {
        // EXCALIBUR_VISIBILITY_EPOCH_TICK_MS -> excalibur.visibility.epoch-tick-ms
        if (envKey.startsWith("EXCALIBUR_")) {
            envKey = envKey.substring(10);  // "EXCALIBUR_" is 10 characters
        }

        // Split into section and value name
        // VISIBILITY_EPOCH_TICK_MS -> section=VISIBILITY, value=EPOCH_TICK_MS
        int firstUnderscore = envKey.indexOf('_');
        if (firstUnderscore > 0) {
            String section = envKey.substring(0, firstUnderscore).toLowerCase();
            String valueName = envKey.substring(firstUnderscore + 1).replace("_", "-").toLowerCase();
            return "excalibur." + section + "." + valueName;
        }

        // Fallback for keys without underscores after prefix
        return "excalibur." + envKey.toLowerCase();
    }
}
