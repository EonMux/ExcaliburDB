package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.graph.browse.CatalogEdge;
import com.eonmux.excalibur.graph.browse.CatalogEntity;
import com.eonmux.excalibur.graph.browse.CatalogRelation;
import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;
import com.eonmux.excalibur.graph.temporal.TemporalFilter;

import java.time.LocalDate;

/**
 * How the structured graph routes write entities, edges and their dates.
 *
 * <p>One place, because {@code /v1/graph/entities}, {@code /v1/graph/relations} and
 * {@code /v1/graph/facts} describe the same things - a label, an interval, a provenance - and a
 * caller reading one and writing the other should meet a single spelling of each.</p>
 *
 * <h2>Spellings</h2>
 * <ul>
 *   <li>A day is an ISO-8601 date. An interval with no known start has a null
 *       {@code valid_from}; one still holding has a null {@code valid_to}.</li>
 *   <li>Confidence is a fraction in {@code [0, 1]}, as {@code /v1/graph/query} reports it.</li>
 *   <li>A source type is spelled as {@code POST /v1/graph/erasure} reads it, so the
 *       {@code source_type} and {@code source_id} of an edge are a source-scoped erasure request
 *       as they stand.</li>
 * </ul>
 */
final class GraphCatalogJson {

    private GraphCatalogJson() {
    }

    /**
     * An entity, with its per-relation counts when it carries them.
     *
     * @param entity The entity
     * @return A JSON object
     */
    static String entity(CatalogEntity entity) {
        StringBuilder json = new StringBuilder(128)
            .append("{\"id\":").append(entity.id())
            .append(",\"name\":").append(quoted(entity.name()))
            .append(",\"out_degree\":").append(entity.outDegree())
            .append(",\"in_degree\":").append(entity.inDegree());
        if (!entity.relations().isEmpty()) {
            json.append(",\"relations\":[");
            boolean first = true;
            for (CatalogEntity.RelationDegree relation : entity.relations()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append("{\"relation\":").append(quoted(relation.relation()))
                    .append(",\"relation_id\":").append(relation.relationId())
                    .append(",\"out\":").append(relation.out())
                    .append(",\"in\":").append(relation.in())
                    .append('}');
            }
            json.append(']');
        }
        return json.append('}').toString();
    }

    /**
     * One relation of the graph's vocabulary, spelled as an entity's per-relation counts spell
     * it.
     *
     * @param relation The relation
     * @return A JSON object
     */
    static String relation(CatalogRelation relation) {
        return "{\"relation\":" + quoted(relation.name())
            + ",\"relation_id\":" + relation.id()
            + ",\"edges\":" + relation.edges() + "}";
    }

    /**
     * One edge incident to an entity.
     *
     * @param edge The edge
     * @return A JSON object
     */
    static String edge(CatalogEdge edge) {
        Provenance provenance = edge.provenance();
        StringBuilder json = new StringBuilder(256)
            .append("{\"direction\":").append(quoted(edge.direction().wireName()))
            .append(",\"subject\":{\"id\":").append(edge.subjectId())
            .append(",\"name\":").append(quoted(edge.subject())).append('}')
            .append(",\"relation\":").append(quoted(edge.relation()))
            .append(",\"relation_id\":").append(edge.relationId())
            .append(",\"object\":{\"id\":").append(edge.objectId())
            .append(",\"name\":").append(quoted(edge.object())).append('}')
            .append(",\"valid_from\":").append(validFrom(edge.validFrom()))
            .append(",\"valid_to\":").append(validTo(edge.validTo()))
            .append(",\"confidence\":")
            .append(JsonText.number(provenance.confidenceFraction(), 2))
            .append(",\"provenance\":{\"source_type\":")
            .append(quoted(provenance.sourceType().name()))
            .append(",\"source_id\":").append(provenance.sourceId())
            .append(",\"extractor_version\":").append(provenance.extractorVersion())
            .append('}');
        if (provenance.sourceType() == SourceType.USER_ASSERTED) {
            // The id DELETE /v1/graph/facts/{fact_id} takes, named as that route names it.
            json.append(",\"fact_id\":").append(provenance.sourceId());
        }
        return json.append('}').toString();
    }

    /**
     * The first day of an interval.
     *
     * @param day An epoch day, or {@link TemporalFilter#UNDATED}
     * @return A quoted ISO date, or {@code null} when the start is not known
     */
    static String validFrom(int day) {
        return day == TemporalFilter.UNDATED ? "null" : quoted(LocalDate.ofEpochDay(day).toString());
    }

    /**
     * The first day an interval no longer holds.
     *
     * @param day An epoch day, or {@link TemporalFilter#OPEN}
     * @return A quoted ISO date, or {@code null} while it still holds
     */
    static String validTo(int day) {
        return day == TemporalFilter.OPEN ? "null" : quoted(LocalDate.ofEpochDay(day).toString());
    }

    /**
     * A JSON string literal, quotes included.
     *
     * @param value The text
     * @return The literal
     */
    static String quoted(String value) {
        return "\"" + JsonText.escape(value) + "\"";
    }
}
