package com.eonmux.excalibur.graph.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eonmux.excalibur.graph.file.Durability;
import com.eonmux.excalibur.graph.format.ColumnDigest;
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
 * This implementation buffers in memory and therefore tops out at {@link #MAX_EDGES} edges
 * (2<sup>31</sup> - 9). That is a property of the buffering, not the format — the on-disk offsets
 * are 64-bit and the reader has no such limit — so a larger corpus needs an external merge sort
 * feeding the same column writers, not a format change.
 *
 * <p>The heap a build needs follows from the columns. Buffered, an edge costs 36 bytes: six
 * {@code int} columns (subject, relation, object, valid-from, valid-to, stated-on) at 24, the
 * source id at 8, the extractor version at 2, and the source type and confidence at 1 each. The
 * write phase adds three {@code int} permutations over every edge at once - forward order, reverse
 * order, and either the merge sort's scratch or the forward position of each reverse slot - for
 * 12 more, so 48 bytes per edge at the peak. The buffer grows by half again, so up to a third of
 * its capacity can be slack: about 66 bytes per edge in the worst case, or 4.8 to 6.6 GB for a
 * hundred million edges.</p>
 *
 * <p>Not thread-safe. One writer per store being built.
 */
public final class EdgeStoreWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(EdgeStoreWriter.class);

    private static final int INITIAL_CAPACITY = 1024;

    /**
     * Most edges one build can buffer.
     *
     * <p>Every column is one Java array indexed by edge, and the largest array length every JVM
     * will allocate is a few elements short of {@link Integer#MAX_VALUE} - asking for more fails
     * with an {@link OutOfMemoryError} however much heap is free.</p>
     */
    static final int MAX_EDGES = Integer.MAX_VALUE - 8;

    private int[] subjects = new int[INITIAL_CAPACITY];
    private int[] relations = new int[INITIAL_CAPACITY];
    private int[] objects = new int[INITIAL_CAPACITY];
    private int[] validFrom = new int[INITIAL_CAPACITY];
    private int[] validTo = new int[INITIAL_CAPACITY];
    private int[] statedOn = new int[INITIAL_CAPACITY];
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
        return add(subject, relation, object, TemporalFilter.UNDATED, TemporalFilter.OPEN,
            Provenance.UNKNOWN);
    }

    /**
     * Adds an edge whose statement carries no date of its own.
     *
     * @param subject   subject entity id, non-negative
     * @param relation  relation id, non-negative
     * @param object    object entity id, non-negative
     * @param validFrom first day the fact held, days since epoch, or
     *                  {@link TemporalFilter#UNDATED}
     * @param validTo   first day it no longer held, or {@link TemporalFilter#OPEN}
     * @param provenance where the edge came from; never null, use {@link Provenance#UNKNOWN}
     */
    public EdgeStoreWriter add(int subject, int relation, int object,
                               int validFrom, int validTo, Provenance provenance) {
        return add(subject, relation, object, validFrom, validTo, TemporalFilter.UNDATED,
            provenance);
    }

    /**
     * Adds an edge, with the day its statement was made.
     *
     * <p>The stated-on day is not stored. It orders the statements of a single-valued relation
     * whose values do not say when they began, and once supersession has closed the intervals
     * that ordering implied, the intervals are the whole of what it decided. The order edges are
     * added in is the last tie-break, so a caller replaying statements adds them in the order they
     * were made.
     *
     * @param subject   subject entity id, non-negative
     * @param relation  relation id, non-negative
     * @param object    object entity id, non-negative
     * @param validFrom first day the fact held, days since epoch, or
     *                  {@link TemporalFilter#UNDATED}
     * @param validTo   first day it no longer held, or {@link TemporalFilter#OPEN}
     * @param statedOn  the day the statement was made, days since epoch, or
     *                  {@link TemporalFilter#UNDATED}
     * @param provenance where the edge came from; never null, use {@link Provenance#UNKNOWN}
     */
    public EdgeStoreWriter add(int subject, int relation, int object,
                               int validFrom, int validTo, int statedOn, Provenance provenance) {
        TemporalFilter.checkInterval(validFrom, validTo);
        return append(subject, relation, object, validFrom, validTo, statedOn, provenance);
    }

    /**
     * Adds an edge exactly as a built store holds it, for a store rewritten from another.
     *
     * <p>Unlike {@link #add}, accepts an empty interval: supersession closes a statement
     * superseded on the day it began at that same day, and a rewrite that refused it would make
     * every store holding one impossible to compact. See
     * {@link TemporalFilter#checkStoredInterval}.
     *
     * @param subject   subject entity id, non-negative
     * @param relation  relation id, non-negative
     * @param object    object entity id, non-negative
     * @param validFrom first day the fact held, as stored
     * @param validTo   first day it no longer held, as stored
     * @param provenance where the edge came from
     */
    public EdgeStoreWriter addStored(int subject, int relation, int object,
                                     int validFrom, int validTo, Provenance provenance) {
        TemporalFilter.checkStoredInterval(validFrom, validTo);
        return append(subject, relation, object, validFrom, validTo, TemporalFilter.UNDATED,
            provenance);
    }

    private EdgeStoreWriter append(int subject, int relation, int object,
                                   int validFrom, int validTo, int statedOn,
                                   Provenance provenance) {
        if (subject < 0) {
            throw new IllegalArgumentException("subject must be non-negative, got " + subject);
        }
        if (object < 0) {
            throw new IllegalArgumentException("object must be non-negative, got " + object);
        }
        RelationSelector.checkRelation(relation);
        if (statedOn == TemporalFilter.OPEN) {
            throw new IllegalArgumentException(
                "statedOn must be a day or UNDATED, not the open-interval sentinel");
        }
        if (provenance == null) {
            throw new IllegalArgumentException(
                "provenance must not be null; use Provenance.UNKNOWN to record that it is unknown");
        }
        if (count == MAX_EDGES) {
            throw new IllegalStateException(fullMessage());
        }

        ensureCapacity(count + 1);
        subjects[count] = subject;
        relations[count] = relation;
        objects[count] = object;
        this.validFrom[count] = validFrom;
        this.validTo[count] = validTo;
        this.statedOn[count] = statedOn;
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

    /**
     * The capacity the buffer grows to when it has to hold {@code required} edges.
     *
     * <p>Half again, so the copying amortises to a constant per edge, and computed in
     * {@code long}: half again of anything past about 1.43 billion is past the {@code int} range,
     * and a sum that wrapped negative would lose to {@code required} and grow the buffer one
     * slot per edge, copying every column each time. Clamped to {@link #MAX_EDGES}.</p>
     *
     * @param current  The capacity now
     * @param required How many edges it has to hold
     * @return The new capacity, at least {@code required}
     * @throws IllegalStateException if {@code required} is past {@link #MAX_EDGES}
     */
    static int grownCapacity(int current, int required) {
        if (required > MAX_EDGES || required < 0) {
            throw new IllegalStateException(fullMessage());
        }
        long grown = Math.max((long) required, (long) current + (current >> 1));
        return (int) Math.min(grown, MAX_EDGES);
    }

    private static String fullMessage() {
        return "in-memory edge buffer is full at " + MAX_EDGES + " edges; a larger store needs an "
            + "external merge sort feeding ColumnFileWriter";
    }

    private void ensureCapacity(int required) {
        if (required <= subjects.length) {
            return;
        }
        int grown = grownCapacity(subjects.length, required);
        subjects = Arrays.copyOf(subjects, grown);
        relations = Arrays.copyOf(relations, grown);
        objects = Arrays.copyOf(objects, grown);
        validFrom = Arrays.copyOf(validFrom, grown);
        validTo = Arrays.copyOf(validTo, grown);
        statedOn = Arrays.copyOf(statedOn, grown);
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
     * guarantee the format actually needs. Ordered only against the page cache, a power loss
     * could leave the descriptor durable over columns that were not, producing the one outcome
     * the ordering exists to prevent — a directory that opens as a store and reads truncated
     * columns.
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
        // Each column's digest is taken as it streams out, and recorded in the descriptor below:
        // the only moment every byte of the column passes through this process anyway.
        Map<EdgeColumn, ColumnDigest> digests = new EnumMap<>(EdgeColumn.class);
        BuildCancellation.checkpoint();
        int[] forwardOrder = sortForward();
        writeForward(directory, forwardOrder, entityCount, digests);

        BuildCancellation.checkpoint();
        int[] reverseOrder = sortReverse();
        writeReverse(directory, forwardOrder, reverseOrder, entityCount, digests);

        BuildCancellation.checkpoint();

        // Every column, on the device, before the file that says they are a store exists. The
        // enum is walked rather than a list kept alongside, so a column added later is covered
        // by this without anybody having to remember.
        for (EdgeColumn column : EdgeColumn.values()) {
            durability.fsyncFile(directory.resolve(column.fileName()));
        }

        // Last, and atomically. Before this line the directory is not a store.
        EdgeStoreDescriptor.of(entityCount, count, digests).write(directory, durability);
        LOGGER.info("built edge store at {}: {} entities, {} edges", directory, entityCount, count);
    }

    /**
     * Closes the interval of every statement that a later statement on the same
     * (subject, relation) supersedes.
     *
     * <p>Only for relations the policy calls single-valued. Each group is walked in the order its
     * values began - see {@link #orderingDay} - and then in the order the edges were added, so
     * a group's order is total and a rebuild from the same statements always closes the same
     * intervals.
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
            c = Integer.compare(orderingDay(a), orderingDay(b));
            if (c != 0) {
                return c;
            }
            return Integer.compare(a, b);
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
     * The day an edge's value is taken to have begun, for ordering it against the rest of its
     * group.
     *
     * <p>Its {@code validFrom} when the source said when the value began. Otherwise, for a
     * statement that is still open, the day it was stated: a document that says "X runs Y"
     * without saying since when is evidence that X ran Y on the day it was written, and that is
     * the latest the change to X can have happened. A statement with a stated end and no stated
     * start is history rather than a claim about the present, and orders as having begun
     * before anything dated.
     */
    private int orderingDay(int edge) {
        if (validFrom[edge] != TemporalFilter.UNDATED) {
            return validFrom[edge];
        }
        return validTo[edge] == TemporalFilter.OPEN ? statedOn[edge] : TemporalFilter.UNDATED;
    }

    /**
     * Closes the open intervals of one single-valued (subject, relation) group, and dates the
     * start of each value that took over from another.
     *
     * <p>The group arrives in order (see {@link #applySupersession}). Walking it, each statement
     * closes every still-open statement of a <em>different</em> value at the day its own value
     * began, with three exceptions, each of which keeps information rather than inventing it:</p>
     * <ul>
     *   <li><b>A restatement closes nothing.</b> A later document repeating the current value
     *       corroborates it; it is not a change. Both stay open, each with its own provenance,
     *       until a different value arrives and closes them together.</li>
     *   <li><b>One source's statements on one day do not order each other.</b> Two values the
     *       same document gives on the same day - "worked at Acme and at Beta" - carry no
     *       evidence of which came second, and the order a model listed them in is not any. Both
     *       remain, and both are closed by whatever begins later. Between different sources the
     *       later-staged statement is the later statement, which is what makes a batch of
     *       documents ingested on one day resolve to one current value rather than to all of
     *       them.</li>
     *   <li><b>A stated end is kept.</b> An interval the source ended already is left alone: the
     *       source said when the fact stopped holding, which beats an inference from the next
     *       arrival. And a statement with an end but no start does not close anything, because
     *       it never says when it began.</li>
     * </ul>
     *
     * <p>A value that closed another, or that began after the group's first, and whose own start
     * the source did not state, is given the day it is ordered by as its start. Without that the
     * new value would reach back over all of time and a question about the years before the
     * change would get both answers. The group's first value keeps an unknown start: nothing
     * earlier is known, and the fact is what the graph holds for those years.</p>
     *
     * <p>Superseding a statement on the day it began leaves an empty interval, which no snapshot
     * admits and the whole history still returns - a statement made and contradicted the same
     * day.</p>
     *
     * @return how many intervals were closed
     */
    private int closeGroup(int[] order, int from, int to) {
        int closed = 0;
        int earliest = orderingDay(order[from]);
        // The group's still-open statements, by the value each holds. A group is one subject's
        // one relation, so this holds a handful of values however many statements there are.
        Map<Integer, List<Integer>> openByValue = new LinkedHashMap<>();

        for (int k = from; k < to; k++) {
            int edge = order[k];
            int day = orderingDay(edge);
            boolean begins = validFrom[edge] != TemporalFilter.UNDATED
                || validTo[edge] == TemporalFilter.OPEN;

            boolean supersededAny = false;
            if (begins) {
                Iterator<Map.Entry<Integer, List<Integer>>> values =
                    openByValue.entrySet().iterator();
                while (values.hasNext()) {
                    Map.Entry<Integer, List<Integer>> value = values.next();
                    if (value.getKey() == objects[edge]) {
                        continue;
                    }
                    Iterator<Integer> held = value.getValue().iterator();
                    while (held.hasNext()) {
                        int previous = held.next();
                        if (orderingDay(previous) == day && sameSource(previous, edge)) {
                            continue;
                        }
                        validTo[previous] = day;
                        held.remove();
                        closed++;
                        supersededAny = true;
                    }
                    if (value.getValue().isEmpty()) {
                        values.remove();
                    }
                }
            }

            if (validFrom[edge] == TemporalFilter.UNDATED
                && validTo[edge] == TemporalFilter.OPEN
                && day != TemporalFilter.UNDATED
                && (supersededAny || day > earliest)) {
                validFrom[edge] = day;
            }
            if (validTo[edge] == TemporalFilter.OPEN) {
                openByValue.computeIfAbsent(objects[edge], value -> new ArrayList<>()).add(edge);
            }
        }
        return closed;
    }

    /** Whether two edges were extracted from the same source. */
    private boolean sameSource(int a, int b) {
        return sourceTypes[a] == sourceTypes[b] && sourceIds[a] == sourceIds[b];
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

    private void writeForward(Path directory, int[] order, long entityCount,
                              Map<EdgeColumn, ColumnDigest> digests) throws IOException {
        writeColumn(directory, EdgeColumn.EDGES_FWD, order, digests,
            (w, e) -> w.putInt(objects[e]));
        writeColumn(directory, EdgeColumn.REL_FWD, order, digests,
            (w, e) -> w.putInt(relations[e]));
        writeOffsets(directory, EdgeColumn.OFFSETS_FWD, order, entityCount, digests,
            i -> subjects[i]);

        // Every per-edge column is laid out in forward order, and this is the only place that is
        // decided. Reverse traversal reaches them through PERM_REV rather than through a copy.
        writeColumn(directory, EdgeColumn.VALID_FROM, order, digests,
            (w, e) -> w.putInt(validFrom[e]));
        writeColumn(directory, EdgeColumn.VALID_TO, order, digests,
            (w, e) -> w.putInt(validTo[e]));
        writeColumn(directory, EdgeColumn.SOURCE_TYPE, order, digests,
            (w, e) -> w.putByte(sourceTypes[e]));
        writeColumn(directory, EdgeColumn.SOURCE_ID, order, digests,
            (w, e) -> w.putLong(sourceIds[e]));
        writeColumn(directory, EdgeColumn.EXTRACTION_CONFIDENCE, order, digests,
            (w, e) -> w.putByte(confidences[e]));
        writeColumn(directory, EdgeColumn.EXTRACTOR_VERSION, order, digests,
            (w, e) -> w.putShort(extractorVersions[e]));
    }

    private void writeReverse(Path directory, int[] forwardOrder, int[] reverseOrder,
                              long entityCount, Map<EdgeColumn, ColumnDigest> digests)
            throws IOException {
        writeColumn(directory, EdgeColumn.EDGES_REV, reverseOrder, digests,
            (w, e) -> w.putInt(subjects[e]));
        writeColumn(directory, EdgeColumn.REL_REV, reverseOrder, digests,
            (w, e) -> w.putInt(relations[e]));
        writeOffsets(directory, EdgeColumn.OFFSETS_REV, reverseOrder, entityCount, digests,
            i -> objects[i]);

        // Position of each original edge in forward order, so a reverse position can name the
        // forward slot its columns live at.
        int[] forwardPosition = new int[count];
        for (int position = 0; position < count; position++) {
            forwardPosition[forwardOrder[position]] = position;
        }
        ColumnFileWriter writer = ColumnFileWriter.create(directory, EdgeColumn.PERM_REV);
        try (writer) {
            for (int i = 0; i < count; i++) {
                writer.putLong(forwardPosition[reverseOrder[i]]);
            }
        }
        digests.put(EdgeColumn.PERM_REV, writer.digest());
    }

    /** Writes one per-edge column by walking {@code order} and emitting one element per edge. */
    private void writeColumn(Path directory, EdgeColumn column, int[] order,
                             Map<EdgeColumn, ColumnDigest> digests, ElementWriter emitter)
            throws IOException {
        ColumnFileWriter writer = ColumnFileWriter.create(directory, column);
        try (writer) {
            for (int i = 0; i < count; i++) {
                emitter.write(writer, order[i]);
            }
        }
        digests.put(column, writer.digest());
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
                              Map<EdgeColumn, ColumnDigest> digests, KeyExtractor key)
            throws IOException {
        ColumnFileWriter writer = ColumnFileWriter.create(directory, column);
        try (writer) {
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
        digests.put(column, writer.digest());
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
