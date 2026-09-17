package excalibur.backup.cli;

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
