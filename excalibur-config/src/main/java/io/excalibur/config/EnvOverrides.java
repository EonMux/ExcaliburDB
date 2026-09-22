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
 * <p>That rule alone is not enough, because a key's name is not always one dot deep.
 * <code>excalibur.query.cache.enabled</code> is three, and a rule that puts a dot only at the
 * first underscore turns <code>EXCALIBUR_QUERY_CACHE_ENABLED</code> into
 * <code>excalibur.query.cache-enabled</code> — a key nothing reads. The documented promise is
 * that <em>any</em> setting can be supplied from the environment, so the boundary is resolved
 * against {@link ConfigKeys} instead of assumed: each underscore in turn is tried as the last
 * dot, shallowest first, and the first spelling the inventory recognises wins. A name that
 * matches nothing keeps the shallow reading, which is what the unrecognised-key warning at
 * start-up is phrased for.</p>
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
    static String toPropertyKey(String envKey) {
        String[] words = envKey.substring(ENV_PREFIX.length()).toLowerCase(Locale.ROOT).split("_");
        if (words.length < 2) {
            // Nothing to place: there is no underscore for a dot to go at.
            return "excalibur." + String.join("", words);
        }

        // VISIBILITY_EPOCH_TICK_MS -> excalibur.visibility.epoch-tick-ms, the shallow reading,
        // and the one to keep when nothing deeper is recognised.
        for (int sections = 1; sections < words.length; sections++) {
            String candidate = spell(words, sections);
            if (ConfigKeys.isKnown(candidate)) {
                return candidate;
            }
        }
        return spell(words, 1);
    }

    /**
     * Spells the words as a key whose first few are dotted section names.
     *
     * @param words    The lower-cased words between the underscores
     * @param sections How many leading words name sections; the rest are one hyphenated name
     * @return The property key
     */
    private static String spell(String[] words, int sections) {
        StringBuilder key = new StringBuilder("excalibur");
        for (int i = 0; i < sections; i++) {
            key.append('.').append(words[i]);
        }
        for (int i = sections; i < words.length; i++) {
            key.append(i == sections ? '.' : '-').append(words[i]);
        }
        return key.toString();
    }
}
