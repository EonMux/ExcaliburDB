package excalibur.ingest.visibility;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Adapts DocumentVisibilityTracker to the DocumentVisibilityChecker interface.
 *
 * <p>This adapter allows the query pipeline to use a DocumentVisibilityTracker
 * instance through the DocumentVisibilityChecker interface, enabling document
 * atomicity filtering in the query pipeline.</p>
 *
 * <h2>Document Atomicity Contract</h2>
 * <p>Delegates all visibility checks to the underlying tracker, ensuring:</p>
 * <ul>
 *   <li>A document is only visible if ALL its chunks are registered</li>
 *   <li>Visibility is epoch-based: a document visible at epoch E is visible at all epochs >= E</li>
 *   <li>Queries never see partial documents (some chunks visible, others not), including
 *       after a restart that interrupted one - the tracker records its in-flight documents
 *       beside the document registry</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe, delegating all operations to the underlying
 * thread-safe DocumentVisibilityTracker.</p>
 *
 * @author ExcaliburDB
 * @since 8.0
 */
public final class DocumentVisibilityTrackerAdapter implements DocumentVisibilityChecker {

    private final DocumentVisibilityTracker tracker;

    /**
     * Creates a new adapter wrapping the specified tracker.
     *
     * @param tracker The visibility tracker to adapt (must not be null)
     * @throws NullPointerException if tracker is null
     */
    public DocumentVisibilityTrackerAdapter(DocumentVisibilityTracker tracker) {
        this.tracker = Objects.requireNonNull(tracker, "tracker must not be null");
    }

    @Override
    public boolean sharesNodeEpochClock() {
        return tracker.sharesNodeEpochClock();
    }

    @Override
    public boolean isDocumentTracked(String documentId) {
        return tracker.isDocumentTracked(documentId);
    }

    @Override
    public boolean isDocumentVisible(String documentId, long queryEpoch) {
        return tracker.isDocumentFullyVisible(documentId, queryEpoch);
    }

    @Override
    public boolean isChunkVisible(String chunkId, long queryEpoch) {
        return tracker.isChunkVisible(chunkId, queryEpoch);
    }

    @Override
    public List<String> filterPartialDocuments(List<String> chunkIds, long queryEpoch) {
        return tracker.filterPartialDocuments(chunkIds, queryEpoch);
    }

    @Override
    public Set<String> getVisibleDocuments(long queryEpoch) {
        return tracker.getVisibleDocuments(queryEpoch);
    }

    @Override
    public long currentEpoch() {
        return tracker.currentEpoch();
    }

    @Override
    public long getCommitEpoch(String documentId) {
        return tracker.getCommitEpoch(documentId);
    }

    @Override
    public int getChunkCount(String documentId) {
        return tracker.getChunkCount(documentId);
    }

    @Override
    public int getDocumentCount() {
        return tracker.getDocumentCount();
    }

    /**
     * Returns the underlying tracker.
     *
     * @return The wrapped DocumentVisibilityTracker
     */
    public DocumentVisibilityTracker getTracker() {
        return tracker;
    }
}
