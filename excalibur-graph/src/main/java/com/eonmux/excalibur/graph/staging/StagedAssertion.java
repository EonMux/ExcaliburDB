package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.temporal.TemporalFilter;

/**
 * An asserted fact as the staging store holds it, ids resolved.
 *
 * @param factId     the id {@link GraphStagingStore#assertFacts} handed back, which is also the
 *                   fact's source id
 * @param subject    subject entity id
 * @param relation   relation id
 * @param object     object entity id
 * @param validFrom  first day the fact held, or {@link TemporalFilter#UNDATED}
 * @param validTo    first day it no longer held, or {@link TemporalFilter#OPEN}
 * @param confidence how sure the person asserting it was, 0-100
 */
public record StagedAssertion(long factId, int subject, int relation, int object,
                              int validFrom, int validTo, int confidence) {
}
