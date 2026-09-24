package com.eonmux.excalibur.graph.browse;

import java.util.List;

/**
 * An entity as the catalog describes it.
 *
 * <p>The graph keeps one label per entity: two spellings of a name are two entities, so there
 * are no aliases to report.</p>
 *
 * @param id        The entity id, stable across builds, and what an entity-scoped erasure names
 * @param name      Its label
 * @param outDegree How many edges it is the subject of, over all of valid time
 * @param inDegree  How many edges it is the object of, over all of valid time
 * @param relations Per relation, how many of those edges use it; empty in a listing, where only
 *                  the totals are read
 */
public record CatalogEntity(long id, String name, long outDegree, long inDegree,
                            List<RelationDegree> relations) {

    public CatalogEntity {
        relations = List.copyOf(relations);
    }

    /**
     * How many of an entity's edges use one relation, in each direction.
     *
     * @param relationId The relation id
     * @param relation   Its label
     * @param out        Edges using it that the entity is the subject of
     * @param in         Edges using it that the entity is the object of
     */
    public record RelationDegree(int relationId, String relation, long out, long in) {
    }
}
