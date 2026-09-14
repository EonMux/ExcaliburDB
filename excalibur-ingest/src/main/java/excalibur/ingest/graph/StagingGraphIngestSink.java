package excalibur.ingest.graph;

import io.excalibur.graph.provenance.Provenance;
import io.excalibur.graph.staging.ExtractedTriple;
import io.excalibur.graph.staging.GraphStagingStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The sink that actually stages facts and records document text.
 *
 * <h2>Two independent halves</h2>
 * <p>Extraction and lexical indexing are attempted separately and neither is allowed to cost the
 * other. A deployment may run with extraction off and still want BM25 over its documents; and when
 * extraction is on, a model that is refusing calls must not also stop the text from being
 * searchable. Both failures are logged and counted rather than raised: the document they describe
 * is already published and searchable by vector, and failing it here would report a loss that did
 * not happen.</p>
 *
 * <h2>What is counted</h2>
 * <p>Failures are counted rather than only logged, because the interesting failure is the steady
 * one - a model that has been refusing every call for an hour leaves a graph that quietly stops
 * growing, and a count is what makes that visible.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Safe for concurrent use. The staging store serialises its own appends and the lexical index
 * is a Lucene {@code IndexWriter}, which is thread-safe.</p>
 *
 * @author ExcaliburDB
 */
public final class StagingGraphIngestSink implements GraphIngestSink.Staging {

    private static final Logger LOGGER = LoggerFactory.getLogger(StagingGraphIngestSink.class);

    private final GraphStagingStore staging;
    private final RelationExtractor extractor;

    private final AtomicLong documentsSeen = new AtomicLong();
    private final AtomicLong factsStaged = new AtomicLong();
    private final AtomicLong extractionFailures = new AtomicLong();
    private final AtomicLong stagingFailures = new AtomicLong();
    private final AtomicLong indexFailures = new AtomicLong();

    /**
     * Creates a sink.
     *
     * @param staging   Where facts accumulate between graph builds
     * @param extractor Finds the facts; {@link RelationExtractor#none()} disables extraction
     */
    public StagingGraphIngestSink(GraphStagingStore staging, RelationExtractor extractor) {
        this.staging = Objects.requireNonNull(staging, "staging must not be null");
        this.extractor = Objects.requireNonNull(extractor, "extractor must not be null");
    }

    @Override
    public void onDocumentPublished(String documentKey, String text) {
        if (documentKey == null || documentKey.isBlank() || text == null || text.isBlank()) {
            return;
        }
        documentsSeen.incrementAndGet();
        stageFacts(documentKey, text);
    }

    @Override
    public GraphStagingStore staging() {
        return staging;
    }

    @Override
    public int stage(List<ExtractedTriple> triples) {
        if (triples == null || triples.isEmpty()) {
            return 0;
        }
        int staged = 0;
        for (ExtractedTriple triple : triples) {
            try {
                staging.append(triple);
                staged++;
            } catch (IOException | RuntimeException e) {
                stagingFailures.incrementAndGet();
                LOGGER.warn("could not stage {} {} {}: {}",
                    triple.subject(), triple.relation(), triple.object(), e.toString());
            }
        }
        factsStaged.addAndGet(staged);
        return staged;
    }

    /**
     * Extracts and stages the document's facts.
     *
     * @param documentKey The document
     * @param text        Its text
     */
    private void stageFacts(String documentKey, String text) {
        List<ExtractedTriple> triples;
        try {
            triples = extractor.extract(documentKey, text);
        } catch (RuntimeException e) {
            extractionFailures.incrementAndGet();
            LOGGER.warn("could not extract facts from {}: {}", documentKey, e.toString());
            return;
        }
        if (triples == null || triples.isEmpty()) {
            return;
        }
        stage(triples);
        recordEntityText(triples, text);
    }

    /**
     * Records the document's text against each entity it mentioned.
     *
     * <p>This is what the lexical half of hybrid retrieval ranks: an entity's document is the text
     * it was found in. Without it the BM25 signal scores every entity identically, and the budget
     * that decides which facts a model is shown is spent in arbitrary order.
     *
     * <p>Each snippet is recorded with the origin of the fact that mentioned the entity. Every
     * triple here came out of one call over one document, so they agree on the three fields
     * erasure matches against - source type, source id and extractor version - and what
     * recording them buys is that a later source- or extractor-scoped erasure can reach this
     * text. A snippet recorded without an origin can only be erased by naming its entity, and
     * the coordinator has to withhold the request rather than report it satisfied.
     *
     * <p>The confidence is deliberately dropped. It is an extractor's confidence in one fact,
     * and this is the whole document's text; carrying one triple's number on it would be a
     * measurement of nothing that the next reader could reasonably try to filter on.
     *
     * @param triples The facts extracted from this document
     * @param text    The document's text
     */
    private void recordEntityText(List<ExtractedTriple> triples, String text) {
        Map<String, Provenance> mentioned = new LinkedHashMap<>();
        for (ExtractedTriple triple : triples) {
            Provenance origin = originOf(triple.provenance());
            mentioned.putIfAbsent(triple.subject(), origin);
            mentioned.putIfAbsent(triple.object(), origin);
        }
        for (Map.Entry<String, Provenance> entity : mentioned.entrySet()) {
            try {
                staging.appendEntityText(
                    staging.entityIdOf(entity.getKey()), text, entity.getValue());
            } catch (IOException | RuntimeException e) {
                indexFailures.incrementAndGet();
                LOGGER.warn("could not record text for entity {}: {}",
                    entity.getKey(), e.toString());
            }
        }
    }

    /**
     * Where a fact came from, without the extractor's confidence in the fact itself.
     *
     * @param provenance The fact's provenance
     * @return The same origin with no confidence attached
     */
    private static Provenance originOf(Provenance provenance) {
        return new Provenance(provenance.sourceType(), provenance.sourceId(), 0,
            provenance.extractorVersion());
    }

    /**
     * How many published documents this sink has seen.
     *
     * @return The count
     */
    public long documentsSeen() {
        return documentsSeen.get();
    }

    /**
     * How many facts it has staged.
     *
     * @return The count
     */
    public long factsStaged() {
        return factsStaged.get();
    }

    /**
     * How many documents it could not extract facts from.
     *
     * @return The count
     */
    public long extractionFailures() {
        return extractionFailures.get();
    }

    /**
     * How many facts it extracted and could not write to the staging store.
     *
     * <p>The extraction succeeded and the fact was lost anyway, which is the failure a count of
     * extraction failures cannot show. A store refusing every append leaves a graph that stops
     * growing while everything upstream of it reports success.</p>
     *
     * @return The count
     */
    public long stagingFailures() {
        return stagingFailures.get();
    }

    /**
     * How many entity mentions it could not record text for.
     *
     * @return The count
     */
    public long indexFailures() {
        return indexFailures.get();
    }

    @Override
    public String toString() {
        return "StagingGraphIngestSink[" + documentsSeen() + " documents, " + factsStaged()
            + " facts, " + extractionFailures() + " extraction failures, "
            + stagingFailures() + " staging failures, "
            + indexFailures() + " index failures]";
    }
}
