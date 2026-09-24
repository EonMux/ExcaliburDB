package com.eonmux.excalibur.core.datadir;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the process holding a data directory's lock wrote about itself.
 *
 * <p>Informational, never authoritative. The operating system's lock on the file is what keeps
 * a second process out; this record is what lets the refusal say who is in the way. It can be
 * stale - a holder that was killed leaves its record behind, and the next holder overwrites it -
 * so nothing decides anything from it: a record is read only after the lock has been found held,
 * and then only to describe the holder.</p>
 *
 * <p>The format is one {@code key=value} per line, so an operator can read it with {@code cat}
 * and a shell script can pick a value out with {@code sed}; the container's health check reads
 * {@code health-url} exactly that way. Unknown keys are ignored and a repeated key takes its
 * last value, which is what lets a holder add to its record by appending rather than
 * rewriting.</p>
 *
 * @param pid         The holder's process id, or {@link #UNKNOWN_PID}
 * @param description What the holder is, e.g. {@code excalibur node}
 * @param since       When it took the lock, as an ISO-8601 instant
 * @param url         The URL a node is listening on, once it has bound its socket
 * @param healthUrl   Where a probe on this host reaches the node's liveness route
 * @author ExcaliburDB
 */
public record LockHolder(long pid,
                         String description,
                         Optional<String> since,
                         Optional<String> url,
                         Optional<String> healthUrl) {

    /** The pid of a holder whose record does not carry a readable one. */
    public static final long UNKNOWN_PID = -1L;

    /** The description of a holder whose record does not carry one. */
    static final String UNKNOWN_DESCRIPTION = "unknown";

    /** Key of the process id. */
    static final String KEY_PID = "pid";

    /** Key of the holder's description. */
    static final String KEY_HOLDER = "holder";

    /** Key of the instant the lock was taken. */
    static final String KEY_SINCE = "since";

    /** Key of the URL a node listens on. */
    static final String KEY_URL = "url";

    /** Key of the URL a local probe reaches the node's liveness route at. */
    static final String KEY_HEALTH_URL = "health-url";

    /**
     * Validates the components.
     *
     * @throws NullPointerException if any component is null
     */
    public LockHolder {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(since, "since");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(healthUrl, "healthUrl");
    }

    /**
     * Reads a record, tolerating anything in it.
     *
     * <p>A record is read at the one moment something has already gone wrong - another process
     * holds the directory - so a record that cannot be parsed must not turn that clear refusal
     * into a confusing one. Whatever cannot be read is reported as unknown.</p>
     *
     * @param text The lock file's contents
     * @return What the record says, as far as it can be read
     */
    public static LockHolder parse(String text) {
        long pid = UNKNOWN_PID;
        String description = UNKNOWN_DESCRIPTION;
        String since = null;
        String url = null;
        String healthUrl = null;
        for (String line : Objects.requireNonNull(text, "text").split("\n")) {
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String value = line.substring(equals + 1).trim();
            switch (line.substring(0, equals).trim()) {
                case KEY_PID -> pid = parsePid(value);
                case KEY_HOLDER -> description = value.isEmpty() ? UNKNOWN_DESCRIPTION : value;
                case KEY_SINCE -> since = value;
                case KEY_URL -> url = value;
                case KEY_HEALTH_URL -> healthUrl = value;
                default -> {
                    // A key a later build writes; ignored so that build's record still reads.
                }
            }
        }
        return new LockHolder(pid, description, Optional.ofNullable(since),
            Optional.ofNullable(url), Optional.ofNullable(healthUrl));
    }

    /**
     * Writes the record in the form {@link #parse} reads.
     *
     * @return One {@code key=value} line per component that is present
     */
    public String render() {
        StringBuilder text = new StringBuilder();
        text.append(KEY_PID).append('=').append(pid).append('\n');
        text.append(KEY_HOLDER).append('=').append(description).append('\n');
        since.ifPresent(value -> text.append(KEY_SINCE).append('=').append(value).append('\n'));
        url.ifPresent(value -> text.append(KEY_URL).append('=').append(value).append('\n'));
        healthUrl.ifPresent(value ->
            text.append(KEY_HEALTH_URL).append('=').append(value).append('\n'));
        return text.toString();
    }

    /**
     * Describes the holder for a refusal message.
     *
     * @return e.g. {@code pid 42, excalibur node, since 2026-09-23T10:00:00Z}
     */
    public String describe() {
        List<String> parts = new ArrayList<>();
        if (pid != UNKNOWN_PID) {
            parts.add("pid " + pid);
        }
        if (!UNKNOWN_DESCRIPTION.equals(description)) {
            parts.add(description);
        }
        since.ifPresent(value -> parts.add("since " + value));
        return parts.isEmpty() ? "a process that did not record who it is" : String.join(", ", parts);
    }

    /**
     * Returns this record with the endpoint a node listens on added.
     *
     * @param listening Where the node is listening
     * @param health    Where a probe on this host reaches its liveness route
     * @return The extended record
     */
    LockHolder withEndpoint(String listening, String health) {
        return new LockHolder(pid, description, since, Optional.of(listening),
            Optional.of(health));
    }

    /**
     * Parses a process id, reporting an unreadable one as unknown.
     *
     * @param value The recorded value
     * @return The pid, or {@link #UNKNOWN_PID}
     */
    private static long parsePid(String value) {
        try {
            long pid = Long.parseLong(value);
            return pid > 0 ? pid : UNKNOWN_PID;
        } catch (NumberFormatException e) {
            return UNKNOWN_PID;
        }
    }
}
