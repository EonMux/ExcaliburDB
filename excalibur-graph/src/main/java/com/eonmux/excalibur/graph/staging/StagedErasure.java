package com.eonmux.excalibur.graph.staging;

import com.eonmux.excalibur.graph.provenance.Provenance;

/**
 * What one batch of erasure requests covers, as the staging store needs to ask it.
 *
 * <h2>Why the three questions are separate</h2>
 * <p>Staging holds three different things, and a scope reaches them differently. A fact carries
 * full provenance, so every scope can be answered against it. A snippet carries its origin but
 * belongs to an entity, so both kinds of scope apply. A label is a name and nothing else: only an
 * entity-scoped request — the one a person's right to erasure produces — has any business
 * replacing it, and a withdrawn document must not take the names of everyone it mentioned with
 * it.
 *
 * <p>Declared here rather than in the erasure package so that the staging store does not have to
 * depend on the coordinator that drives it.
 *
 * @author ExcaliburDB
 */
public interface StagedErasure {

    /**
     * Whether one staged fact must be removed.
     *
     * @param subject    Subject entity id
     * @param relation   Relation id
     * @param object     Object entity id
     * @param validFrom  When the fact began to hold
     * @param validTo    When it stopped, or open
     * @param provenance Where the fact came from
     * @return true to remove it
     */
    boolean erasesFact(int subject, int relation, int object, int validFrom, int validTo,
                       Provenance provenance);

    /**
     * Whether one recorded snippet must be removed.
     *
     * @param entityId   The entity the snippet belongs to
     * @param provenance Where the snippet came from, {@link Provenance#UNKNOWN} when it was
     *                   recorded before origins were kept
     * @return true to remove it
     */
    boolean erasesSnippet(int entityId, Provenance provenance);

    /**
     * Whether one entity's label must be replaced by a tombstone.
     *
     * @param entityId The entity
     * @return true to replace its label
     */
    boolean erasesLabel(int entityId);
}
