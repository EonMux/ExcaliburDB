package com.eonmux.excalibur.backup.cli;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A parsed command line.
 *
 * @param command The subcommand
 * @param options Long options with values, without their leading dashes
 * @param flags   Long options without values, without their leading dashes
 * @author ExcaliburDB
 */
public record Arguments(String command, Map<String, String> options, Set<String> flags) {

    /**
     * Canonical constructor, copying both collections.
     *
     * @throws NullPointerException if any argument is null
     */
    public Arguments {
        Objects.requireNonNull(command, "command");
        options = Map.copyOf(Objects.requireNonNull(options, "options"));
        flags = Set.copyOf(Objects.requireNonNull(flags, "flags"));
    }

    /**
     * Returns a required option as a path.
     *
     * @param name The option's name, without dashes
     * @return Its value
     * @throws UsageException if the option was not given
     */
    public Path requiredPath(String name) throws UsageException {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new UsageException("--" + name + " is required");
        }
        return Path.of(value);
    }

    /**
     * Reports whether a flag was given.
     *
     * @param name The flag's name, without dashes
     * @return true if it was
     */
    public boolean flag(String name) {
        return flags.contains(name);
    }
}
