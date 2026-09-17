package excalibur.fsck.check;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What is wrong between the files rather than inside any one of them.
 *
 * <p>Every file in a data directory can pass its own checksum while the directory as a
 * whole is unusable. A catalog row naming a segment that is not there stops a node opening
 * the store; a vector index whose segment is gone is an index of nothing; a routing slot
 * pointing at a lost segment silently sends a range of keys somewhere that cannot answer
 * for them. None of that is visible to a per-file verifier, and all of it decides whether
 * a node should be allowed to start.</p>
 */
public final class CrossFileChecks {

    private final Path dataDirectory;

    /**
     * Prepares the cross-file pass for one directory.
     *
     * @param dataDirectory The directory being surveyed
     */
    public CrossFileChecks(Path dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    /**
     * Compares the catalog, the routing directory and what is actually on disk.
     *
     * @param files        Every file verdict from the survey
     * @param catalogRows  The catalog's rows
     * @param routingSlots Canonical segment index per hash slot
     * @return Everything inconsistent, in the order it was found
     */
    public List<ConsistencyFinding> run(List<FileCheck> files, List<CatalogRecord> catalogRows,
                                        int[] routingSlots) {
        Set<String> present = presentFileNames(files);
        Set<Long> segmentsOnDisk = segmentsOnDisk(files);
        List<ConsistencyFinding> findings = new ArrayList<>();
        findings.addAll(catalogAgainstDisk(catalogRows, present));
        findings.addAll(diskAgainstCatalog(catalogRows, segmentsOnDisk));
        findings.addAll(orphanedIndexes(files, segmentsOnDisk));
        findings.addAll(routingAgainstDisk(routingSlots, segmentsOnDisk));
        findings.addAll(leftovers(files));
        return findings;
    }

    /**
     * Finds catalog rows whose segment file is not on disk.
     *
     * @param catalogRows The catalog's rows
     * @param present     Every file name found at the top level
     * @return One finding per live row with nothing behind it
     */
    private List<ConsistencyFinding> catalogAgainstDisk(List<CatalogRecord> catalogRows,
                                                        Set<String> present) {
        List<ConsistencyFinding> findings = new ArrayList<>();
        for (CatalogRecord row : catalogRows) {
            if (!row.expectsFileOnDisk()) {
                continue;
            }
            String expected = row.fileName() != null
                ? row.fileName() : "segment_" + row.segmentId() + ".dat";
            if (present.contains(expected)) {
                continue;
            }
            findings.add(new ConsistencyFinding("catalog-names-missing-segment", expected,
                FindingSeverity.DAMAGE,
                "catalog row " + row.index() + " records segment " + row.segmentId()
                    + " as " + row.state() + " and names " + expected
                    + ", which is not in the data directory. Whatever that segment held is "
                    + "gone; the row is a claim the store can no longer honour"));
        }
        return findings;
    }

    /**
     * Finds segment files no catalog row names.
     *
     * @param catalogRows     The catalog's rows
     * @param segmentsOnDisk  Segment numbers with a data file present
     * @return One finding per unnamed segment file
     */
    private List<ConsistencyFinding> diskAgainstCatalog(List<CatalogRecord> catalogRows,
                                                        Set<Long> segmentsOnDisk) {
        Set<Long> named = new HashSet<>();
        for (CatalogRecord row : catalogRows) {
            if (row.crcValid()) {
                named.add(row.segmentId());
                named.add(segmentNumberOf(row.fileName()));
            }
        }
        List<ConsistencyFinding> findings = new ArrayList<>();
        for (Long segment : new TreeSet<>(segmentsOnDisk)) {
            if (named.contains(segment)) {
                continue;
            }
            findings.add(new ConsistencyFinding("segment-not-in-catalog",
                "segment_" + segment + ".dat", FindingSeverity.WARNING,
                "no catalog row names this segment file. Start-up rediscovers segment files "
                    + "from the directory, so nothing is lost, but the lifecycle state, "
                    + "integrated offset, source and epochs the catalog held for it are"));
        }
        return findings;
    }

    /**
     * Finds vector indexes and logs whose segment data file is gone.
     *
     * @param files          Every file verdict
     * @param segmentsOnDisk Segment numbers with a data file present
     * @return One finding per orphan
     */
    private List<ConsistencyFinding> orphanedIndexes(List<FileCheck> files,
                                                     Set<Long> segmentsOnDisk) {
        List<ConsistencyFinding> findings = new ArrayList<>();
        for (FileCheck file : files) {
            String name = nameOf(file.relative());
            FileKind kind = FileClassifier.classify(file.relative());
            if (!isSegmentSidecar(kind)) {
                continue;
            }
            long segment = FileClassifier.segmentNumber(name);
            if (segment < 0 || segmentsOnDisk.contains(segment)) {
                continue;
            }
            findings.add(new ConsistencyFinding("index-without-segment", file.relative(),
                FindingSeverity.DAMAGE, orphanDetail(kind, name, segment)));
        }
        return findings;
    }

    /**
     * Phrases what an orphaned file is orphaned from, in terms of what it was for.
     *
     * @param kind    What the file is
     * @param name    Its file name
     * @param segment The segment number it names
     * @return The detail
     */
    private static String orphanDetail(FileKind kind, String name, long segment) {
        String missing = "segment_" + segment + ".dat";
        if (kind == FileKind.WRITE_AHEAD_LOG) {
            return name + " is the write-ahead log of segment " + segment + ", and " + missing
                + " is not here. Any record in it that had not been integrated when the "
                + "segment was lost has nothing left to be replayed into, and there is no "
                + "way from here to tell which records those were";
        }
        return name + " indexes segment " + segment + ", and " + missing + " is not here. It "
            + "points at records that are not in this directory, so nothing can open it and "
            + "nothing will rebuild it in place";
    }

    /**
     * Finds routing slots pointing at segments that are not on disk.
     *
     * @param routingSlots   Canonical segment index per hash slot
     * @param segmentsOnDisk Segment numbers with a data file present
     * @return One finding per distinct missing target
     */
    private List<ConsistencyFinding> routingAgainstDisk(int[] routingSlots,
                                                        Set<Long> segmentsOnDisk) {
        if (routingSlots.length == 0 || segmentsOnDisk.isEmpty()) {
            return List.of();
        }
        Set<Integer> missing = new TreeSet<>();
        for (int canonical : routingSlots) {
            if (!segmentsOnDisk.contains((long) canonical)) {
                missing.add(canonical);
            }
        }
        List<ConsistencyFinding> findings = new ArrayList<>();
        for (Integer canonical : missing) {
            findings.add(new ConsistencyFinding("routing-names-missing-segment",
                "segment_" + canonical + ".dat", FindingSeverity.DAMAGE,
                "the routing directory sends some hash slots to segment " + canonical
                    + ", and segment_" + canonical + ".dat is not here. Those keys have "
                    + "nowhere to be read from or written to. This cannot be repaired "
                    + "automatically: which slots belong to which segment is not recoverable "
                    + "from the file names, which is why the directory is written down at all"));
        }
        return findings;
    }

    /**
     * Reports staging files a failed operation left behind.
     *
     * @param files Every file verdict
     * @return One finding per leftover
     */
    private List<ConsistencyFinding> leftovers(List<FileCheck> files) {
        List<ConsistencyFinding> findings = new ArrayList<>();
        for (FileCheck file : files) {
            if (FileClassifier.classify(file.relative()) != FileKind.STAGING_LEFTOVER) {
                continue;
            }
            findings.add(new ConsistencyFinding("staging-leftover", file.relative(),
                FindingSeverity.WARNING,
                "a write staged this file and never published it. Nothing reads it, and its "
                    + "presence means an operation was interrupted"));
        }
        return findings;
    }

    /**
     * Reports whether a kind is a file that belongs to a segment data file.
     *
     * @param kind What the file is
     * @return true for the log and the three index sidecars
     */
    private static boolean isSegmentSidecar(FileKind kind) {
        return kind == FileKind.WRITE_AHEAD_LOG || kind == FileKind.HNSW_GRAPH
            || kind == FileKind.HNSW_DOCUMENT_MAP || kind == FileKind.PQ_CODEBOOK;
    }

    /**
     * Collects the names of every file found directly in the data directory.
     *
     * @param files Every file verdict
     * @return The top-level file names
     */
    private Set<String> presentFileNames(List<FileCheck> files) {
        Set<String> names = new HashSet<>();
        for (FileCheck file : files) {
            if (!file.relative().contains("/") && Files.exists(file.path())) {
                names.add(file.relative());
            }
        }
        return names;
    }

    /**
     * Collects the segment numbers that have a data file.
     *
     * @param files Every file verdict
     * @return The segment numbers present
     */
    private Set<Long> segmentsOnDisk(List<FileCheck> files) {
        Set<Long> segments = new HashSet<>();
        for (FileCheck file : files) {
            String name = nameOf(file.relative());
            if (FileKind.SEGMENT_DATA_NAME.matcher(name).matches()) {
                segments.add(FileClassifier.segmentNumber(name));
            }
        }
        return segments;
    }

    /**
     * Reads the segment number out of a file name a catalog row carries.
     *
     * @param fileName The name, which may be null
     * @return The segment number, or -1
     */
    private static long segmentNumberOf(String fileName) {
        return fileName == null ? -1 : FileClassifier.segmentNumber(fileName);
    }

    /**
     * Returns the last element of a relative path.
     *
     * @param relative A "/"-separated relative path
     * @return Its final element
     */
    private static String nameOf(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? relative : relative.substring(slash + 1);
    }
}
