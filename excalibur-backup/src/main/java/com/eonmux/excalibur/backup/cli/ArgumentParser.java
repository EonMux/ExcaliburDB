package com.eonmux.excalibur.backup.cli;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns an argument array into a subcommand, options and flags.
 *
 * <p>An unknown option is an error rather than something to ignore. A backup tool that
 * accepts {@code --exclude-derrived} and silently includes the derived state has produced
 * an image the operator believes is smaller than it is; the same mistake on a restore
 * would be worse.</p>
 *
 * @author ExcaliburDB
 */
public final class ArgumentParser {

    private ArgumentParser() {
    }

    /**
     * Parses a command line.
     *
     * @param args         What was typed
     * @param validOptions Options this subcommand takes a value for
     * @param validFlags   Options this subcommand takes without a value
     * @return The parsed line
     * @throws UsageException       if an option is unknown, repeated or missing its value
     * @throws NullPointerException if any argument is null
     */
    public static Arguments parse(String[] args, Set<String> validOptions,
                                  Set<String> validFlags) throws UsageException {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(validOptions, "validOptions");
        Objects.requireNonNull(validFlags, "validFlags");
        if (args.length == 0) {
            throw new UsageException("no command given");
        }
        Map<String, String> options = new LinkedHashMap<>();
        Set<String> flags = new LinkedHashSet<>();
        for (int i = 1; i < args.length; i++) {
            i = consume(args, i, validOptions, validFlags, options, flags);
        }
        return new Arguments(args[0], options, flags);
    }

    /**
     * Reports whether a subcommand's line asks for help.
     *
     * <p>{@code --help} or {@code -h} anywhere an option could stand, and nowhere else: the
     * value of an option that takes one is skipped, so {@code --out -h} names a directory
     * called {@code -h} rather than asking for help. It never throws, and it is asked before
     * the line is parsed, so help is answered for a line that would not parse - which is
     * the line most likely to have prompted someone to ask.</p>
     *
     * @param args         What was typed, the subcommand first
     * @param validOptions Options this subcommand takes a value for
     * @return true if help was asked for
     * @throws NullPointerException if either argument is null
     */
    public static boolean wantsHelp(String[] args, Set<String> validOptions) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(validOptions, "validOptions");
        for (int i = 1; i < args.length; i++) {
            String argument = args[i];
            if (argument.equals("--help") || argument.equals("-h")) {
                return true;
            }
            if (argument.startsWith("--") && validOptions.contains(argument.substring(2))) {
                i++;
            }
        }
        return false;
    }

    /**
     * Consumes one option or flag, and its value if it takes one.
     *
     * @param args         The whole line
     * @param index        Where the option starts
     * @param validOptions Options taking a value
     * @param validFlags   Options taking none
     * @param options      Receives options
     * @param flags        Receives flags
     * @return The index of the last argument consumed
     * @throws UsageException if the argument is not a known option
     */
    private static int consume(String[] args, int index, Set<String> validOptions,
                               Set<String> validFlags, Map<String, String> options,
                               Set<String> flags) throws UsageException {
        String argument = args[index];
        if (!argument.startsWith("--")) {
            throw new UsageException("unexpected argument: " + argument);
        }
        String name = argument.substring(2);
        if (validFlags.contains(name)) {
            flags.add(name);
            return index;
        }
        if (!validOptions.contains(name)) {
            throw new UsageException("unknown option: " + argument);
        }
        if (index + 1 >= args.length) {
            throw new UsageException(argument + " needs a value");
        }
        if (options.put(name, args[index + 1]) != null) {
            throw new UsageException(argument + " was given twice");
        }
        return index + 1;
    }
}
