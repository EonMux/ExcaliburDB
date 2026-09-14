package excalibur.api.security;

import io.excalibur.config.ConfigKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@code excalibur.api.} settings, and what to say about one nobody declared.
 *
 * <h2>Why this is not in the config module</h2>
 * <p>{@link ConfigKeys} reports an unrecognised key under any prefix that module parses, and
 * deliberately does not claim {@code excalibur.api.}: nothing there reads those keys, so it
 * cannot tell a typo from a setting another module answers to. This module is that other module,
 * and these are the keys it answers to - the rate limits, the request bounds and the TLS
 * settings.</p>
 *
 * <h2>Why a typo here is worth a line in the log</h2>
 * <p>These are the node's security policy. {@code excalibur.api.mutate-rate-per-secnod=1} is
 * parsed, stored, and never consulted; the node starts on the shipped limit and nothing anywhere
 * says the setting did not take, so an operator who set out to slow writes to a trickle is
 * serving five a second and believes otherwise. A warning rather than a refusal, for the reason
 * {@code ConfigKeys} already gives: the same file is reused across versions, and refusing a key
 * a newer build reads would turn a rollback into an outage.</p>
 *
 * <h2>Why the inventory is reflective</h2>
 * <p>A written-out list of keys beside the constants that declare them is a second copy, and the
 * second copy is the one that goes stale. Reading the {@code KEY_*} constants means a key cannot
 * be added without being recognised.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable and thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public final class ApiConfigKeys {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiConfigKeys.class);

    /** The prefix this module answers for. */
    private static final String PREFIX = "excalibur.api.";

    /** Every {@code excalibur.api.} key the two policy classes read. */
    private static final Set<String> KNOWN = declaredKeys();

    private ApiConfigKeys() {
    }

    /**
     * Warns about an {@code excalibur.api.} key no setting answers to.
     *
     * @param properties The effective settings, file and environment overlaid
     * @throws NullPointerException if properties is null
     */
    public static void reportUnrecognised(Map<String, String> properties) {
        for (String key : new TreeSet<>(properties.keySet())) {
            if (!key.startsWith(PREFIX) || KNOWN.contains(key)) {
                continue;
            }
            String nearest = nearest(key);
            LOGGER.warn("No setting named {}; it is being ignored{}", key,
                nearest == null ? "" : ". Did you mean " + nearest + "?");
        }
    }

    /**
     * Whether this module reads the key.
     *
     * @param key A property key
     * @return true if it is one of the {@code excalibur.api.} settings
     */
    public static boolean isKnown(String key) {
        return KNOWN.contains(key);
    }

    /** The nearest known key, by the same measure {@link ConfigKeys} uses for its own. */
    private static String nearest(String key) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : KNOWN) {
            int distance = ConfigKeys.editDistance(key, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return bestDistance <= Math.max(3, key.length() / 3) ? best : null;
    }

    /** The {@code public static final String KEY_*} constants of the two policy classes. */
    private static Set<String> declaredKeys() {
        Set<String> keys = new TreeSet<>();
        for (Class<?> type : new Class<?>[]{ApiSecurityConfig.class, TlsConfig.class}) {
            for (Field field : type.getDeclaredFields()) {
                if (isPropertyKey(field)) {
                    keys.add(read(field));
                }
            }
        }
        return Set.copyOf(keys);
    }

    private static boolean isPropertyKey(Field field) {
        int modifiers = field.getModifiers();
        return Modifier.isPublic(modifiers)
            && Modifier.isStatic(modifiers)
            && Modifier.isFinal(modifiers)
            && field.getType() == String.class
            && field.getName().startsWith("KEY_");
    }

    private static String read(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("could not read " + field.getName(), e);
        }
    }
}
