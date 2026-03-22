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
}
