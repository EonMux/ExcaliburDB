package com.eonmux.excalibur.retrieval.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves a relation-like phrase from a question to the relation ids it might name.
 *
 * <p>The same boundary {@link EntityLinker} is, for the other column. A question says
 * "collaborated" and the graph holds {@code collaborated_with}; nothing downstream of here works
 * in words.
 *
 * <p>An interface rather than the dictionary, so the pipeline does not depend on which dictionary
 * produced the names and a test can supply its own.
 */
@FunctionalInterface
public interface RelationResolver {

    /**
     * Relation ids {@code hint} could name.
     *
     * @param hint a relation-like phrase from the question
     * @return the candidates, empty if the phrase matches no relation this graph holds
     */
    int[] resolve(String hint);

    /**
     * Every relation id this graph holds, or null when this resolver does not know them.
     *
     * <p>What a one-way traversal needs when the question names no relation. The store's
     * absent filter means every relation in <em>both</em> directions, so walking every relation
     * forward only has to name each of them - and only the vocabulary knows what they are.
     *
     * @return the ids, or null for a resolver that only maps phrases
     */
    default int[] all() {
        return null;
    }

    /**
     * A resolver over the relation names a graph actually holds.
     *
     * <h2>The matching rule</h2>
     * Both sides are reduced to their letters and digits, lowercased - which collapses
     * {@code collaboratedWith}, {@code collaborated_with} and {@code "collaborated with"} onto one
     * string, so the extractor's choice of casing convention stops mattering. Two names match when
     * one is a prefix of the other.
     *
     * <p>Prefix rather than containment, because the phrases arriving here are a fixed English
     * vocabulary and containment makes them promiscuous: a hint of "between" would claim any
     * relation with "between" anywhere inside it. Prefix catches what this is for - a question's
     * bare verb against the graph's verb phrase, "collaborated" against
     * {@code collaborated_with} - and little else.
     *
     * <p>Nothing shorter than {@value #MIN_MATCH_LENGTH} characters matches at all. A two-letter
     * relation name is a prefix of a great many words, and a filter built from an accident of
     * spelling is worse than no filter.
     *
     * @param relationNames relation id to the name recorded for it
     * @return a resolver over those names
     */
    static RelationResolver over(Map<Integer, String> relationNames) {
        if (relationNames == null) {
            throw new IllegalArgumentException("relationNames must not be null");
        }
        Map<String, List<Integer>> byNormalized = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : relationNames.entrySet()) {
            String normalized = normalize(entry.getValue());
            if (normalized.length() >= MIN_MATCH_LENGTH) {
                byNormalized.computeIfAbsent(normalized, ignored -> new ArrayList<>())
                    .add(entry.getKey());
            }
        }

        int[] every =
            relationNames.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        return new RelationResolver() {
            @Override
            public int[] resolve(String hint) {
                String normalized = normalize(hint);
                if (normalized.length() < MIN_MATCH_LENGTH) {
                    return new int[0];
                }
                List<Integer> matched = new ArrayList<>();
                for (Map.Entry<String, List<Integer>> candidate : byNormalized.entrySet()) {
                    String name = candidate.getKey();
                    if (name.startsWith(normalized) || normalized.startsWith(name)) {
                        matched.addAll(candidate.getValue());
                    }
                }
                int[] ids = new int[matched.size()];
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = matched.get(i);
                }
                return ids;
            }

            @Override
            public int[] all() {
                return every.clone();
            }
        };
    }

    /** Shortest name that may take part in a match. */
    int MIN_MATCH_LENGTH = 4;

    /** Letters and digits only, lowercased, so casing and separators stop mattering. */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
