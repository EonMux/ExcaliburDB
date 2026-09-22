package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

/**
 * One fact, as an extractor produces it: three labels, an interval and where it came from.
 *
 * <p>Labels rather than integer ids, because an extractor reads text and has no way to know which
 * integer an entity has been given - that mapping belongs to {@link GraphStagingStore}, which is
 * the only thing that may assign one. Keeping the extractor on the label side is also what lets two
 * extractors, or the same extractor run twice, agree about an entity without coordinating.</p>
 *
 * @param subject    label of the entity the fact is about
 * @param relation   label of the relation
 * @param object     label of the entity the fact points at
 * @param validFrom  first day, inclusive, on which the fact held in the world (epoch days)
 * @param validTo    first day it no longer held, or {@link TemporalFilter#OPEN} if it still does
 * @param provenance where the fact came from and how much to trust it
 */
public record ExtractedTriple(
    String subject,
    String relation,
    String object,
    int validFrom,
    int validTo,
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
        TemporalFilter.checkInterval(validFrom, validTo);
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
            Integer.MIN_VALUE, TemporalFilter.OPEN, provenance);
    }
}
