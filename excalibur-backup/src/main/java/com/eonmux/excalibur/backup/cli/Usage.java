package com.eonmux.excalibur.backup.cli;

import java.io.PrintStream;

/**
 * The text printed when the command line is wrong, or when help is asked for.
 *
 * @author ExcaliburDB
 */
public final class Usage {

    /** The whole of it. */
    private static final String TEXT = """
        excalibur-backup - verified snapshot, verify and restore for a data directory

        USAGE
          excalibur-backup snapshot --data-dir <dir> --out <dir> [--exclude-derived]
                                    [--allow-live]
          excalibur-backup verify   --snapshot <dir> [--deep]
          excalibur-backup restore  --snapshot <dir> --target <dir> [--force] [--deep]

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
          index, a tenant's built edge store and the quantiser codebooks. The HNSW graphs
          and their document mappings are never left out, because nothing rebuilds them.

        VERIFY
          Checks an image against its manifest without restoring it. Every named file is
          measured and checksummed, and every file present must be named. --deep also
          compares SHA-256 and hands each file to the storage engine's own integrity
          checker for its format.

        RESTORE
          Verifies the image to the same depth as verify - lengths and CRC32C, or with
          --deep also SHA-256 and the format checkers - then builds the new tree beside
          the target and renames it on in one move. Refuses an image that does not
          verify. Refuses a target that already holds something unless --force is given.
          In both cases the target is left exactly as it was.

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
     * Prints the usage text.
     *
     * @param stream Where to print it
     */
    public static void print(PrintStream stream) {
        stream.print(TEXT);
    }
}
