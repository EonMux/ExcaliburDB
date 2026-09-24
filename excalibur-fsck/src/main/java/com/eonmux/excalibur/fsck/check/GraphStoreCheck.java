package com.eonmux.excalibur.fsck.check;

import com.eonmux.excalibur.core.integrity.IntegrityReport;
import com.eonmux.excalibur.graph.erasure.EdgeStoreCompactor;
import com.eonmux.excalibur.graph.file.LogInspection;
import com.eonmux.excalibur.graph.integrity.ServedGraphIntegrity;
import com.eonmux.excalibur.graph.staging.GraphBuilder;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;
import com.eonmux.excalibur.graph.staging.LabelInterner;
import com.eonmux.excalibur.retrieval.build.GraphDirectoryBuilder;
import com.eonmux.excalibur.retrieval.lexical.LexicalIndexIntegrity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Checks each tenant's built graph, {@code <namespace>/graph}, and the directories a publish, a
 * build or an erasure compaction leaves beside it.
 *
 * <h2>What a built graph holds</h2>
 * <p>The edge store - a descriptor recording the length and CRC32C of each of its thirteen
 * columns, and a CRC32C of its own - the entity and relation label dictionaries, each index
 * carrying the length and CRC32C of its text and a CRC32C of itself, the entity vectors when the
 * graph was built with an embedder, whose header carries the CRC32C of the presence flags, of the
 * vectors and of itself, and two Lucene indexes, {@code labels/} and {@code documents/}, every
 * file of which ends in a CRC32 footer. Each is judged by the check the node runs before it maps
 * the file, reached through {@link ServedGraphIntegrity#inspect} and
 * {@link LexicalIndexIntegrity#verify}, so a verdict here is what the node would do with it: a
 * graph with any file {@code CORRUPT} or {@code MISSING} is one the node does not serve, and one
 * {@code LEGACY_UNPROTECTED}, written before these formats carried checksums, is served
 * unverified until the next build replaces it.</p>
 *
 * <h2>The directories beside it</h2>
 * <p>What each sibling is depends on its neighbours, as the node's own recovery decides it on
 * open:</p>
 * <ul>
 *   <li>{@code graph.previous} beside a served graph is a copy a completed publish set aside,
 *       and the node deletes it on its next open; alone, it is the only graph there is, and the
 *       node restores it. Only in the second case is it checked as a graph.</li>
 *   <li>{@code graph.retired} is treated the same way, and is also deleted when a
 *       {@code graph.previous} is beside it.</li>
 *   <li>{@code graph.building} is the scratch of a build that did not finish, which the next
 *       build deletes before it writes anything.</li>
 *   <li>{@code graph.compacting} is the scratch of an erasure compaction that did not finish.
 *       Its presence is itself a signal: on open the node reads it as an erasure that may have
 *       been interrupted, and applies what is still owed before it serves the graph.</li>
 * </ul>
 * <p>A copy the node is about to delete is reported unchecked rather than judged: damage in it
 * costs nothing, and it may hold facts an erasure has since removed from the served graph, so it
 * is left for the node to delete rather than offered to a repair that would preserve it.</p>
 *
 * <h2>Coverage</h2>
 * <p>Every file under one of these directories receives exactly one verdict here: the verdict on
 * the file itself, the verdict on the Lucene index it belongs to, or an unchecked verdict saying
 * why it was not judged. {@link GraphInspection#covers} lets a caller replace the per-file
 * verdicts with these.</p>
 *
 * <h2>Read-only</h2>
 * <p>Every check streams through a channel or reads through a non-mapping Lucene directory and
 * opens no writer, so it can be pointed at a live data directory or a snapshot's payload.</p>
 */
public final class GraphStoreCheck {

    /** Name the whole graph goes by in a report. */
    public static final String FORMAT = "built graph";

    /**
     * Name of the directory a namespace's built graph is served from.
     *
     * <p>Stated here because the constant that decides it lives in the API module's tenant
     * layout, which this tool does not depend on: it reads directories, not deployments.</p>
     */
    public static final String GRAPH_DIRECTORY = "graph";

    private static final String PREVIOUS = GRAPH_DIRECTORY + GraphBuilder.PREVIOUS_SUFFIX;
    private static final String RETIRED = GRAPH_DIRECTORY + GraphBuilder.RETIRED_SUFFIX;
    private static final String BUILDING = GRAPH_DIRECTORY + GraphBuilder.BUILD_SUFFIX;
    private static final String COMPACTING =
        EdgeStoreCompactor.scratchFor(Path.of(GRAPH_DIRECTORY)).getFileName().toString();

    /** Every directory name this check owns, the served graph first. */
    private static final List<String> DIRECTORIES =
        List.of(GRAPH_DIRECTORY, PREVIOUS, RETIRED, BUILDING, COMPACTING);

    private static final Set<String> DIRECTORY_NAMES = Set.copyOf(DIRECTORIES);

    private GraphStoreCheck() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reports whether a file sits in a built graph or one of the directories beside it.
     *
     * @param relative A file's path relative to the data directory, "/"-separated
     * @return true for anything under {@code <namespace>/graph} or a sibling this check owns
     */
    public static boolean isGraphPart(String relative) {
        String[] segments = relative.split("/");
        return segments.length > 2 && DIRECTORY_NAMES.contains(segments[1]);
    }

    /**
     * Returns the directory, relative to the data directory, whose inspection covers a file.
     *
     * @param relative A path for which {@link #isGraphPart} holds
     * @return {@code <namespace>/<directory>}
     * @throws IllegalArgumentException if the path is not in a graph directory
     */
    public static String rootOf(String relative) {
        if (!isGraphPart(relative)) {
            throw new IllegalArgumentException("Not in a graph directory: " + relative);
        }
        String[] segments = relative.split("/");
        return segments[0] + "/" + segments[1];
    }

    /**
     * Returns the displaced graphs beside a graph directory, other than the directory itself.
     *
     * <p>What decides whether moving a graph directory away is safe. The node restores a
     * displaced {@code graph.previous} or {@code graph.retired} when nothing is served beside it,
     * and one beside a served graph may be a copy an erasure compaction replaced - holding the
     * very facts the erasure removed, with the obligation already discharged. Moving the served
     * graph away while such a copy is beside it would have the node restore that copy and serve
     * them again.</p>
     *
     * @param directory A graph directory, served or displaced
     * @return The {@code graph.previous} and {@code graph.retired} directories beside it
     * @throws NullPointerException if directory is null
     */
    public static List<Path> displacedBeside(Path directory) {
        Objects.requireNonNull(directory, "directory");
        Path namespace = directory.toAbsolutePath().getParent();
        if (namespace == null) {
            return List.of();
        }
        return Stream.of(PREVIOUS, RETIRED)
            .map(namespace::resolve)
            .filter(sibling -> !sibling.equals(directory.toAbsolutePath()))
            .filter(Files::isDirectory)
            .toList();
    }

    /**
     * Returns the label interners in the namespace's staging directory whose open would have to
     * cut damage off, and so would ask the graph's dictionaries whether it may.
     *
     * <p>The other thing that decides whether moving a graph directory away is safe. An interner
     * with a damaged record is cut back to it by the node only when nothing holds an id at or
     * past it, and a graph's dictionaries are one of those holders - sometimes the only one, and
     * the record of the ids queries have answered with. With the graph gone the node would cut
     * without asking it, and could give an id the graph named to a new label; so while any
     * interner beside it has damage to cut, the graph stays where it is. An interner that cannot
     * be read at all is counted too, since what its open would do cannot be told.</p>
     *
     * @param directory A graph directory, served or displaced
     * @return The interners, entity labels first; empty when neither has anything to cut
     * @throws NullPointerException if directory is null
     */
    public static List<Path> internersWithDamageBeside(Path directory) {
        Objects.requireNonNull(directory, "directory");
        Path namespace = directory.toAbsolutePath().getParent();
        if (namespace == null) {
            return List.of();
        }
        Path staging = namespace.resolve(GRAPH_DIRECTORY + GraphStagingCheck.DIRECTORY_SUFFIX);
        List<Path> damaged = new ArrayList<>();
        for (String name : List.of(GraphStagingStore.ENTITY_LABELS,
                GraphStagingStore.RELATION_LABELS)) {
            Path interner = staging.resolve(name);
            if (!Files.isRegularFile(interner)) {
                continue;
            }
            try {
                LogInspection inspection = LabelInterner.inspect(interner);
                // A creation cut short held no id, and its open asks nobody.
                if (inspection.hasTornTail() && inspection.intactBytes() > 0) {
                    damaged.add(interner);
                }
            } catch (IOException | RuntimeException e) {
                damaged.add(interner);
            }
        }
        return List.copyOf(damaged);
    }

    /**
     * Reports whether a path names a directory that holds a whole graph: the served one or a
     * displaced copy of it.
     *
     * @param path A path
     * @return true for {@code graph}, {@code graph.previous} or {@code graph.retired}
     */
    public static boolean isGraphDirectory(Path path) {
        Path name = path.getFileName();
        return name != null && Set.of(GRAPH_DIRECTORY, PREVIOUS, RETIRED)
            .contains(name.toString());
    }

    /**
     * Checks every graph directory under a data directory.
     *
     * @param dataDirectory The data directory, or a snapshot's payload root
     * @return One inspection per graph directory found, in namespace then directory order
     * @throws NullPointerException if dataDirectory is null
     */
    public static List<GraphInspection> inspect(Path dataDirectory) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        List<GraphInspection> inspections = new ArrayList<>();
        for (Path namespace : namespaces(dataDirectory)) {
            for (String name : DIRECTORIES) {
                Path directory = namespace.resolve(name);
                if (Files.isDirectory(directory)) {
                    String root = namespace.getFileName() + "/" + name;
                    inspections.add(check(directory, root, role(namespace, name)));
                }
            }
        }
        return List.copyOf(inspections);
    }

    /**
     * Lists the directories directly under the data directory, in name order.
     *
     * @param dataDirectory The data directory
     * @return Each subdirectory, or nothing if the directory cannot be listed
     */
    private static List<Path> namespaces(Path dataDirectory) {
        if (!Files.isDirectory(dataDirectory)) {
            return List.of();
        }
        try (Stream<Path> listing = Files.list(dataDirectory)) {
            // A restore's scratch is a copy of a whole data directory, not a namespace, and is
            // reported as the restore's by the scan.
            return listing.filter(Files::isDirectory)
                .filter(path -> !com.eonmux.excalibur.core.datadir.ReservedNames.isReserved(
                    path.getFileName().toString()))
                .sorted().toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
    }

    /**
     * What the node does with one directory on its next open.
     *
     * @param namespace The namespace directory
     * @param name      The directory's name within it
     * @return Its role
     */
    private static Role role(Path namespace, String name) {
        boolean served = Files.isDirectory(namespace.resolve(GRAPH_DIRECTORY));
        boolean previous = Files.isDirectory(namespace.resolve(PREVIOUS));
        if (name.equals(GRAPH_DIRECTORY)) {
            return Role.SERVED;
        }
        if (name.equals(PREVIOUS)) {
            return served ? Role.DELETED_ON_OPEN : Role.RESTORED_ON_OPEN;
        }
        if (name.equals(RETIRED)) {
            return served || previous ? Role.DELETED_ON_OPEN : Role.RESTORED_ON_OPEN;
        }
        return name.equals(BUILDING) ? Role.BUILD_SCRATCH : Role.COMPACTION_SCRATCH;
    }

    /**
     * Checks one graph directory.
     *
     * @param directory The directory
     * @param root      Its path relative to the data directory
     * @param role      What the node does with it
     * @return A verdict on every file under it
     */
    private static GraphInspection check(Path directory, String root, Role role) {
        List<Path> files = filesUnder(directory);
        List<FileCheck> verdicts = new ArrayList<>();
        if (role.judged()) {
            for (IntegrityReport report : ServedGraphIntegrity.inspect(directory)) {
                verdicts.add(verdict(report, directory, root));
            }
            for (String index : List.of(GraphDirectoryBuilder.LABEL_INDEX,
                    GraphDirectoryBuilder.DOCUMENT_INDEX)) {
                verdicts.add(verdict(LexicalIndexIntegrity.verify(directory.resolve(index)),
                    directory, root));
            }
        }
        GraphInspection judged = new GraphInspection(root, role, verdicts);
        for (Path file : files) {
            String relative = root + "/" + relativeTo(directory, file);
            if (!judged.judges(relative)) {
                verdicts.add(unjudged(file, relative, role));
            }
        }
        return new GraphInspection(root, role, verdicts);
    }

    /**
     * Converts a verifier's verdict on a file or an index directory in the graph.
     *
     * @param report    The verdict
     * @param directory The graph directory
     * @param root      Its path relative to the data directory
     * @return The verdict in this tool's terms
     */
    private static FileCheck verdict(IntegrityReport report, Path directory, String root) {
        Path file = report.file();
        String relative = root + "/" + relativeTo(directory, file);
        return IntegrityReports.from(report, file, relative, null, sizeOf(file));
    }

    /**
     * Builds the verdict on a file this check did not judge, saying why.
     *
     * @param file     The file
     * @param relative Its path relative to the data directory
     * @param role     What the node does with the directory it sits in
     * @return An unchecked verdict
     */
    private static FileCheck unjudged(Path file, String relative, Role role) {
        return new FileCheck(file, relative, FORMAT, null, CheckStatus.UNCHECKED,
            role.unjudgedDetail(), sizeOf(file));
    }

    /**
     * Lists every regular file under a directory.
     *
     * @param directory The directory
     * @return The files, in path order, or nothing if the tree cannot be walked
     */
    private static List<Path> filesUnder(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
    }

    /**
     * Renders a path under a directory with "/" separators.
     *
     * @param directory The directory
     * @param file      A path beneath it, or the directory itself
     * @return The relative path
     */
    private static String relativeTo(Path directory, Path file) {
        return directory.relativize(file).toString().replace(java.io.File.separatorChar, '/');
    }

    /**
     * Sizes a file or directory, or reports it absent.
     *
     * @param path The path
     * @return Its size, 0 for a directory, or -1 when it is not there or cannot be sized
     */
    private static long sizeOf(Path path) {
        try {
            return Files.isDirectory(path) ? 0 : Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }

    /** What the node does with a graph directory the next time it opens the namespace. */
    public enum Role {

        /** The graph the node serves, once every file of it has verified. */
        SERVED,

        /** A displaced graph with nothing served beside it, which the node restores. */
        RESTORED_ON_OPEN,

        /** A displaced copy beside the graph that supersedes it, which the node deletes. */
        DELETED_ON_OPEN,

        /** A build's scratch, which the node deletes on open and before discharging an erasure. */
        BUILD_SCRATCH,

        /**
         * An erasure compaction's scratch, whose presence the node reads on open as the sign of
         * an interrupted erasure while one is owed, and deletes otherwise.
         */
        COMPACTION_SCRATCH;

        /**
         * Reports whether the directory is checked as a graph.
         *
         * @return true for the graph the node serves or is about to restore
         */
        public boolean judged() {
            return this == SERVED || this == RESTORED_ON_OPEN;
        }

        /**
         * Explains an unchecked verdict on a file in a directory of this role.
         *
         * @return The detail
         */
        private String unjudgedDetail() {
            return switch (this) {
                case SERVED, RESTORED_ON_OPEN -> "not a file a build writes into the graph, so "
                    + "nothing reads it and nothing verifies it; the next build replaces the "
                    + "whole directory, this file with it";
                case DELETED_ON_OPEN -> "a copy of the graph a completed publish or erasure "
                    + "compaction set aside, beside the graph that replaced it. The node deletes "
                    + "it on its next open and never reads it, so it is not judged; it may hold "
                    + "facts an erasure has since removed, so leave it for the node to delete "
                    + "rather than moving it anywhere";
                case BUILD_SCRATCH -> "scratch of a graph build that did not finish. Nothing "
                    + "reads it, and the node deletes it when the namespace next opens, and "
                    + "again before it discharges an erasure; it may hold facts an erasure has "
                    + "since removed, so leave it for the node to delete rather than moving it "
                    + "anywhere";
                case COMPACTION_SCRATCH -> "scratch of an erasure compaction that did not "
                    + "finish. Nothing reads its contents, but while erasures are outstanding "
                    + "against the graph its presence tells the node on open that an erasure "
                    + "may have been interrupted, and the node applies what is owed before it "
                    + "serves the graph, deleting this before it discharges them; with nothing "
                    + "owed, the node deletes it when the namespace next opens. Leave it for "
                    + "the node rather than moving it anywhere";
            };
        }
    }

    /**
     * The verdicts on one graph directory.
     *
     * @param root     The directory's path relative to the data directory
     * @param role     What the node does with it
     * @param verdicts A verdict on every file under it, and one on each Lucene index in it
     */
    public record GraphInspection(String root, Role role, List<FileCheck> verdicts) {

        /**
         * Canonical constructor, defensively copying the verdicts.
         *
         * @throws NullPointerException if any component is null
         */
        public GraphInspection {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(role, "role");
            verdicts = List.copyOf(Objects.requireNonNull(verdicts, "verdicts"));
        }

        /**
         * Reports whether a file is accounted for by these verdicts.
         *
         * <p>Every file under the directory is: each receives a verdict of its own or sits in a
         * Lucene index whose verdict covers it.</p>
         *
         * @param relative A file's path relative to the data directory, "/"-separated
         * @return true if a verdict here speaks for it
         */
        public boolean covers(String relative) {
            return relative.startsWith(root + "/");
        }

        /**
         * Reports whether a file was judged, by its own verdict or its index's.
         *
         * @param relative A file's path relative to the data directory
         * @return true if a verdict names it or the index directory it sits in
         */
        private boolean judges(String relative) {
            return verdicts.stream().anyMatch(verdict -> relative.equals(verdict.relative())
                || relative.startsWith(verdict.relative() + "/"));
        }
    }
}
