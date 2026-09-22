package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

/**
 * One fact, as an extractor produces it: three labels, an interval, the day it was stated and
 * where it came from.
 *
 * <p>Labels rather than integer ids, because an extractor reads text and has no way to know which
 * integer an entity has been given - that mapping belongs to {@link GraphStagingStore}, which is
 * the only thing that may assign one. Keeping the extractor on the label side is also what lets two
 * extractors, or the same extractor run twice, agree about an entity without coordinating.</p>
 *
 * <h2>Two different dates</h2>
 * <p>{@code validFrom} and {@code validTo} are valid time: when the fact held in the world, and
 * only what the source actually said about that. {@code statedOn} is when the source said it - a
 * document's own date. It is not a claim about the world and never becomes one directly: a fact
 * stated in a 2024 document may have held since 1990, and giving it a 2024 start would hide it
 * from every question about the years it did hold. What it is for is ordering. When two
 * statements give a single-valued relation different values and neither says when its value
 * began, the one stated later is the one that superseded the other, and the graph builder places
 * the change on the day it was stated; see
 * {@link com.eonmux.excalibur.graph.temporal.SupersessionPolicy}.</p>
 *
 * @param subject    label of the entity the fact is about
 * @param relation   label of the relation
 * @param object     label of the entity the fact points at
 * @param validFrom  first day, inclusive, on which the fact held in the world (epoch days), or
 *                   {@link TemporalFilter#UNDATED} if the source did not say
 * @param validTo    first day it no longer held, or {@link TemporalFilter#OPEN} if it still does
 * @param statedOn   the day the source made the statement (epoch days), or
 *                   {@link TemporalFilter#UNDATED} for the staging store to record the day it was
 *                   staged instead
 * @param provenance where the fact came from and how much to trust it
 */
public record ExtractedTriple(
    String subject,
    String relation,
    String object,
    int validFrom,
    int validTo,
    int statedOn,
    Provenance provenance
) {

    public ExtractedTriple {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be null or blank");
        }
        if (relation == null || relation.isBlank()) {
            throw new IllegalArgumentException("relation must not be null or blank");
        }
        if (object == null || object.isBlank()) {
            throw new IllegalArgumentException("object must not be null or blank");
        }
        if (provenance == null) {
            throw new IllegalArgumentException(
                "provenance must not be null; use Provenance.UNKNOWN to record that it is unknown");
        }
        if (statedOn == TemporalFilter.OPEN) {
            throw new IllegalArgumentException(
                "statedOn must be a day or UNDATED, not the open-interval sentinel");
        }
        TemporalFilter.checkInterval(validFrom, validTo);
    }

    /**
     * A fact over an interval, with the day it was stated left for the staging store to record.
     *
     * @param subject    label of the subject entity
     * @param relation   label of the relation
     * @param object     label of the object entity
     * @param validFrom  first day the fact held, or {@link TemporalFilter#UNDATED}
     * @param validTo    first day it no longer held, or {@link TemporalFilter#OPEN}
     * @param provenance where it came from
     */
    public ExtractedTriple(String subject, String relation, String object,
                           int validFrom, int validTo, Provenance provenance) {
        this(subject, relation, object, validFrom, validTo, TemporalFilter.UNDATED, provenance);
    }

    /**
     * A fact with no recorded start and no end - one that is simply held to be true.
     *
     * @param subject    label of the subject entity
     * @param relation   label of the relation
     * @param object     label of the object entity
     * @param provenance where it came from
     * @return The triple, valid over all time
     */
    public static ExtractedTriple alwaysValid(String subject, String relation, String object,
                                              Provenance provenance) {
        return new ExtractedTriple(subject, relation, object,
            TemporalFilter.UNDATED, TemporalFilter.OPEN, provenance);
    }

    /**
     * Whether the source's date for this statement is known.
     *
     * @return false when the staging store will record the day it was staged instead
     */
    public boolean isDated() {
        return statedOn != TemporalFilter.UNDATED;
    }

    /**
     * The same fact, stated on {@code day}.
     *
     * @param day the day the source made the statement, in epoch days, or
     *            {@link TemporalFilter#UNDATED}
     * @return A new triple; this one is unchanged
     */
    public ExtractedTriple withStatedOn(int day) {
        return new ExtractedTriple(subject, relation, object, validFrom, validTo, day, provenance);
    }
}
