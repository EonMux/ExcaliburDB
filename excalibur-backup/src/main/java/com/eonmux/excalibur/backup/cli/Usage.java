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
          another, so a clean shutdown is the only moment at which every file describes
          the same instant. The snapshot takes the data directory's lock for the length
          of the copy, so a directory a node holds is refused and a node cannot start
          during the copy. The directory is also fingerprinted before and after the copy,
          and a snapshot whose source moved underneath it is refused. --allow-live
          downgrades both refusals to a warning and records the image as LIVE_UNSAFE.

          --exclude-derived leaves out state something will rebuild: the lexical chunk
          index, a tenant's built edge store and the quantiser codebooks. The HNSW graphs and their node mappings, a compaction's staged
          mapping included, are never left out, because nothing rebuilds them. A mapping
          names each node's chunk, or its document in a file an older build wrote.

          Never copied, and reported as omitted: staged names a crash left behind, JVM
          heap dumps (*.hprof), which are a record of a process rather than state, and
          the segment_N.tfs term sketches an earlier build kept, which nothing reads.

        """;

    /** The synopsis and description of {@code verify}. */
    private static final String VERIFY = """
        USAGE
          excalibur-backup verify   --snapshot <dir> [--deep]

        VERIFY
          Checks an image against its manifest without restoring it. Every named file is
          measured and checksummed, and every file present must be named.

          --deep also compares SHA-256, and hands every file to the checker for its
          format: the engine's own for segments, the catalog, HNSW graphs and their node
          mappings (each node's chunk at version 3, its document at versions 1 and 2),
          quantiser codebooks and LSH tables, each run on a scratch copy; excalibur-fsck's
          for the write-ahead logs, the routing directory, the document registry, source
          catalog and source status history, the graph staging logs, the lexical store,
          a term sketch an earlier build kept, which is reported and not counted against
          the image, and a compaction's staged graph and mapping, which the node discards
          on open while both are there. The report says how many were proven
          against a checksum or seal, and lists the ones whose format carries nothing to
          prove them by.

        """;

    /** The synopsis and description of {@code restore}. */
    private static final String RESTORE = """
        USAGE
          excalibur-backup restore  --snapshot <dir> --target <dir> [--force] [--deep]

        RESTORE
          Verifies the image to the same depth as verify - lengths and CRC32C, or with
          --deep also SHA-256 and the format checkers - then takes the target's lock,
          stages the image inside the target and moves it into place entry by entry. The
          target itself is never renamed, so it may be a mount point, such as the
          container image's volume. Every step is journaled: a failure puts back what the
          target held, and after a crash the node refuses the directory until restore is
          run into it again, which first completes or undoes the interrupted one.

          Refuses an image that does not verify, a target a node or another tool holds,
          and a target that already holds something unless --force is given. In each case
          the target's data is left exactly as it was. A term sketch in an image an
          earlier build took is verified with the image and not restored.

          The lost+found of a filesystem mounted at the target is the filesystem's: it
          does not make the target hold something, and it is never moved or replaced.

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
          5   the data directory moved while it was being copied; a writer is running
          6   a file could not be read or written, or a copy did not read back as
              what was written
          7   another process holds the data directory or restore target: a node is
              running on it, or a repair or restore is; nothing was written
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
