package com.eonmux.excalibur.graph.browse;

/**
 * A relation as the catalog describes it: one entry of the served graph's vocabulary.
 *
 * <p>The label is the one every relation-keyed setting and filter is matched against - a
 * single-valued relation, an edge listing narrowed by relation, a relation a question names -
 * so it is spelled exactly as the graph stores it.</p>
 *
 * @param id    The relation id, stable across builds
 * @param name  Its label as stored - lower-cased with words joined by underscores wherever it
 *              came from extraction or an asserted fact, which both clean it so
 * @param edges How many edges of the served graph use it, over all of valid time
 */
public record CatalogRelation(int id, String name, long edges) {
}
