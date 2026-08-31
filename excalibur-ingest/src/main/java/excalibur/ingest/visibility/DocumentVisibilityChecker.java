package excalibur.ingest.visibility;

import java.util.List;
import java.util.Set;

/**
 * Common interface for checking document visibility.
 *
 * <p>This interface abstracts the document visibility checking mechanism,
 * allowing the query pipeline to work with different visibility tracking
 * implementations (DocumentVisibilityRegistry, DocumentVisibilityTracker, etc.).</p>
 *
 * <h2>Document Atomicity Contract</h2>
 * <p>Implementations must ensure that:</p>
 * <ul>
 *   <li>A document is only visible if ALL its chunks are registered</li>
 *   <li>Visibility is epoch-based: a document visible at epoch E is visible at all epochs >= E</li>
 *   <li>Queries never see partial documents (some chunks visible, others not)</li>
 *   <li>{@link #sharesNodeEpochClock()} reports honestly which counter those epochs are on</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be thread-safe for read operations.</p>
 *
 * @author ExcaliburDB
 * @since 8.0
 */
public interface DocumentVisibilityChecker {

    /**
     * Whether this checker's epochs are the ones a query captures.
     *
     * <p>Every method here takes a {@code queryEpoch}, and the answer is only meaningful when
     * that epoch and the commit epochs held inside came from the same counter. An
     * implementation publishing on a counter of its own returns false, and a caller must then
     * treat the check as "is this document complete" rather than "was it committed by the time
     * my query started" - the second question its epochs cannot answer.
     *
     * <p>False by default, so an implementation gets the conservative reading unless it
     * deliberately says otherwise.
     *
     * @return true when commit epochs here are on the node's visibility clock
     */
    default boolean sharesNodeEpochClock() {
        return false;
    }

    /**
     * Whether this checker has an opinion about a document at all.
     *
     * <p>There are three states, and only two of them are a judgement:
     * <ul>
     *   <li><b>committed</b> - visible from its commit epoch onward;</li>
     *   <li><b>in flight</b> - not visible, because showing some of its chunks would be
     *       exactly the partial document this whole mechanism exists to prevent;</li>
     *   <li><b>unknown</b> - this checker never saw the document, which is not the same
     *       thing as knowing it to be incomplete.</li>
     * </ul>
     *
     * <p>The distinction is load-bearing. These checkers hold in-memory state built as
     * documents are ingested, so after a restart every document written by a previous process
     * is unknown. Treating unknown as invisible drops all of them from every result the moment
     * one new document is ingested - silently, and more of them the longer the node runs.
     *
     * <p>True by default, which preserves the two-state reading for an implementation that
     * genuinely knows about everything.
     *
     * @param documentId The document identifier
     * @return true when this checker is tracking the document, committed or in flight
     */
    default boolean isDocumentTracked(String documentId) {
        return true;
    }

    /**
     * Checks if a document is fully visible at the specified query epoch.
     *
     * <p>A document is fully visible if:</p>
     * <ul>
     *   <li>It is registered in the visibility system</li>
     *   <li>Its commit epoch is &lt;= the query epoch</li>
     *   <li>All its chunks are registered (not partial)</li>
     * </ul>
     *
     * @param documentId The document identifier (must not be null)
     * @param queryEpoch The epoch at which the query was started (must be &gt;= 0)
     * @return true if the document is fully visible at the query epoch
     */
    boolean isDocumentVisible(String documentId, long queryEpoch);

    /**
     * Checks if a specific chunk is visible at the query epoch.
     *
     * <p>A chunk is visible if its owning document is fully visible.</p>
     *
     * @param chunkId The chunk identifier (format: documentId:chunkIndex)
     * @param queryEpoch The epoch at which the query was started
     * @return true if the chunk is visible at the query epoch
     */
    boolean isChunkVisible(String chunkId, long queryEpoch);

    /**
     * Filters query results to exclude partial documents.
     *
     * <p>This method takes a list of chunk IDs from query results and
     * returns only those chunks whose documents are fully visible at
     * the query epoch.</p>
     *
     * @param chunkIds The chunk IDs from query results
     * @param queryEpoch The epoch at which the query was started
     * @return Filtered list containing only chunks from fully visible documents
     */
    List<String> filterPartialDocuments(List<String> chunkIds, long queryEpoch);

    /**
     * Gets the set of fully visible document IDs at the specified epoch.
     *
     * @param queryEpoch The epoch at which to check visibility
     * @return Set of document IDs that are fully visible
     */
    Set<String> getVisibleDocuments(long queryEpoch);

    /**
     * Gets the commit epoch for a document.
     *
     * @param documentId The document identifier
     * @return The commit epoch, or -1 if not found
     */
    long getCommitEpoch(String documentId);

    /**
     * Gets the chunk count for a registered document.
     *
     * @param documentId The document identifier
     * @return The number of chunks, or 0 if not found
     */
    int getChunkCount(String documentId);

    /**
     * Returns the number of registered documents.
     *
     * @return Document count
     */
    int getDocumentCount();

    /**
     * The highest commit epoch this checker has published.
     *
     * <p>Needed to bound the atomicity filter. A query carries an epoch from the shard's visibility
     * tracker, and that counter is unrelated to ingest commit epochs — comparing the two is
     * meaningless. Without this the filter has to run unbounded, which means it cannot exclude a
     * document committed after the query began.
     *
     * <p>Declared rather than defaulted deliberately. A default returning an unbounded value would
     * let an implementation silently disable the bound simply by not overriding it, and the
     * resulting behaviour is indistinguishable from a filter that is working.
     *
     * @return the current commit epoch; 0 when nothing has been committed
     */
    long currentEpoch();
}
