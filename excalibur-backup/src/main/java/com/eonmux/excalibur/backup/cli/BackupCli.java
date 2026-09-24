package com.eonmux.excalibur.backup.cli;

import com.eonmux.excalibur.backup.manifest.ManifestFormatException;
import com.eonmux.excalibur.backup.restore.RestoreRefusedException;
import com.eonmux.excalibur.backup.snapshot.SnapshotRefusedException;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Set;

/**
 * The command: {@code snapshot}, {@code verify} and {@code restore}.
 *
 * <p>Each runs once and exits. Nothing here binds a port, schedules a thread pool or
 * outlives the process, because a backup tool that is a service is a service that has to
 * be backed up.</p>
 *
 * <p>The JVM needs {@code --enable-preview}, {@code --enable-native-access=ALL-UNNAMED}
 * and {@code --add-modules=jdk.incubator.vector}: the first because every class in this
 * build is stamped as a preview class file, the last because the vector index's code
 * references the incubating Vector API. The generated start scripts carry all three.</p>
 *
 * @author ExcaliburDB
 */
public final class BackupCli {

    private BackupCli() {
    }

    /**
     * Runs the command and exits with its code.
     *
     * @param args What was typed
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err).code());
    }

    /**
     * Runs the command, reporting to the given streams instead of exiting.
     *
     * @param args What was typed
     * @param out  Where results go
     * @param err  Where refusals and faults go
     * @return What the process should exit with
     */
    public static ExitCode run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, BackupCli::dispatch);
    }

    /**
     * Runs the command through the given subcommands.
     *
     * <p>Everything this class decides - usage, help, and which exit code each failure maps
     * to - happens around {@code command}, so a test can stand a failure in for the real
     * subcommands and see how it is reported. The one it needs this for is a defect: nothing
     * a real subcommand can be made to do from outside throws what this build did not
     * anticipate, and that is the case whose exit code most needs to be pinned.</p>
     *
     * @param args    What was typed
     * @param out     Where results go
     * @param err     Where refusals and faults go
     * @param command What runs a line that is not a request for usage
     * @return What the process should exit with
     */
    static ExitCode run(String[] args, PrintStream out, PrintStream err, Command command) {
        if (args.length == 0) {
            err.println("excalibur-backup: no command given");
            Usage.print(err);
            return ExitCode.USAGE;
        }
        if (args[0].equals("--help") || args[0].equals("-h") || args[0].equals("help")) {
            Usage.print(out);
            return ExitCode.OK;
        }
        Set<String> options = optionsOf(args[0]);
        if (options != null && ArgumentParser.wantsHelp(args, options)) {
            Usage.print(out, args[0]);
            return ExitCode.OK;
        }
        try {
            return command.run(args, out, err);
        } catch (UsageException e) {
            err.println("excalibur-backup: " + e.getMessage());
            Usage.print(err, args[0]);
            return ExitCode.USAGE;
        } catch (IOException e) {
            err.println("excalibur-backup: " + e.getMessage());
            return codeFor(e);
        } catch (RuntimeException e) {
            err.println("excalibur-backup: unanticipated failure: " + e);
            e.printStackTrace(err);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    /**
     * Parses the line for the named subcommand and runs it.
     *
     * @param args What was typed
     * @param out  Where results go
     * @param err  Where refusals and faults go
     * @return What the subcommand decided
     * @throws UsageException if the command or its options are not understood
     * @throws IOException    if the subcommand fails or refuses
     */
    private static ExitCode dispatch(String[] args, PrintStream out, PrintStream err)
        throws UsageException, IOException {
        return switch (args[0]) {
            case "snapshot" -> SnapshotCommand.execute(
                parse(args, SnapshotCommand.OPTIONS, SnapshotCommand.FLAGS), out);
            case "verify" -> VerifyCommand.execute(
                parse(args, VerifyCommand.OPTIONS, VerifyCommand.FLAGS), out, err);
            case "restore" -> RestoreCommand.execute(
                parse(args, RestoreCommand.OPTIONS, RestoreCommand.FLAGS), out);
            default -> throw new UsageException("unknown command: " + args[0]);
        };
    }

    /**
     * Names the options a subcommand takes a value for.
     *
     * @param command The subcommand, as typed
     * @return Its options, or null if this build has no such subcommand
     */
    private static Set<String> optionsOf(String command) {
        return switch (command) {
            case "snapshot" -> SnapshotCommand.OPTIONS;
            case "verify" -> VerifyCommand.OPTIONS;
            case "restore" -> RestoreCommand.OPTIONS;
            default -> null;
        };
    }

    /**
     * Parses a command line against one subcommand's options.
     *
     * @param args         What was typed
     * @param validOptions Options taking a value
     * @param validFlags   Options taking none
     * @return The parsed line
     * @throws UsageException if an option is unknown, repeated or missing its value
     */
    private static Arguments parse(String[] args, Set<String> validOptions,
                                   Set<String> validFlags) throws UsageException {
        return ArgumentParser.parse(args, validOptions, validFlags);
    }

    /**
     * Runs a command line that is not a request for usage.
     */
    @FunctionalInterface
    interface Command {

        /**
         * Runs the line.
         *
         * @param args What was typed, the subcommand first
         * @param out  Where results go
         * @param err  Where refusals and faults go
         * @return What the subcommand decided
         * @throws UsageException if the command or its options are not understood
         * @throws IOException    if the subcommand fails or refuses
         */
        ExitCode run(String[] args, PrintStream out, PrintStream err)
            throws UsageException, IOException;
    }

    /**
     * Maps a failure to the exit code a script should see.
     *
     * <p>Each refusal has its own code because they call for opposite responses: a damaged
     * image is an incident, and a non-empty target is usually a typo.</p>
     *
     * @param failure What went wrong
     * @return The code to exit with
     */
    private static ExitCode codeFor(IOException failure) {
        if (failure instanceof RestoreRefusedException refusal) {
            return switch (refusal.reason()) {
                case SNAPSHOT_DOES_NOT_VERIFY -> ExitCode.VERIFICATION_FAILED;
                case TARGET_NOT_EMPTY -> ExitCode.TARGET_NOT_EMPTY;
                case TARGET_IN_USE -> ExitCode.IN_USE;
            };
        }
        if (failure instanceof SnapshotRefusedException refusal) {
            return switch (refusal.reason()) {
                case DESTINATION_NOT_EMPTY -> ExitCode.TARGET_NOT_EMPTY;
                case SOURCE_CHANGED_UNDERNEATH -> ExitCode.NOT_QUIESCED;
                case COPY_DID_NOT_MATCH -> ExitCode.IO_ERROR;
                case SOURCE_IN_USE -> ExitCode.IN_USE;
            };
        }
        return failure instanceof ManifestFormatException
            ? ExitCode.VERIFICATION_FAILED : ExitCode.IO_ERROR;
    }
}
