package com.eonmux.excalibur.backup.cli;

import java.io.PrintStream;

/**
 * The text printed when the command line is wrong, or when help is asked for.
 *
 * <p>Kept as one section per subcommand, so that {@code excalibur-backup restore --help}
 * answers the question that was asked - what restore takes - rather than burying it in the
 * other two. Every form ends with the exit codes, because a script's author reading the help
 * for one subcommand needs them as much as one reading all three.</p>
 *
 * @author ExcaliburDB
 */
public final class Usage {

    /** The first line, naming the tool. */
    private static final String HEADER = """
        excalibur-backup - verified snapshot, verify and restore for a data directory

        """;

    /** The synopsis and description of {@code snapshot}. */
    private static final String SNAPSHOT = """
        USAGE
          excalibur-backup snapshot --data-dir <dir> --out <dir> [--exclude-derived]
                                    [--allow-live]

        SNAPSHOT
          Copies a data directory into a self-describing image: the payload under data/,
          and a MANIFEST naming every file with its length, its CRC32C and its SHA-256,
          plus the format versions and the epoch the image is coherent at.

          The node must be stopped. Nothing in this engine orders one file against
          another and nothing locks the directory, so a clean shutdown is the only moment
          at which every file describes the same instant. The directory is fingerprinted
          before and after the copy, and a snapshot whose source moved underneath it is
          refused; --allow-live downgrades that refusal to a warning and records the
          image as LIVE_UNSAFE.

          --exclude-derived leaves out state something will rebuild: the lexical chunk
          index, a tenant's built edge store, the quantiser codebooks and the segments'
          term sketches. The HNSW graphs and their document mappings, a compaction's staged
          mapping included, are never left out, because nothing rebuilds them.

          Never copied, and reported as omitted: staged names a crash left behind, and
          JVM heap dumps (*.hprof), which are a record of a process rather than state.

        """;

    /** The synopsis and description of {@code verify}. */
    private static final String VERIFY = """
        USAGE
          excalibur-backup verify   --snapshot <dir> [--deep]

        VERIFY
          Checks an image against its manifest without restoring it. Every named file is
          measured and checksummed, and every file present must be named.

          --deep also compares SHA-256, and hands every file to the checker for its
          format: the engine's own for segments, the catalog, HNSW graphs and their
          document mappings, quantiser codebooks and LSH tables, each run on a scratch
          copy; excalibur-fsck's for the write-ahead logs, the routing directory, the
          registries, the graph staging logs, the lexical store, the term sketches and
          a compaction's staged graph and mapping, which the node discards on open while
          both are there. The report says how
          many were proven against a checksum or seal, and lists the ones whose format
          carries nothing to prove them by.

        """;

    /** The synopsis and description of {@code restore}. */
    private static final String RESTORE = """
        USAGE
          excalibur-backup restore  --snapshot <dir> --target <dir> [--force] [--deep]

        RESTORE
          Verifies the image to the same depth as verify - lengths and CRC32C, or with
          --deep also SHA-256 and the format checkers - then builds the new tree beside
          the target and renames it on in one move. Refuses an image that does not
          verify. Refuses a target that already holds something unless --force is given.
          In both cases the target is left exactly as it was.

        """;

    /** The synopsis of all three, for the full text. */
    private static final String SYNOPSIS = """
        USAGE
          excalibur-backup snapshot --data-dir <dir> --out <dir> [--exclude-derived]
                                    [--allow-live]
          excalibur-backup verify   --snapshot <dir> [--deep]
          excalibur-backup restore  --snapshot <dir> --target <dir> [--force] [--deep]
          excalibur-backup <command> --help

        """;

    /** What each exit code means. */
    private static final String EXIT_CODES = """
        EXIT CODES
          0   the command did what it was asked
          2   the command line was wrong
          3   a snapshot did not verify; nothing was restored
          4   the restore target is not empty and --force was not given, or the
              snapshot --out directory is not empty
          5   the data directory moved while it was being copied; a node is running
          6   a file could not be read or written, or a copy did not read back as
              what was written
          70  an unanticipated failure
        """;

    private Usage() {
    }

    /**
     * Prints the usage text for every subcommand.
     *
     * @param stream Where to print it
     */
    public static void print(PrintStream stream) {
        stream.print(HEADER + SYNOPSIS + withoutSynopsis(SNAPSHOT) + withoutSynopsis(VERIFY)
            + withoutSynopsis(RESTORE) + EXIT_CODES);
    }

    /**
     * Prints the usage text for one subcommand, or for all of them if it is not one.
     *
     * @param stream  Where to print it
     * @param command The subcommand's name, as typed
     */
    public static void print(PrintStream stream, String command) {
        String section = switch (command) {
            case "snapshot" -> SNAPSHOT;
            case "verify" -> VERIFY;
            case "restore" -> RESTORE;
            default -> null;
        };
        if (section == null) {
            print(stream);
            return;
        }
        stream.print(HEADER + section + EXIT_CODES);
    }

    /**
     * Drops a section's own synopsis, which the full text gives once for all three.
     *
     * @param section One subcommand's section
     * @return Its description alone
     */
    private static String withoutSynopsis(String section) {
        return section.substring(section.indexOf("\n\n") + 2);
    }
}
