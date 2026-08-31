package excalibur.api.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parsing of configuration values shared by the security policies.
 *
 * <p>Every reader here follows the same rule: a value that cannot be understood is reported and
 * the default is used, rather than the server failing to start over a malformed rate limit. The
 * exception is anything whose default would be less safe than the value the operator asked for -
 * those are validated by the policy that owns them and are allowed to fail the startup.
 *
 * <p>Package-private on purpose. This is not a general-purpose parsing library; it exists so that
 * {@link ApiSecurityConfig} and {@link TlsConfig} read the same key the same way.
 */
final class ConfigText {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigText.class);

    private ConfigText() {
        // Utility class
    }

    /** The value with surrounding whitespace removed, or null when absent or blank. */
    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The named property, trimmed, or null when absent or blank. */
    static String value(Map<String, String> properties, String key) {
        return trimToNull(properties.get(key));
    }

    /**
     * A comma-separated list, with blank entries dropped.
     *
     * <p>Returns an empty list for an absent value, which every caller reads as "nothing
     * configured" rather than "everything allowed".
     */
    static List<String> splitList(String value) {
        List<String> items = new ArrayList<>();
        if (value == null) {
            return items;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    /**
     * A strictly positive, finite double.
     *
     * <p>Zero and negatives are rejected rather than clamped: a rate limit of zero would stop the
     * server answering anything, which is never what an operator meant to type.
     */
    static double positiveDouble(Map<String, String> properties, String key, double defaultValue) {
        String raw = value(properties, key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(raw);
            if (parsed > 0 && Double.isFinite(parsed)) {
                return parsed;
            }
            LOGGER.warn("Ignoring non-positive value for {}: {}", key, raw);
        } catch (NumberFormatException e) {
            LOGGER.warn("Ignoring unparseable value for {}: {}", key, raw);
        }
        return defaultValue;
    }

    /** A strictly positive long. */
    static long positiveLong(Map<String, String> properties, String key, long defaultValue) {
        String raw = value(properties, key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw);
            if (parsed > 0) {
                return parsed;
            }
            LOGGER.warn("Ignoring non-positive value for {}: {}", key, raw);
        } catch (NumberFormatException e) {
            LOGGER.warn("Ignoring unparseable value for {}: {}", key, raw);
        }
        return defaultValue;
    }

    /**
     * A boolean flag.
     *
     * <p>Only {@code true} and {@code false} are accepted, case-insensitively. Anything else is
     * reported and the default applies - notably not {@link Boolean#parseBoolean}, which silently
     * reads every typo, including {@code "TRUE "} with a stray character or {@code "yes"}, as
     * false. For a flag whose whole job is to relax a safety check, reading a typo as a value is
     * worse than reading it as absent.
     */
    static boolean flag(Map<String, String> properties, String key, boolean defaultValue) {
        String raw = value(properties, key);
        if (raw == null) {
            return defaultValue;
        }
        String normalized = raw.toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }
        LOGGER.warn("Ignoring unparseable boolean for {}: {} (expected true or false)", key, raw);
        return defaultValue;
    }
}
