package com.eonmux.excalibur.api.protocol;

import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;

/**
 * Refuses the members of a request object that the route does not read.
 *
 * <p>Every accessor on a request body finds a member by name, so a misspelt optional member reads
 * as absent and the request is answered as though the caller had not asked for what it plainly
 * did: a {@code contentType} indexes an HTML page as plain text, a {@code valid_form} states a
 * fact as holding over all of time, an {@code include_extensions} crawls a whole root unfiltered.
 * Each of those succeeds, so nothing tells the caller. Naming the member in a {@code 400} turns
 * the misspelling into an error they can see and correct.</p>
 */
public final class UnknownFields {

    private UnknownFields() {
        throw new AssertionError("Utility class");
    }

    /**
     * Refuses members the object does not take, naming every one of them.
     *
     * @param present The member names the object carries
     * @param takes   The names this object takes, listed back to the caller
     * @param prefix  What the names are qualified by in the message - {@code "config."},
     *                {@code "documents[3]."} - or nothing
     * @param what    What the object is, for the message
     * @throws IllegalArgumentException if any member is not taken
     */
    public static void refuse(Collection<String> present, List<String> takes, String prefix,
                              String what) {
        refuse(present, takes, List.of(), prefix, what);
    }

    /**
     * Refuses members the object does not take and nothing else accounts for.
     *
     * @param present   The member names the object carries
     * @param takes     The names this object takes, listed back to the caller
     * @param explained Names not taken here that the caller refuses separately, with a reason
     *                  of their own, and so are passed over here
     * @param prefix    What the names are qualified by in the message, or nothing
     * @param what      What the object is, for the message
     * @throws IllegalArgumentException if any member is neither taken nor explained
     */
    public static void refuse(Collection<String> present, List<String> takes,
                              Collection<String> explained, String prefix, String what) {
        StringJoiner unknown = new StringJoiner("', '", "'", "'");
        int count = 0;
        for (String name : present) {
            if (!takes.contains(name) && !explained.contains(name)) {
                unknown.add(prefix + name);
                count++;
            }
        }
        if (count > 0) {
            throw new IllegalArgumentException((count == 1 ? "Unknown field " : "Unknown fields ")
                + unknown + ": " + what + " takes " + String.join(", ", takes)
                + ". Correct the name, or remove the field.");
        }
    }
}
