package io.excalibur.core.config;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

/**
 * Lightweight configuration loader for ExcaliburDB.
 * 
 * Thread-safety: This class is thread-safe. Configuration is loaded once
 * at startup and cached. After loading, all methods are immutable operations.
 * 
 * @see Config
 */
public final class ConfigLoader {
    
    private static ConfigLoader instance;
    private static Config cachedConfig;
    
    private ConfigLoader() {
        // Singleton - only one instance
    }
    
    /**
     * Loads configuration from properties file with environment variable overrides.
     *
     * @param basePath Path to properties file in classpath (default: "excalibur.properties")
     * @param envOverrides Map of environment variable overrides in format "EXCALIBUR_KEY=value"
     * @return Loaded and validated Config object
     *
     * @throws IllegalStateException if configuration is invalid or missing required fields
     */
    public static Config load(String basePath, Map<String, String> envOverrides) {
        synchronized(ConfigLoader.class) {
            if (cachedConfig != null) {
                return cachedConfig;
            }

            instance = new ConfigLoader();
            cachedConfig = instance.doLoad(basePath, envOverrides);
            return cachedConfig;
        }
    }

    /**
     * Internal method that performs the actual configuration loading.
     *
     * @param basePath Path to properties file in classpath
     * @param envOverrides Map of environment variable overrides
     * @return Loaded and validated Config object
     */
    private Config doLoad(String basePath, Map<String, String> envOverrides) {
        try {
            InputStream stream = ConfigLoader.class.getClassLoader()
                .getResourceAsStream(basePath);
            if (stream == null) {
                throw new IllegalStateException("Properties file not found: " + basePath);
            }

            PropertiesLoader loader = new PropertiesLoader(stream);
            Map<String, String> props = loader.load();

            // Apply environment variable overrides
            applyEnvOverrides(props, envOverrides);

            return new Config(props);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load configuration", e);
        }
    }

    /**
     * Applies environment variable overrides to the properties map.
     *
     * <p>Environment variables follow the pattern:
     * EXCALIBUR_VISIBILITY_EPOCH_TICK_MS -> excalibur.visibility.epoch-tick-ms</p>
     *
     * @param props The properties map to modify
     * @param envOverrides Map of environment variable overrides
     */
    private void applyEnvOverrides(Map<String, String> props, Map<String, String> envOverrides) {
        if (envOverrides == null) {
            return;
        }
        for (Map.Entry<String, String> entry : envOverrides.entrySet()) {
            String envKey = entry.getKey();
            String propKey = transformEnvKey(envKey);
            // Always apply override, even for keys not in properties file
            props.put(propKey, entry.getValue());
        }
    }

    /**
     * Transforms an environment variable key to a property key.
     *
     * <p>Example: EXCALIBUR_VISIBILITY_EPOCH_TICK_MS -> excalibur.visibility.epoch-tick-ms</p>
     * <p>The property key format is: excalibur.&lt;section&gt;.&lt;value-name&gt;</p>
     * <p>where section is a single word and value-name uses hyphens.</p>
     *
     * @param envKey The environment variable key
     * @return The corresponding property key
     */
    private String transformEnvKey(String envKey) {
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
    
    /**
     * Loads default configuration with all required fields.
     * 
     * @return Default Config object
     */
    public static Config loadDefaults() {
        try {
            InputStream stream = ConfigLoader.class.getClassLoader()
                .getResourceAsStream("excalibur.properties.default");
            if (stream == null) {
                throw new IllegalStateException("Default properties file not found");
            }
            
            PropertiesLoader loader = new PropertiesLoader(stream);
            Map<String, String> props = loader.load();
            return new Config(props);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load default configuration", e);
        }
    }
    
    /**
     * Returns the singleton instance.
     */
    public static ConfigLoader getInstance() {
        return instance;
    }
    
    /**
     * Clears cached configuration (useful for testing).
     */
    public static void clearCache() {
        synchronized(ConfigLoader.class) {
            cachedConfig = null;
            instance = null;
        }
    }
    
    private static class PropertiesLoader {
        private final InputStream stream;
        
        PropertiesLoader(InputStream stream) {
            this.stream = stream;
        }
        
        Map<String, String> load() throws IllegalStateException {
            Map<String, String> props = new HashMap<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            String line;

            try {
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    // Skip empty lines and comments
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }

                    int eqIndex = line.indexOf('=');
                    if (eqIndex <= 0) {
                        continue;
                    }

                    String key = line.substring(0, eqIndex).trim();
                    String value = line.substring(eqIndex + 1).trim();

                    // Remove quotes if present
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }

                    props.put(key, value);
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Failed to read properties", e);
            } finally {
                try {
                    reader.close();
                } catch (java.io.IOException ignored) {}
                try {
                    stream.close();
                } catch (java.io.IOException ignored) {}
            }

            return props;
        }
    }
}
