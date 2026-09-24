package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

/**
 * One fact a person states directly, before it is staged.
 *
 * <p>The labels are cleaned the way the extractor cleans the ones it reads, through
 * {@link FactLabels}, so an asserted relation and an extracted one with the same name are one
 * relation. What this does not carry is where the fact came from: the staging store gives every
 * assertion a fact id of its own and records it as the fact's source; see
 * {@link GraphStagingStore#assertFacts}.</p>
 *
 * @param subject    label of the entity the fact is about, cleaned
 * @param relation   label of the relation, cleaned
 * @param object     label of the entity the fact points at, cleaned
 * @param validFrom  first day, inclusive, on which the fact held (epoch days), or
 *                   {@link TemporalFilter#UNDATED} when the person did not say
 * @param validTo    first day it no longer held, or {@link TemporalFilter#OPEN} if it still does
 * @param confidence how sure the person is, {@code 0..}{@value Provenance#MAX_CONFIDENCE}
 */
public record AssertedFact(String subject, String relation, String object,
                           int validFrom, int validTo, int confidence) {

    /**
     * Cleans the labels and checks the rest.
     *
     * @throws IllegalArgumentException if a label is absent, blank, longer than
     *         {@value FactLabels#MAX_LABEL_CHARS} characters or an erasure's placeholder, the
     *         interval is empty or reversed, or the confidence is out of range
     */
    public AssertedFact {
        String cleanSubject = FactLabels.entity(subject);
        String cleanRelation = FactLabels.relation(relation);
        String cleanObject = FactLabels.entity(object);
        requireLabel(cleanSubject, "subject");
        requireLabel(cleanRelation, "relation");
        requireLabel(cleanObject, "object");
        subject = cleanSubject;
        relation = cleanRelation;
        object = cleanObject;
        if (validFrom == TemporalFilter.OPEN) {
            throw new IllegalArgumentException(
                "validFrom must be a day or UNDATED, not the open-interval sentinel");
        }
        if (validTo == TemporalFilter.UNDATED) {
            throw new IllegalArgumentException(
                "validTo must be a day or OPEN, not the undated sentinel");
        }
        TemporalFilter.checkInterval(validFrom, validTo);
        if (confidence < 0 || confidence > Provenance.MAX_CONFIDENCE) {
            throw new IllegalArgumentException("confidence must be 0.." + Provenance.MAX_CONFIDENCE
                + ", got " + confidence);
        }
    }

    /**
     * A fact held to be true with no recorded start and no end, at full confidence.
     *
     * @param subject  label of the subject entity
     * @param relation label of the relation
     * @param object   label of the object entity
     * @return The fact
     */
    public static AssertedFact always(String subject, String relation, String object) {
        return new AssertedFact(subject, relation, object,
            TemporalFilter.UNDATED, TemporalFilter.OPEN, Provenance.MAX_CONFIDENCE);
    }

    private static void requireLabel(String cleaned, String field) {
        if (cleaned == null) {
            throw new IllegalArgumentException(field + " must be 1 to "
                + FactLabels.MAX_LABEL_CHARS + " characters once surrounding white space is "
                + "removed, and not the placeholder an erasure leaves in place of a name");
        }
    }
}
