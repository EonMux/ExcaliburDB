package io.excalibur.config;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Merges <code>EXCALIBUR_*</code> environment variables into a properties map.
 *
 * <h2>Override Resolution</h2>
 * <p>Environment variables override properties file values, and may also set keys
 * that the file does not mention. The key transformation is:
 * <ul>
 *   <li>Environment variable: <code>EXCALIBUR_VISIBILITY_EPOCH_TICK_MS</code></li>
 *   <li>Property key: <code>excalibur.visibility.epoch-tick-ms</code></li>
 * </ul>
 * The first underscore after the prefix separates the section from the value name;
 * remaining underscores become hyphens.</p>
 *
 * <p>Variables without the <code>EXCALIBUR_</code> prefix are ignored so that the
 * ambient environment (HOME, PATH, LANG, ...) never pollutes the properties map.</p>
 *
 * @author ExcaliburDB
 */
final class EnvOverrides {

    private static final String ENV_PREFIX = "EXCALIBUR_";

    private EnvOverrides() {
        // Utility class
    }

    /**
     * Applies environment variable overrides on top of a base properties map.
     *
     * @param baseProperties Base properties (typically parsed from the config file)
     * @param envOverrides Environment variables, keyed by variable name
     * @return New map with the overrides applied; never the map that was passed in
     */
    static Map<String, String> apply(Map<String, String> baseProperties,
                                     Map<String, String> envOverrides) {
        Map<String, String> result = baseProperties != null
            ? new HashMap<>(baseProperties)
            : new HashMap<>();

        if (envOverrides == null) {
            return result;
        }

        for (Map.Entry<String, String> entry : envOverrides.entrySet()) {
            String envKey = entry.getKey();
            if (envKey != null && envKey.startsWith(ENV_PREFIX)) {
                result.put(toPropertyKey(envKey), entry.getValue());
            }
        }

        return result;
    }

    /**
     * Transforms an environment variable name into property key format.
     *
     * @param envKey Environment variable name, with the EXCALIBUR_ prefix
     * @return Property key in <code>excalibur.&lt;section&gt;.&lt;value-name&gt;</code> form
     */
    private static String toPropertyKey(String envKey) {
        String suffix = envKey.substring(ENV_PREFIX.length());

        // VISIBILITY_EPOCH_TICK_MS -> section=visibility, value-name=epoch-tick-ms
        int firstUnderscore = suffix.indexOf('_');
        if (firstUnderscore > 0) {
            String section = suffix.substring(0, firstUnderscore).toLowerCase(Locale.ROOT);
            String valueName = suffix.substring(firstUnderscore + 1)
                .replace('_', '-')
                .toLowerCase(Locale.ROOT);
            return "excalibur." + section + "." + valueName;
        }

        // Fallback for names with no underscore after the prefix
        return "excalibur." + suffix.toLowerCase(Locale.ROOT);
    }
}
