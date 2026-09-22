package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.protocol.JsonText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a registration's configuration looks like when it is read back out.
 *
 * <h2>Why not just echo it</h2>
 * <p>Every key a registration can carry today is a crawl setting - a root, a URL, a depth, a
 * list of extensions - and none of them is a secret. That is a fact about this release, not a
 * property of the shape: the configuration is an open string map, it is persisted to a file on
 * disk that a person can edit, and the obvious next settings to want on an HTTP crawl are a
 * bearer token and a cookie. A read-back that echoes whatever it finds would hand those to
 * every holder of a read token on the day they are added, and nothing about adding them would
 * look like a change to this endpoint.</p>
 *
 * <p>So the direction is inverted: keys this node knows to be crawl settings are echoed, and
 * anything else is named and not quoted. A caller still learns that the registration carries
 * something unrecognised - which is what it needs to know, since the node will not honour it
 * either - without the value crossing the boundary.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless and immutable.</p>
 */
final class SourceConfigView {

    /**
     * The configuration keys a read-back may quote.
     *
     * <p>Exactly the ones {@link SourceFactory} understands. A key outside this set is either a
     * hand-edit of the catalog file or a setting from a build this one does not share, and in
     * both cases the node cannot say what the value is for.</p>
     */
    private static final Set<String> READABLE = Set.of(
        SourceFactory.ROOT_PATH,
        SourceFactory.BASE_URL,
        SourceFactory.INCLUDED_EXTENSIONS,
        SourceFactory.EXCLUDED_DIRECTORIES,
        SourceFactory.MAX_DEPTH,
        SourceFactory.FOLLOW_SYMLINKS,
        SourceFactory.INCLUDE_HIDDEN,
        SourceFactory.MAX_FILE_BYTES,
        SourceFactory.MAX_DOCUMENTS,
        SourceFactory.MAX_RESPONSE_BYTES,
        SourceFactory.TIMEOUT_SECONDS,
        SourceFactory.INCLUDED_PATH_PATTERNS,
        SourceFactory.EXCLUDED_PATH_PATTERNS,
        SourceFactory.USER_AGENT,
        SourceFactory.CRAWL_DELAY_MS);

    private SourceConfigView() {
        throw new AssertionError("Utility class");
    }

    /**
     * Renders a registration's configuration as the two JSON fields it becomes.
     *
     * <p>Sorted, so two reads of an unchanged registration are byte-identical and a caller
     * diffing them sees only what actually changed.</p>
     *
     * @param config The configuration as the registration supplied it
     * @return {@code "config": {...}}, followed by {@code "withheld": [...]} when the
     *         registration carries keys this node will not quote
     */
    static String toJson(Map<String, String> config) {
        StringBuilder readable = new StringBuilder("\"config\": {");
        List<String> withheld = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, String> entry : new TreeMap<>(config).entrySet()) {
            if (!READABLE.contains(entry.getKey())) {
                withheld.add(entry.getKey());
                continue;
            }
            if (!first) {
                readable.append(", ");
            }
            first = false;
            readable.append('"').append(JsonText.escape(entry.getKey())).append("\": \"")
                .append(JsonText.escape(entry.getValue())).append('"');
        }
        readable.append('}');

        if (withheld.isEmpty()) {
            return readable.toString();
        }
        readable.append(", \"withheld\": [");
        for (int i = 0; i < withheld.size(); i++) {
            if (i > 0) {
                readable.append(", ");
            }
            readable.append('"').append(JsonText.escape(withheld.get(i))).append('"');
        }
        return readable.append(']').toString();
    }
}
