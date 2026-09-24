package com.eonmux.excalibur.graph.browse;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

/**
 * One edge incident to an entity, as the catalog reports it.
 *
 * @param direction   {@link EdgeDirection#OUT} when the entity asked about is the subject,
 *                    {@link EdgeDirection#IN} when it is the object
 * @param relationId  The relation id
 * @param relation    Its label
 * @param subjectId   The subject entity id
 * @param subject     Its label
 * @param objectId    The object entity id
 * @param object      Its label
 * @param validFrom   First day the fact held, or {@link TemporalFilter#UNDATED}
 * @param validTo     First day it no longer held, or {@link TemporalFilter#OPEN}
 * @param provenance  Where it came from, as the store records it
 */
public record CatalogEdge(EdgeDirection direction, int relationId, String relation,
                          long subjectId, String subject, long objectId, String object,
                          int validFrom, int validTo, Provenance provenance) {
}
