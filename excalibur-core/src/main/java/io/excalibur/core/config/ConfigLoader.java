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
     * @param envOverrides Map of environment variable overrides in format "excalibur.key=value"
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
            cachedConfig = instance.load(basePath, envOverrides);
            return cachedConfig;
        }
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
