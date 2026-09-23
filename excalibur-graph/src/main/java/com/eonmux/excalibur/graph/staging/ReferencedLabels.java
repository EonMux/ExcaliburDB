package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.dictionary.LabelDictionary;
import com.eonmux.excalibur.graph.erasure.ErasureLog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Where label ids are held outside the interners that assigned them, and the highest one each
 * holds.
 *
 * <h2>Why a label interner has to ask</h2>
 * <p>An interner's file may hold a record that fails its checksum. Usually that is a write a
 * crash cut short - the last record, or one of several a single unfinished force was covering,
 * whose pages reached the device out of order - and cutting it off, with everything after it, is
 * safe: {@link LabelInterner#intern} forces a record to the device before it returns the id, so a
 * record that never became durable held an id nobody was ever given, and so did every record
 * written after it. But a record that did become durable, and then rotted, looks exactly the
 * same, and its id, or the id of a record after it, may be in any of the artifacts this names.
 * Cutting it off would give that id to the next new label, and every one of those artifacts would
 * go on describing the new label as the old one - one entity served under another's name, or an
 * erasure applied to the wrong entity.</p>
 *
 * <p>So before an interner gives up an id, the staging store asks every holder it knows of for
 * the highest id it holds, and refuses the open when one of them holds an id the interner is
 * about to lose. The staging store's own logs - staged facts and entity text - it reads itself;
 * this is how a caller names the holders that live elsewhere. It is asked only when an interner
 * actually has damage to cut off, so a clean open reads none of them.</p>
 *
 * @author ExcaliburDB
 */
@FunctionalInterface
public interface ReferencedLabels {

    /** Names no holder: for a staging store nothing outside its own directory refers to. */
    ReferencedLabels NONE = () -> Reach.NONE;

    /**
     * Reads the highest ids this holder refers to.
     *
     * @return The highest entity and relation id held, with what holds each
     * @throws IOException if the holder cannot be read; a label interner is then not opened,
     *                     since whether it may give up an id cannot be decided
     */
    Reach reach() throws IOException;

    /**
     * The highest id of one kind something holds.
     *
     * @param highestId The id, or -1 when nothing is held
     * @param heldBy    What holds it, phrased for the operator reading a refusal
     */
    record Reference(long highestId, String heldBy) {

        /** Nothing held. */
        public static final Reference NONE = new Reference(-1, "nothing");

        /** @throws NullPointerException if {@code heldBy} is null */
        public Reference {
            Objects.requireNonNull(heldBy, "heldBy must not be null");
            if (highestId < -1) {
                throw new IllegalArgumentException("highestId must be -1 or an id, got "
                    + highestId);
            }
        }

        /**
         * The higher of two references; this one when they are equal.
         *
         * @param other The other reference
         * @return Whichever holds the higher id
         */
        public Reference higher(Reference other) {
            return other.highestId > highestId ? other : this;
        }
    }

    /**
     * The highest entity id and the highest relation id held.
     *
     * @param entities  The highest entity id, and what holds it
     * @param relations The highest relation id, and what holds it
     */
    record Reach(Reference entities, Reference relations) {

        /** Nothing held. */
        public static final Reach NONE = new Reach(Reference.NONE, Reference.NONE);

        /** @throws NullPointerException if either part is null */
        public Reach {
            Objects.requireNonNull(entities, "entities must not be null");
            Objects.requireNonNull(relations, "relations must not be null");
        }

        /**
         * The higher of two reaches, kind by kind.
         *
         * @param other The other reach
         * @return The higher entity reference and the higher relation reference
         */
        public Reach higher(Reach other) {
            return new Reach(entities.higher(other.entities), relations.higher(other.relations));
        }
    }

    /**
     * Every holder in {@code sources}, read in turn.
     *
     * @param sources The holders
     * @return A holder whose reach is the highest of theirs
     * @throws NullPointerException if any source is null
     */
    static ReferencedLabels all(ReferencedLabels... sources) {
        List<ReferencedLabels> held = List.of(sources);
        return () -> {
            Reach reach = Reach.NONE;
            for (ReferencedLabels source : held) {
                reach = reach.higher(source.reach());
            }
            return reach;
        };
    }

    /**
     * The dictionaries of a graph published at {@code graphDirectory}.
     *
     * <p>A build writes every label the interners hold into the dictionaries it publishes, and a
     * published graph is forced to the device; ids it names are the ones queries have answered
     * with and callers have kept. A graph a publish interrupted by a crash left set aside is read
     * too, since the node restores it when it opens the graph.</p>
     *
     * @param graphDirectory Where the graph is served from; need not exist
     * @return The holder
     * @throws NullPointerException if {@code graphDirectory} is null
     */
    static ReferencedLabels publishedIn(Path graphDirectory) {
        Objects.requireNonNull(graphDirectory, "graphDirectory must not be null");
        return () -> {
            Reach reach = Reach.NONE;
            for (Path graph : GraphBuilder.servedOrDisplaced(graphDirectory)) {
                String heldBy = "the published graph at " + graph;
                reach = reach.higher(new Reach(
                    new Reference(LabelDictionary.highestId(graph,
                        GraphBuilder.ENTITY_DICTIONARY), heldBy),
                    new Reference(LabelDictionary.highestId(graph,
                        GraphBuilder.RELATION_DICTIONARY), heldBy)));
            }
            return reach;
        };
    }

    /**
     * Whether a graph published at {@code graphDirectory} names an entity id.
     *
     * <p>Asked of the same graphs {@link #publishedIn} reads - the served one and any a publish or
     * a compaction set aside - because an erasure accepted for an entity only one of them names
     * is still owed by that graph, which recovery may be about to restore.</p>
     *
     * @param graphDirectory Where the graph is served from; need not exist
     * @param entityId       The id
     * @return true when one of those graphs' entity dictionaries has an entry for it
     * @throws IOException if a dictionary is present and cannot be read
     * @throws NullPointerException if {@code graphDirectory} is null
     */
    static boolean publishedEntity(Path graphDirectory, long entityId) throws IOException {
        Objects.requireNonNull(graphDirectory, "graphDirectory must not be null");
        for (Path graph : GraphBuilder.servedOrDisplaced(graphDirectory)) {
            try (LabelDictionary dictionary = LabelDictionary.openIfPresent(graph,
                    GraphBuilder.ENTITY_DICTIONARY, "E")) {
                if (dictionary != null && dictionary.contains(entityId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The outstanding entity-scoped requests of an erasure log, or of any log in its format.
     *
     * @param obligationLog The log; need not exist
     * @return The holder, which holds entity ids only
     * @throws NullPointerException if {@code obligationLog} is null
     * @see ErasureLog#highestEntityOwed
     */
    static ReferencedLabels owedIn(Path obligationLog) {
        Objects.requireNonNull(obligationLog, "obligationLog must not be null");
        return () -> new Reach(new Reference(ErasureLog.highestEntityOwed(obligationLog),
            "an outstanding erasure request in " + obligationLog), Reference.NONE);
    }
}
