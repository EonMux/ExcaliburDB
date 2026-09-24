package com.eonmux.excalibur.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <h2>An empty variable overrides nothing</h2>
 * <p>A variable that is set to nothing, or to whitespace, is skipped, and the file's value
 * stands; when it spells a key this module reads, a warning names it and the key. Copied through, it would replace that value with a blank, and a
 * blank reads as absent - so the shipped default would win over a file that said otherwise. The
 * usual source is a compose file interpolating a variable that is unset on the host:
 * <code>EXCALIBUR_API_AUTH_TOKEN: ${TOKEN}</code> arrives as an empty string, and copying it
 * would switch off the authentication the file turned on.</p>
 *
 * @author ExcaliburDB
 */
final class EnvOverrides {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnvOverrides.class);

    private static final String ENV_PREFIX = "EXCALIBUR_";

    /**
     * The prefix of the settings another module reads from the effective properties.
     *
     * <p>{@link ConfigKeys} does not list them, for the reason it gives, so their spelling cannot
     * be judged here. A variable under it is still the override of a setting, though - most
     * visibly <code>EXCALIBUR_API_AUTH_TOKEN</code> - and an empty one is an override being
     * ignored like any other.</p>
     */
    private static final String API_PREFIX = "excalibur.api.";

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

        for (String envKey : ignoredEmptyOverrides(envOverrides)) {
            LOGGER.warn("{} is set but empty; it is ignored, and {} keeps the value from the"
                + " configuration file or its default", envKey, toPropertyKey(envKey));
        }
        prefixed(envOverrides).forEach((envKey, value) -> {
            if (value != null && !value.isBlank()) {
                result.put(toPropertyKey(envKey), value);
            }
        });

        return result;
    }

    /**
     * The empty variables that would have overridden a key, in name order.
     *
     * <p>What {@link #apply} warns about. Only a variable that spells a setting is listed - a key
     * this module reads, or one under {@link #API_PREFIX} - since the warning says that key keeps
     * its value, and for any other variable there is no key to name. A variable read by name rather than as an override -
     * <code>EXCALIBUR_DATA_DIR</code>, <code>EXCALIBUR_PORT</code>, the MCP client's
     * <code>EXCALIBUR_URL</code> - is answered for by whatever reads it, which treats a blank
     * value as unset; a blank variable that spells no key at all overrides nothing either way,
     * and set to a value it would be reported by {@link #unrecognisedVariables}.</p>
     *
     * @param environment Environment variables, keyed by variable name
     * @return The names of the empty variables that spell a setting
     */
    static List<String> ignoredEmptyOverrides(Map<String, String> environment) {
        List<String> ignored = new ArrayList<>();
        prefixed(environment).forEach((name, value) -> {
            String key = toPropertyKey(name);
            if ((value == null || value.isBlank())
                    && (ConfigKeys.isKnown(key) || key.startsWith(API_PREFIX))) {
                ignored.add(name);
            }
        });
        return ignored;
    }

    /**
     * The variables that carry the <code>EXCALIBUR_</code> prefix, in name order.
     *
     * @param environment Environment variables, keyed by variable name
     * @return Those whose name starts with the prefix; never the map that was passed in
     */
    static SortedMap<String, String> prefixed(Map<String, String> environment) {
        SortedMap<String, String> prefixed = new TreeMap<>();
        environment.forEach((name, value) -> {
            if (name != null && name.startsWith(ENV_PREFIX)) {
                prefixed.put(name, value);
            }
        });
        return prefixed;
    }

    /**
     * The keys the environment sets: those of every <code>EXCALIBUR_*</code> variable that is
     * not empty, and so overrides the file.
     *
     * @param environment Environment variables, keyed by variable name
     * @return The keys, whether or not anything reads them
     */
    static Set<String> overriddenKeys(Map<String, String> environment) {
        Set<String> keys = new HashSet<>();
        prefixed(environment).forEach((name, value) -> {
            if (value != null && !value.isBlank()) {
                keys.add(toPropertyKey(name));
            }
        });
        return keys;
    }

    /**
     * The variables that would set a key no section reads, in name order.
     *
     * <p>Judged the way {@link ConfigKeys} judges a key in a file: only a variable that lands
     * under a section this module parses can be called a typo. That is also what leaves alone
     * the variables read directly rather than as the override of a key -
     * <code>EXCALIBUR_PORT</code>, <code>EXCALIBUR_DATA_DIR</code>,
     * <code>EXCALIBUR_LOG_LEVEL</code>, the MCP client's <code>EXCALIBUR_URL</code>,
     * <code>EXCALIBUR_NAMESPACE</code> and <code>EXCALIBUR_MCP_TIMEOUT_SECONDS</code>, and the
     * container health check's <code>EXCALIBUR_HEALTHCHECK_*</code> - none of which spells a key
     * under such a section. An empty variable is not listed: it overrides nothing, and
     * {@link #apply} has already said so.</p>
     *
     * @param environment Environment variables, keyed by variable name
     * @return The names of the variables that set nothing
     */
    static List<String> unrecognisedVariables(Map<String, String> environment) {
        List<String> unrecognised = new ArrayList<>();
        prefixed(environment).forEach((name, value) -> {
            String key = toPropertyKey(name);
            if (value != null && !value.isBlank()
                    && ConfigKeys.isOwned(key) && !ConfigKeys.isKnown(key)) {
                unrecognised.add(name);
            }
        });
        return unrecognised;
    }

    /**
     * Spells a property key as the environment variable that overrides it.
     *
     * @param key A property key, <code>excalibur.</code> and all
     * @return The variable, such as <code>EXCALIBUR_QUERY_CACHE_MAX_ENTRIES</code>
     */
    static String toVariableName(String key) {
        return ENV_PREFIX + key.substring("excalibur.".length())
            .replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
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
