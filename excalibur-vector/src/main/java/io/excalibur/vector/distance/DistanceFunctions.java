package io.excalibur.vector.distance;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a metric name into the {@link DistanceFunction} that implements it.
 *
 * <p>A metric arrives as a string wherever it crosses a boundary the type system does
 * not reach: a properties file, a request parameter, a graph file header. Every one of
 * those places has to agree on the same spelling as {@link DistanceFunction#name()},
 * because {@code DistanceValidator} switches on that name to decide how a score is
 * clamped and normalized. Resolving through this one registry is what keeps them in
 * step; a name it does not recognise is a configuration error and is refused rather
 * than quietly defaulted, since the wrong metric changes which documents come back.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class holds only immutable state and is safe for concurrent use.</p>
 *
 * @author ExcaliburDB
 * @since 1.3
 */
public final class DistanceFunctions {

    /**
     * Insertion-ordered so the rejection message lists the metrics in a stable order.
     */
    private static final Map<String, DistanceFunction> BY_NAME = buildRegistry();

    private DistanceFunctions() {
        throw new AssertionError("DistanceFunctions is a utility class and is not instantiable");
    }

    private static Map<String, DistanceFunction> buildRegistry() {
        Map<String, DistanceFunction> registry = new LinkedHashMap<>();
        register(registry, CosineSimilarity.getInstance());
        register(registry, L2Distance.getInstance());
        register(registry, InnerProduct.getInstance());
        return Collections.unmodifiableMap(registry);
    }

    private static void register(Map<String, DistanceFunction> registry, DistanceFunction function) {
        DistanceFunction previous = registry.put(function.name(), function);
        if (previous != null) {
            throw new IllegalStateException(
                "Two distance functions claim the name '" + function.name() + "'");
        }
    }

    /**
     * Returns the distance function registered under the given metric name.
     *
     * <p>The name is trimmed and lower-cased first, so a value typed into a
     * properties file with stray whitespace or capitals still resolves.</p>
     *
     * @param name The metric name, for example {@code "cosine"}
     * @return The matching distance function, never null
     * @throws IllegalArgumentException if the name is null, blank or unrecognised
     */
    public static DistanceFunction byName(String name) {
        String canonical = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        DistanceFunction function = BY_NAME.get(canonical);
        if (function == null) {
            throw new IllegalArgumentException(
                "Unknown distance metric '" + name + "'; accepted values are " + names());
        }
        return function;
    }

    /**
     * Returns the metric names this registry can resolve.
     *
     * @return An unmodifiable set of canonical metric names
     */
    public static Set<String> names() {
        return BY_NAME.keySet();
    }
}
