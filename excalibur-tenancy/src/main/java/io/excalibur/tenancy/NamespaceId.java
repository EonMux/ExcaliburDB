package io.excalibur.tenancy;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * A tenant identifier, validated so that it is safe to use as a directory name.
 *
 * <p>This type exists because the identifier becomes a path. A tenant id of {@code ../other-tenant}
 * or an absolute path would otherwise let one tenant's request resolve into another's index
 * directory — cross-tenant data access from a string that arrived over the network. Validating at
 * construction means every later use is safe by virtue of holding one of these, rather than by
 * every call site remembering to check.
 *
 * <p>The rule is an allowlist, not a blocklist: lowercase letters, digits, hyphen and underscore,
 * bounded in length. A blocklist of dangerous sequences would have to anticipate every encoding,
 * separator and normalisation form the filesystem accepts; an allowlist does not.
 */
public record NamespaceId(String value) implements Comparable<NamespaceId> {

    /** Longest permitted identifier. Comfortably inside every filesystem's component limit. */
    public static final int MAX_LENGTH = 64;

    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    /**
     * The namespace holding data shared by every tenant, such as a curated knowledge base.
     *
     * <p>Named rather than magic so that "is this the shared index" is a comparison against a
     * constant, and so a tenant cannot claim the name — {@code shared} matches the allowlist, so
     * {@link #of} rejects it explicitly.
     */
    public static final NamespaceId SHARED = new NamespaceId("shared");

    public NamespaceId {
        if (value == null) {
            throw new IllegalArgumentException("namespace id must not be null");
        }
    }

    /**
     * Validates and wraps a tenant identifier.
     *
     * @throws IllegalArgumentException if it is empty, too long, contains anything outside the
     *                                  allowlist, or claims the shared namespace
     */
    public static NamespaceId of(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("namespace id must not be empty");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                "namespace id must be at most " + MAX_LENGTH + " characters, got " + value.length());
        }
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "namespace id must match [a-z0-9][a-z0-9_-]* — it becomes a directory name, and "
                    + "anything else risks resolving outside its own namespace. Got: " + value);
        }
        if (value.equals(SHARED.value())) {
            throw new IllegalArgumentException(
                "'" + SHARED.value() + "' is reserved for the shared read-only index");
        }
        return new NamespaceId(value);
    }

    /**
     * This namespace's directory under {@code root}.
     *
     * <p>Resolves and then verifies the result is still inside {@code root}. The validation in
     * {@link #of} already makes escape impossible, so this is a second, independent check — cheap,
     * and the kind of defence that keeps a future change to the pattern from silently becoming a
     * traversal.
     */
    public Path directoryUnder(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        Path resolved = root.resolve(value).normalize();
        Path normalisedRoot = root.normalize();
        if (!resolved.startsWith(normalisedRoot) || resolved.equals(normalisedRoot)) {
            throw new IllegalStateException(
                "namespace " + value + " resolved outside its root: " + resolved);
        }
        return resolved;
    }

    /** Whether this is the shared, read-only namespace. */
    public boolean isShared() {
        return value.equals(SHARED.value());
    }

    @Override
    public int compareTo(NamespaceId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
