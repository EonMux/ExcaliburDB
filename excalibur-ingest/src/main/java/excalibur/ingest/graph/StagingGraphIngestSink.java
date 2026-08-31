package excalibur.ingest.graph;

import io.excalibur.graph.staging.ExtractedTriple;
import io.excalibur.graph.staging.GraphStagingStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
     * @param triples The facts extracted from this document
     * @param text    The document's text
     */
    private void recordEntityText(List<ExtractedTriple> triples, String text) {
        Set<String> mentioned = new LinkedHashSet<>();
        for (ExtractedTriple triple : triples) {
            mentioned.add(triple.subject());
            mentioned.add(triple.object());
        }
        for (String label : mentioned) {
            try {
                staging.appendEntityText(staging.entityIdOf(label), text);
            } catch (IOException | RuntimeException e) {
                indexFailures.incrementAndGet();
                LOGGER.warn("could not record text for entity {}: {}", label, e.toString());
            }
        }
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
            + indexFailures() + " index failures]";
    }
}
