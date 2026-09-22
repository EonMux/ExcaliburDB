package com.eonmux.excalibur.graph.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.eonmux.excalibur.graph.file.Durability;
import com.eonmux.excalibur.graph.format.ColumnFileWriter;
import com.eonmux.excalibur.graph.format.EdgeColumn;
import com.eonmux.excalibur.graph.format.EdgeStoreDescriptor;
import com.eonmux.excalibur.graph.build.BuildCancellation;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.temporal.SupersessionPolicy;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds an edge store: sorts the edges, lays down both indexes and every column, and writes the
 * descriptor last.
 *
 * <p>The descriptor is written last and moved into place atomically. Until it exists the directory
 * is not a store, so a build killed halfway leaves something that refuses to open rather than
 * something that opens and returns wrong answers.
 *
 * <p>Edges are buffered in parallel primitive arrays — never row objects — so a build holds one
 * allocation per column rather than one per edge, and the sort permutes indices rather than moving
 * records. This is the same columnar shape the store has on disk, which is what lets the write
 * phase be a straight walk in permutation order.
 *
 * <h2>Size</h2>
 * This implementation buffers in memory and therefore tops out at {@link Integer#MAX_VALUE} edges.
 * That is a property of the buffering, not the format — the on-disk offsets are 64-bit and the
 * reader has no such limit — so a larger corpus needs an external merge sort feeding the same
 * column writers, not a format change.
 *
 * <p>Not thread-safe. One writer per store being built.
 */
public final class EdgeStoreWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(EdgeStoreWriter.class);

    private static final int INITIAL_CAPACITY = 1024;

    private int[] subjects = new int[INITIAL_CAPACITY];
    private int[] relations = new int[INITIAL_CAPACITY];
    private int[] objects = new int[INITIAL_CAPACITY];
    private int[] validFrom = new int[INITIAL_CAPACITY];
    private int[] validTo = new int[INITIAL_CAPACITY];
    private byte[] sourceTypes = new byte[INITIAL_CAPACITY];
    private long[] sourceIds = new long[INITIAL_CAPACITY];
    private byte[] confidences = new byte[INITIAL_CAPACITY];
    private short[] extractorVersions = new short[INITIAL_CAPACITY];

    private int count;
    private int maxEntityId = -1;
    private SupersessionPolicy supersessionPolicy = SupersessionPolicy.NONE;

    /**
     * Sets which relations close their predecessor when a new statement arrives.
     *
     * <p>Defaults to {@link SupersessionPolicy#NONE}. Applied during {@link #build}, after every
     * edge is known, because whether a statement is superseded depends on statements that may
     * arrive after it.
     */
    public EdgeStoreWriter supersessionPolicy(SupersessionPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        this.supersessionPolicy = policy;
        return this;
    }

    /** Adds an edge that holds over all time, with no recorded provenance. */
    public EdgeStoreWriter add(int subject, int relation, int object) {
        return add(subject, relation, object, Integer.MIN_VALUE, TemporalFilter.OPEN,
            Provenance.UNKNOWN);
    }

    /**
     * Adds an edge.
     *
     * @param subject   subject entity id, non-negative
     * @param relation  relation id, non-negative
     * @param object    object entity id, non-negative
     * @param validFrom first day the fact held, days since epoch
     * @param validTo   first day it no longer held, or {@link TemporalFilter#OPEN}
     * @param provenance where the edge came from; never null, use {@link Provenance#UNKNOWN}
     */
    public EdgeStoreWriter add(int subject, int relation, int object,
                               int validFrom, int validTo, Provenance provenance) {
        if (subject < 0) {
            throw new IllegalArgumentException("subject must be non-negative, got " + subject);
        }
        if (object < 0) {
            throw new IllegalArgumentException("object must be non-negative, got " + object);
        }
        RelationSelector.checkRelation(relation);
        TemporalFilter.checkInterval(validFrom, validTo);
        if (provenance == null) {
            throw new IllegalArgumentException(
                "provenance must not be null; use Provenance.UNKNOWN to record that it is unknown");
        }
        if (count == Integer.MAX_VALUE) {
            throw new IllegalStateException(
                "in-memory edge buffer is full at " + count
                    + " edges; a larger store needs an external merge sort feeding ColumnFileWriter");
        }

        ensureCapacity(count + 1);
        subjects[count] = subject;
        relations[count] = relation;
        objects[count] = object;
        this.validFrom[count] = validFrom;
        this.validTo[count] = validTo;
        sourceTypes[count] = provenance.sourceType().toByte();
        sourceIds[count] = provenance.sourceId();
        confidences[count] = provenance.confidenceByte();
        extractorVersions[count] = provenance.extractorVersionShort();
        count++;

        maxEntityId = Math.max(maxEntityId, Math.max(subject, object));
        return this;
    }

    /** How many edges have been added. */
    public int edgeCount() {
        return count;
    }

    private void ensureCapacity(int required) {
        if (required <= subjects.length) {
            return;
        }
        int grown = Math.max(required, subjects.length + (subjects.length >> 1));
        subjects = Arrays.copyOf(subjects, grown);
        relations = Arrays.copyOf(relations, grown);
        objects = Arrays.copyOf(objects, grown);
        validFrom = Arrays.copyOf(validFrom, grown);
        validTo = Arrays.copyOf(validTo, grown);
        sourceTypes = Arrays.copyOf(sourceTypes, grown);
        sourceIds = Arrays.copyOf(sourceIds, grown);
        confidences = Arrays.copyOf(confidences, grown);
        extractorVersions = Arrays.copyOf(extractorVersions, grown);
    }

    /**
     * Writes the store into {@code directory}, creating it if needed.
     *
     * @param entityCount how many entity ids the store addresses; must exceed every id used.
     *                    Passed rather than derived so a store can reserve ids that have no edges
     *                    yet — deriving it would make the offset array's length depend on which
     *                    entities happened to be mentioned.
     */
    public void build(Path directory, long entityCount) throws IOException {
        build(directory, entityCount, Durability.DEVICE);
    }

    /**
     * As above, with how the store is forced to the device under the caller's control.
     *
     * <p>The columns are forced before the descriptor is written, which is what turns "the
     * descriptor is written last" from a statement about the order of two writes into the
     * guarantee the format actually needs. Until this, the ordering held against the page cache
     * and nothing else: a power loss could leave the descriptor durable over columns that were
     * not, producing the one outcome the ordering exists to prevent — a directory that opens as
     * a store and reads truncated columns.
     *
     * @param directory   where the store goes
     * @param entityCount how many entity ids the store addresses
     * @param durability  how the columns and the descriptor are forced
     * @throws IOException if any column or the descriptor cannot be written or forced
     * @throws IllegalArgumentException if {@code durability} is null, or the counts do not agree
     * @throws com.eonmux.excalibur.graph.build.BuildCancelledException if the graph build on this
     *         thread was asked to stop, which it is between phases and never part-way through
     *         one
     */
    public void build(Path directory, long entityCount, Durability durability) throws IOException {
        if (durability == null) {
            throw new IllegalArgumentException("durability must not be null");
        }
        if (entityCount < 0) {
            throw new IllegalArgumentException("entityCount must not be negative: " + entityCount);
        }
        if (maxEntityId >= entityCount) {
            throw new IllegalArgumentException("entityCount " + entityCount
                + " does not cover entity id " + maxEntityId + " used by an edge");
        }
        Files.createDirectories(directory);

        // Supersession first: it changes valid intervals, and every column written below must
        // reflect the closed intervals rather than the ones originally supplied.
        int superseded = applySupersession();
        if (superseded > 0) {
            LOGGER.debug("closed {} superseded intervals", superseded);
        }

        // Between the phases, which is where a build that has been asked to stop ends. Each is
        // a sort or one pass over the edges, and none of them has written anything the caller
        // serves: the directory is not a store until the descriptor below exists.
        BuildCancellation.checkpoint();
        int[] forwardOrder = sortForward();
        writeForward(directory, forwardOrder, entityCount);

        BuildCancellation.checkpoint();
        int[] reverseOrder = sortReverse();
        writeReverse(directory, forwardOrder, reverseOrder, entityCount);

        BuildCancellation.checkpoint();

        // Every column, on the device, before the file that says they are a store exists. The
        // enum is walked rather than a list kept alongside, so a column added later is covered
        // by this without anybody having to remember.
        for (EdgeColumn column : EdgeColumn.values()) {
            durability.fsyncFile(directory.resolve(column.fileName()));
        }

        // Last, and atomically. Before this line the directory is not a store.
        EdgeStoreDescriptor.of(entityCount, count).write(directory, durability);
        LOGGER.info("built edge store at {}: {} entities, {} edges", directory, entityCount, count);
    }

    /**
     * Closes the interval of every statement that a later statement on the same
     * (subject, relation) supersedes.
     *
     * <p>Only for relations the policy calls single-valued. Applied by walking each group in
     * {@code validFrom} order and closing an open interval at the next statement's start.
     *
     * <p>An interval that already has an explicit end is left alone: the source said when the fact
     * stopped holding, and that is better information than an inference from the next statement's
     * arrival.
     *
     * @return how many intervals were closed
     */
    private int applySupersession() {
        if (count == 0 || supersessionPolicy == SupersessionPolicy.NONE) {
            return 0;
        }
        int[] order = identity();
        IndexSort.sort(order, (a, b) -> {
            int c = Integer.compare(subjects[a], subjects[b]);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(relations[a], relations[b]);
            if (c != 0) {
                return c;
            }
            return Integer.compare(validFrom[a], validFrom[b]);
        });

        int closed = 0;
        int groupStart = 0;
        for (int i = 1; i <= count; i++) {
            boolean endOfGroup = i == count
                || subjects[order[i]] != subjects[order[groupStart]]
                || relations[order[i]] != relations[order[groupStart]];
            if (!endOfGroup) {
                continue;
            }
            if (supersessionPolicy.isSingleValued(relations[order[groupStart]])) {
                closed += closeGroup(order, groupStart, i);
            }
            groupStart = i;
        }
        return closed;
    }

    /**
     * Closes the open intervals of one single-valued (subject, relation) group.
     *
     * <p>The group arrives sorted ascending by {@code validFrom}, so it is a sequence of runs of
     * equal start days. Every statement in a run is superseded by the <em>next distinct</em>
     * start to its right, and the runs are walked from the right so that start is already known.
     *
     * <p>Two statements starting on the same day carry no ordering information about which
     * supersedes which, so neither closes the other — but both are closed by anything that
     * starts later. Comparing only adjacent pairs got the first half right and the second half
     * wrong: in "Acme day 100, Beta day 100, Gamma day 900" it closed Beta at 900 and left Acme
     * open, so a query two years later returned a job the subject had left, presented as current
     * and sitting beside the correct answer.
     *
     * @return how many intervals were closed
     */
    private int closeGroup(int[] order, int from, int to) {
        int closed = 0;
        boolean haveSuccessor = false;
        int successorStart = TemporalFilter.OPEN;

        int i = to - 1;
        while (i >= from) {
            int runStart = validFrom[order[i]];
            int runFrom = i;
            while (runFrom > from && validFrom[order[runFrom - 1]] == runStart) {
                runFrom--;
            }

            if (haveSuccessor) {
                for (int k = runFrom; k <= i; k++) {
                    int edge = order[k];
                    // An interval with an explicit end is left alone: the source said when the
                    // fact stopped holding, which beats an inference from the next arrival.
                    if (validTo[edge] == TemporalFilter.OPEN) {
                        validTo[edge] = successorStart;
                        closed++;
                    }
                }
            }

            haveSuccessor = true;
            successorStart = runStart;
            i = runFrom - 1;
        }
        return closed;
    }

    /**
     * Order by (subject, relation, object).
     *
     * <p>Jointly, so that a relation-filtered neighbour scan is a binary search to a contiguous run
     * rather than a walk of the whole adjacency list testing every element. Object breaks ties so
     * the output is deterministic for a given input set, which is what makes a rebuilt store
     * byte-identical and therefore diffable.
     */
    private int[] sortForward() {
        int[] order = identity();
        IndexSort.sort(order, (a, b) -> {
            int c = Integer.compare(subjects[a], subjects[b]);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(relations[a], relations[b]);
            if (c != 0) {
                return c;
            }
            return Integer.compare(objects[a], objects[b]);
        });
        return order;
    }

    /** Order by (object, relation, subject): the same principle, transposed. */
    private int[] sortReverse() {
        int[] order = identity();
        IndexSort.sort(order, (a, b) -> {
            int c = Integer.compare(objects[a], objects[b]);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(relations[a], relations[b]);
            if (c != 0) {
                return c;
            }
            return Integer.compare(subjects[a], subjects[b]);
        });
        return order;
    }

    private int[] identity() {
        int[] order = new int[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        return order;
    }

    private void writeForward(Path directory, int[] order, long entityCount) throws IOException {
        writeColumn(directory, EdgeColumn.EDGES_FWD, order, (w, e) -> w.putInt(objects[e]));
        writeColumn(directory, EdgeColumn.REL_FWD, order, (w, e) -> w.putInt(relations[e]));
        writeOffsets(directory, EdgeColumn.OFFSETS_FWD, order, entityCount, i -> subjects[i]);

        // Every per-edge column is laid out in forward order, and this is the only place that is
        // decided. Reverse traversal reaches them through PERM_REV rather than through a copy.
        writeColumn(directory, EdgeColumn.VALID_FROM, order, (w, e) -> w.putInt(validFrom[e]));
        writeColumn(directory, EdgeColumn.VALID_TO, order, (w, e) -> w.putInt(validTo[e]));
        writeColumn(directory, EdgeColumn.SOURCE_TYPE, order, (w, e) -> w.putByte(sourceTypes[e]));
        writeColumn(directory, EdgeColumn.SOURCE_ID, order, (w, e) -> w.putLong(sourceIds[e]));
        writeColumn(directory, EdgeColumn.EXTRACTION_CONFIDENCE, order,
            (w, e) -> w.putByte(confidences[e]));
        writeColumn(directory, EdgeColumn.EXTRACTOR_VERSION, order,
            (w, e) -> w.putShort(extractorVersions[e]));
    }

    private void writeReverse(Path directory, int[] forwardOrder, int[] reverseOrder,
                              long entityCount) throws IOException {
        writeColumn(directory, EdgeColumn.EDGES_REV, reverseOrder, (w, e) -> w.putInt(subjects[e]));
        writeColumn(directory, EdgeColumn.REL_REV, reverseOrder, (w, e) -> w.putInt(relations[e]));
        writeOffsets(directory, EdgeColumn.OFFSETS_REV, reverseOrder, entityCount, i -> objects[i]);

        // Position of each original edge in forward order, so a reverse position can name the
        // forward slot its columns live at.
        int[] forwardPosition = new int[count];
        for (int position = 0; position < count; position++) {
            forwardPosition[forwardOrder[position]] = position;
        }
        try (ColumnFileWriter writer = ColumnFileWriter.create(directory, EdgeColumn.PERM_REV)) {
            for (int i = 0; i < count; i++) {
                writer.putLong(forwardPosition[reverseOrder[i]]);
            }
        }
    }

    /** Writes one per-edge column by walking {@code order} and emitting one element per edge. */
    private void writeColumn(Path directory, EdgeColumn column, int[] order, ElementWriter emitter)
            throws IOException {
        try (ColumnFileWriter writer = ColumnFileWriter.create(directory, column)) {
            for (int i = 0; i < count; i++) {
                emitter.write(writer, order[i]);
            }
        }
    }

    /**
     * Writes a CSR offset array: for each entity, where its run of edges begins.
     *
     * <p>Holds {@code entityCount + 1} entries so that an entity's run is always
     * {@code [offsets[e], offsets[e + 1])} with no special case for the last entity. Entities with
     * no edges get an empty run rather than a missing one, which is why this walks every entity id
     * rather than only those that appear.
     */
    private void writeOffsets(Path directory, EdgeColumn column, int[] order, long entityCount,
                              KeyExtractor key) throws IOException {
        try (ColumnFileWriter writer = ColumnFileWriter.create(directory, column)) {
            int position = 0;
            for (long entity = 0; entity < entityCount; entity++) {
                writer.putLong(position);
                while (position < count && key.of(order[position]) == entity) {
                    position++;
                }
            }
            writer.putLong(position);
            if (position != count) {
                throw new IllegalStateException("offset array for " + column + " covered "
                    + position + " of " + count + " edges; an edge references an entity id at or "
                    + "beyond entityCount " + entityCount);
            }
        }
    }

    @FunctionalInterface
    private interface ElementWriter {
        void write(ColumnFileWriter writer, int edge) throws IOException;
    }

    @FunctionalInterface
    private interface KeyExtractor {
        int of(int edge);
    }
}
