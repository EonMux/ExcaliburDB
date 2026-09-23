package com.eonmux.excalibur.api.bootstrap;

import com.eonmux.excalibur.api.security.ApiSecurityConfig;
import com.eonmux.excalibur.api.security.TlsConfig;
import com.eonmux.excalibur.api.server.ListenPort;
import com.eonmux.excalibur.api.server.ShutdownBudget;
import com.eonmux.excalibur.config.ConfigKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
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
 * and these are the keys it answers to - the listening port, the rate limits, the request bounds
 * and the TLS settings.</p>
 *
 * <h2>Why it sits in this package</h2>
 * <p>The keys are declared across the module rather than in one corner of it, and an inventory
 * that can only see one corner is worse than none: it reports every key it cannot see as a
 * setting being ignored. This lived beside the security policy and reflected only the two policy
 * classes, so the documented first run - copy the template, which sets
 * {@code excalibur.api.port} - warned that the port was being ignored while the node was
 * listening on it. Bootstrap is the one package that already sees the whole module, so the
 * inventory can be complete from here and cannot be complete from there.</p>
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

    /** The prefixes this module answers for. */
    private static final List<String> PREFIXES = List.of("excalibur.api.");

    /**
     * The prefix of the settings that sized the term filter the query pipeline no longer has.
     *
     * <p>A file written for an earlier build still carries them, and nothing reads them any
     * more. Each is named once at startup so an operator knows the line can go, rather than
     * being left to wonder whether a node that ignores it is misconfigured.</p>
     */
    private static final String RETIRED_PREFIX = "excalibur.filter.";

    /**
     * Every class in this module that declares a property key constant.
     *
     * <p>Adding a key to one of these is enough to have it recognised; adding one to a class
     * that is not here is the drift this inventory exists to prevent, which is why a test holds
     * the list against the template an operator copies.</p>
     */
    private static final Class<?>[] DECLARING =
        {ApiSecurityConfig.class, TlsConfig.class, ListenPort.class, ShutdownBudget.class};

    /** Every key the declaring classes read, under the prefixes this module answers for. */
    private static final Set<String> KNOWN = declaredKeys();

    /** Sorted, so the nearest-match suggestion does not depend on a set's iteration order. */
    private static final List<String> SORTED_KNOWN = KNOWN.stream().sorted().toList();

    private ApiConfigKeys() {
    }

    /**
     * Warns about an {@code excalibur.api.} key no setting answers to, and about a retired
     * {@code excalibur.filter.} key a file from an earlier build still sets.
     *
     * @param properties The effective settings, file and environment overlaid
     * @throws NullPointerException if properties is null
     */
    public static void reportUnrecognised(Map<String, String> properties) {
        for (String key : new TreeSet<>(properties.keySet())) {
            if (key.startsWith(RETIRED_PREFIX)) {
                LOGGER.warn("{} is no longer read: the term filter it configured has been "
                    + "removed, and the line can be deleted", key);
                continue;
            }
            if (!owns(key) || KNOWN.contains(key)) {
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

    /**
     * Whether this module claims the key's prefix, and so will judge a typo under it.
     *
     * @param key A property key
     * @return true if the key starts with a prefix this module answers for
     */
    public static boolean owns(String key) {
        for (String prefix : PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every key this module reads, in a stable order.
     *
     * @return The inventory, sorted
     */
    public static List<String> known() {
        return SORTED_KNOWN;
    }

    /** The nearest known key, by the same measure {@link ConfigKeys} uses for its own. */
    private static String nearest(String key) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : SORTED_KNOWN) {
            int distance = ConfigKeys.editDistance(key, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return bestDistance <= Math.max(3, key.length() / 3) ? best : null;
    }

    /** The {@code public static final String KEY_*} constants every declaring class holds. */
    private static Set<String> declaredKeys() {
        Set<String> keys = new TreeSet<>();
        for (Class<?> type : DECLARING) {
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
