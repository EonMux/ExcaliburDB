package com.eonmux.excalibur.ingest.graph;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.staging.ExtractedTriple;
import com.eonmux.excalibur.graph.staging.GraphStagingStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
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
 * <h2>Replacing and removing a version</h2>
 * <p>A replaced document's earlier facts and snippets are withdrawn from staging exactly as an
 * erasure of the document withdraws them: by the source-scoped match on
 * {@link LlmRelationExtractor#sourceIdOf} of its key, which is the id every fact and snippet
 * extracted from it carries. They are withdrawn before the new version's facts are staged,
 * because those carry the same id and a withdrawal after them would take them too. Entity labels
 * stay: a label is a name other documents' facts may share. The served graph keeps what it was
 * built with until the next build, which replays staging and so no longer holds the earlier
 * version.</p>
 *
 * <p>A document removed outright is withdrawn the same way, with nothing staged after it.</p>
 *
 * <p>A withdrawal that fails is owed rather than dropped: {@link StagedWithdrawals} records it and
 * retries it until it is made, and nothing newer is staged for the document in the meantime,
 * since the retry withdraws by document and would take a newer version's facts with the old
 * ones.</p>
 *
 * <p>The withdrawal rewrites the staging logs, reading them whole, and holds the staging store's
 * append lock while it does. That is paid once per replaced or removed document -
 * a log with nothing to remove is scanned and left alone - and never for a document seen for the
 * first time.</p>
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

    /** Milliseconds in a UTC day, which is the resolution a fact's dates are kept at. */
    private static final long MILLIS_PER_DAY = 86_400_000L;

    private final GraphStagingStore staging;
    private final StagedWithdrawals withdrawals;
    private final RelationExtractor extractor;

    private final AtomicLong documentsSeen = new AtomicLong();
    private final AtomicLong factsStaged = new AtomicLong();
    private final AtomicLong extractionFailures = new AtomicLong();
    private final AtomicLong stagingFailures = new AtomicLong();
    private final AtomicLong indexFailures = new AtomicLong();
    private final AtomicLong recordsWithdrawn = new AtomicLong();
    private final AtomicLong withdrawalFailures = new AtomicLong();

    /**
     * Creates a sink over a store whose owed withdrawals nothing records beyond this sink's life.
     *
     * @param staging   Where facts accumulate between graph builds
     * @param extractor Finds the facts; {@link RelationExtractor#none()} disables extraction
     */
    public StagingGraphIngestSink(GraphStagingStore staging, RelationExtractor extractor) {
        this(StagedWithdrawals.inMemory(staging), extractor);
    }

    /**
     * Creates a sink that stages into the store its withdrawals are made from.
     *
     * @param withdrawals The withdrawals owed against the staging store, which is where facts
     *                    accumulate between graph builds
     * @param extractor   Finds the facts; {@link RelationExtractor#none()} disables extraction
     */
    public StagingGraphIngestSink(StagedWithdrawals withdrawals, RelationExtractor extractor) {
        this.withdrawals = Objects.requireNonNull(withdrawals, "withdrawals must not be null");
        this.staging = withdrawals.staging();
        this.extractor = Objects.requireNonNull(extractor, "extractor must not be null");
    }

    /**
     * Stages a published document's facts, first making any withdrawal still owed for it.
     *
     * <p>A document this node did not hold can still owe one: a removal whose withdrawal failed,
     * published again once its source listed it again. When that withdrawal fails once more,
     * nothing is staged, for the reason {@link #onDocumentReplaced} gives.</p>
     *
     * @param documentKey        The source-qualified document key
     * @param text               The document's text
     * @param lastModifiedMillis The document's own date, or 0 when unknown
     */
    @Override
    public void onDocumentPublished(String documentKey, String text, long lastModifiedMillis) {
        if (documentKey == null || documentKey.isBlank() || text == null || text.isBlank()) {
            return;
        }
        documentsSeen.incrementAndGet();
        // Outside the document's lock: extraction may be a model call, and that lock is what a
        // retry of an owed withdrawal waits on.
        List<ExtractedTriple> triples = extract(documentKey, text);
        counted(documentKey, withdrawals.stage(documentKey,
            () -> stageFacts(triples, text, lastModifiedMillis)));
    }

    /**
     * Withdraws what was staged from the document's earlier version, then stages this one.
     *
     * <p>A withdrawal that fails is counted, logged and owed, and the new version is not staged:
     * the owed withdrawal is retried by document rather than by version, so it would take the
     * new facts with the old ones - and until it did, the next build would publish both.</p>
     *
     * @param documentKey        The source-qualified document key
     * @param text               The new version's text
     * @param lastModifiedMillis The new version's date, or 0 when unknown
     */
    @Override
    public void onDocumentReplaced(String documentKey, String text, long lastModifiedMillis) {
        if (documentKey == null || documentKey.isBlank()) {
            return;
        }
        boolean hasText = text != null && !text.isBlank();
        if (hasText) {
            documentsSeen.incrementAndGet();
        }
        List<ExtractedTriple> triples = hasText ? extract(documentKey, text) : List.of();
        counted(documentKey, withdrawals.withdraw(documentKey,
            () -> stageFacts(triples, text, lastModifiedMillis)));
    }

    /**
     * Withdraws what was staged from a document this node no longer holds.
     *
     * <p>A failed withdrawal is counted, logged and owed, as a replacement's is.</p>
     *
     * @param documentKey The source-qualified document key
     */
    @Override
    public void onDocumentRemoved(String documentKey) {
        if (documentKey == null || documentKey.isBlank()) {
            return;
        }
        withdraw(documentKey);
    }

    /**
     * Removes every staged fact and snippet extracted from a document, whatever its version.
     *
     * @param documentKey The document
     * @return How many records were removed, or -1 when the withdrawal failed and is owed
     */
    public long withdraw(String documentKey) {
        StagedWithdrawals.Result result = withdrawals.withdraw(documentKey, null);
        counted(documentKey, result);
        return result.owed() ? -1 : result.removed();
    }

    /**
     * Counts what a withdrawal did.
     *
     * @param documentKey The document, for the log line
     * @param result      What the withdrawal did
     */
    private void counted(String documentKey, StagedWithdrawals.Result result) {
        if (result.owed()) {
            withdrawalFailures.incrementAndGet();
            LOGGER.warn("the facts staged from earlier versions of {} are still to be withdrawn; "
                + "nothing newer is staged for it until they are", documentKey);
            return;
        }
        recordsWithdrawn.addAndGet(result.removed());
    }

    @Override
    public GraphStagingStore staging() {
        return staging;
    }

    /**
     * Forces the staging store this sink writes into.
     *
     * @throws IOException if it cannot be forced
     */
    @Override
    public void sync() throws IOException {
        staging.sync();
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
     * Extracts a document's facts.
     *
     * @param documentKey The document
     * @param text        Its text
     * @return The facts, empty when there are none or the extractor failed
     */
    private List<ExtractedTriple> extract(String documentKey, String text) {
        try {
            List<ExtractedTriple> triples = extractor.extract(documentKey, text);
            return triples == null ? List.of() : triples;
        } catch (RuntimeException e) {
            extractionFailures.incrementAndGet();
            LOGGER.warn("could not extract facts from {}: {}", documentKey, e.toString());
            return List.of();
        }
    }

    /**
     * Stages a document's extracted facts and the text they were found in.
     *
     * @param triples            The facts
     * @param text               The document's text
     * @param lastModifiedMillis The document's own date, or 0 when unknown
     */
    private void stageFacts(List<ExtractedTriple> triples, String text,
                            long lastModifiedMillis) {
        if (triples.isEmpty()) {
            return;
        }
        stage(statedOn(triples, lastModifiedMillis));
        recordEntityText(triples, text);
    }


    /**
     * Dates every fact the extractor left undated with the day its document was written.
     *
     * <p>An extractor reads text and cannot know the document's date; the source that fetched
     * it can. A fact the extractor did date keeps its own. With no document date the facts stay
     * undated, and the staging store records the day they were staged.
     *
     * @param triples            The extracted facts
     * @param lastModifiedMillis The document's own date, or 0 when unknown
     * @return The facts to stage
     */
    static List<ExtractedTriple> statedOn(List<ExtractedTriple> triples, long lastModifiedMillis) {
        if (lastModifiedMillis <= 0) {
            return triples;
        }
        int day = Math.toIntExact(Math.floorDiv(lastModifiedMillis, MILLIS_PER_DAY));
        List<ExtractedTriple> dated = new ArrayList<>(triples.size());
        for (ExtractedTriple triple : triples) {
            dated.add(triple.isDated() ? triple : triple.withStatedOn(day));
        }
        return dated;
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

    /**
     * How many staged facts and snippets were withdrawn because their document was replaced or
     * removed.
     *
     * @return The count
     */
    public long recordsWithdrawn() {
        return recordsWithdrawn.get();
    }

    /**
     * How many times a replaced or removed document's earlier facts could not be withdrawn.
     *
     * <p>Each such withdrawal is owed from then on, retried until it is made, and nothing newer
     * is staged for the document meanwhile; a build before it is made publishes what an earlier
     * version said.</p>
     *
     * @return The count
     */
    public long withdrawalFailures() {
        return withdrawalFailures.get();
    }

    @Override
    public String toString() {
        return "StagingGraphIngestSink[" + documentsSeen() + " documents, " + factsStaged()
            + " facts, " + recordsWithdrawn() + " withdrawn, "
            + extractionFailures() + " extraction failures, "
            + stagingFailures() + " staging failures, "
            + indexFailures() + " index failures, "
            + withdrawalFailures() + " withdrawal failures]";
    }
}
