package com.eonmux.excalibur.ingest.registry;

import com.eonmux.excalibur.ingest.source.DocumentNotFoundException;
import com.eonmux.excalibur.ingest.source.DocumentValidators;
import com.eonmux.excalibur.ingest.source.FetchDisallowedException;
import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.IncompleteCrawlException;
import com.eonmux.excalibur.ingest.source.Source;
import com.eonmux.excalibur.ingest.source.SourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Detects changed and deleted documents during re-crawl operations.
 *
 * <h2>Change Detection Strategy</h2>
 * <p>A registered document is first put to its source conditionally, with the validators the
 * registry recorded for the indexed version (see {@link Source#fetchIfChanged}). A source that
 * confirms the version is current - an HTTP origin answering {@code 304 Not Modified} - settles
 * it without transferring the body, and the document is unchanged. Every other answer carries
 * the document, and the detector compares what came back by content (see
 * {@link DocumentMetadata#isChanged}):
 * <ol>
 *   <li><strong>Content Length:</strong> If lengths differ, document is changed.</li>
 *   <li><strong>Content Hash:</strong> If both old and new hashes are available, they
 *       decide.</li>
 *   <li><strong>Last Modified Timestamp:</strong> Only for an entry that recorded no hash: a
 *       newer timestamp is then taken as a change.</li>
 * </ol>
 *
 * <p>A document that came back with its bytes intact is unchanged even when its timestamp or its
 * entity tag moved - a touched file, a redeployed page - and nothing of it is re-chunked or
 * re-embedded. What it came back with is recorded instead; see {@link #refreshRecord}.</p>
 *
 * <p>Bytes intact are not the whole answer, because the text is what was indexed. A document
 * whose bytes came back unchanged and would now be read into other text - its source's
 * {@code charset} was replaced, a server labels the page with another encoding - is changed, and
 * is read again with the decoding its source now declares.</p>
 *
 * <h2>Documents that could not be checked</h2>
 * <p>A source that cannot answer for a document - it timed out, a server failed, a server
 * answered {@code 304 Not Modified} to a request that asked nothing - says nothing about whether
 * it changed. Such a document is kept as it is indexed and reported unchecked in the
 * {@link ChangeSet}, so the run counts it as a fetch that failed rather than passing it as
 * current.</p>
 *
 * <h2>Deleted Document Detection</h2>
 * <p>After processing all crawled documents, any document in the registry that was not
 * seen during the crawl is considered deleted. That inference is only sound over a complete
 * listing, so a crawl that reports itself truncated - an
 * {@link IncompleteCrawlException} - abandons the detection rather than deleting everything the
 * crawl had not reached.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is NOT thread-safe. Each detection operation should be performed from
 * a single thread. Create a new instance for each crawl operation.</p>
 *
 * @see DocumentRegistry
 * @see ChangeSet
 * @see Source
 */
public class ChangeDetector {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChangeDetector.class);

    private final DocumentRegistry registry;

    /**
     * Creates a new ChangeDetector with the specified registry.
     *
     * @param registry The document registry to compare against
     * @throws NullPointerException if registry is null
     */
    public ChangeDetector(DocumentRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * Detects changes by crawling the source and comparing with the registry.
     *
     * @param source The source to crawl
     * @return ChangeSet describing new, changed, and deleted documents
     * @throws SourceException if crawling fails
     * @throws NullPointerException if source is null
     */
    public ChangeSet detectChanges(Source source) throws SourceException {
        Objects.requireNonNull(source, "source must not be null");
        
        String sourceId = source.getSourceId();
        ChangeSet.Builder builder = ChangeSet.builder(sourceId);
        builder.totalRegistered(registry.size(sourceId));
        
        Set<String> seenDocumentIds = new HashSet<>();
        
        try (Stream<String> crawl = source.crawl()) {
            crawl.forEach(documentId -> {
                if (processDocument(source, sourceId, documentId, builder)) {
                    seenDocumentIds.add(documentId);
                }
            });
        } catch (IncompleteCrawlException e) {
            throw truncated(sourceId, e);
        }
        
        for (String registeredDocId : registry.getDocumentIds(sourceId)) {
            if (!seenDocumentIds.contains(registeredDocId)) {
                DocumentMetadata metadata = registry.get(sourceId, registeredDocId);
                if (metadata != null) {
                    builder.addDeleted(metadata);
                }
            }
        }
        
        builder.totalCrawled(seenDocumentIds.size());
        return builder.build();
    }

    /**
     * Turns a crawl that stopped early into the failure its callers already handle.
     *
     * <p>Everything after the crawl infers deletions by subtraction: a registered document the
     * crawl did not list is taken to be one the source no longer serves. That is only sound over
     * a complete listing, and there is no partial answer worth returning from an incomplete one -
     * the documents it never reached are exactly the ones it would tombstone. So the detection
     * fails and the corpus is left as it is.</p>
     */
    private static SourceException truncated(String sourceId, IncompleteCrawlException cause) {
        LOGGER.error("Change detection for source {} abandoned: {}", sourceId, cause.getMessage());
        // Not retryable: the crawl did not fail on its own: something closed the source, and
        // repeating it against a closed source gives the same answer.
        return new SourceException(cause.getMessage(), sourceId, false, cause);
    }

    /**
     * Checks if a specific document has changed.
     *
     * @param source The source containing the document
     * @param documentId The document ID to check
     * @return true if the document is new or has changed
     * @throws SourceException if fetching fails
     */
    public boolean hasChanged(Source source, String documentId) throws SourceException {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");

        DocumentMetadata existing = registry.get(source.getSourceId(), documentId);
        return existing == null || changedVersion(source, documentId, existing).isPresent();
    }

    /**
     * Asks the source whether the indexed version of a document is still current.
     *
     * <p>The one place a registered document is compared with its source, so a re-crawl and a
     * single-document check cannot come to different answers. The conditional fetch is tried
     * first; only a document the source actually sent back is compared by timestamp, length and
     * content hash.</p>
     *
     * <p>A document sent back in full and found unchanged still brought news: the validators
     * its source now names this version by, and the decoding it would be read with. They are
     * recorded, so the next re-crawl asks with them - see {@link #refreshRecord}.</p>
     *
     * @param source     The source holding the document
     * @param documentId The document
     * @param existing   What the registry recorded for the indexed version
     * @return What the source now holds, or empty when the indexed version is current
     * @throws SourceException if the source cannot be asked
     */
    private Optional<DocumentMetadata.FetchedDocumentInfo> changedVersion(
            Source source, String documentId, DocumentMetadata existing) throws SourceException {
        Optional<FetchedDocument> fetched =
            source.fetchIfChanged(documentId, existing.validators());
        if (fetched.isEmpty()) {
            return Optional.empty();
        }
        DocumentMetadata.FetchedDocumentInfo info = DocumentRegistry.toFetchedInfo(fetched.get());
        if (existing.isChanged(info)) {
            return Optional.of(info);
        }
        refreshRecord(existing, fetched.get().validators(), info.getDecoding());
        return Optional.empty();
    }

    /**
     * Records the validators an unchanged document came back with, when they differ, and its
     * decoding, when the entry has none.
     *
     * <p>Only a changed document is reindexed, and reindexing is what otherwise writes a
     * document's validators. Without this, one whose entity tag the registry never held - every
     * document registered before tags were kept - or whose tag the server rotated over the same
     * bytes, as a redeployment does, would be sent in full on every re-crawl for as long as its
     * content did not change: each fetch asks with the stale tag, the server cannot match it,
     * and the tag the answer carried is thrown away. The same goes for a file touched with its
     * bytes intact: its new modification time is recorded, so the entry goes on describing the
     * file as it now stands.</p>
     *
     * <p>Each validator is replaced only when the answer carried one. An answer without a tag
     * is not evidence against the recorded one - the server can only ever answer 304 to a tag
     * it currently issues, so a stale tag costs nothing a missing one would not - and a
     * timestamp the answer did not state is still the best one known. The change is made in
     * memory and reaches disk at the registry's next save, as every registration does; and only
     * if the entry is still the one read, so a reindex that registered a new version meanwhile
     * is never overwritten with this older one.</p>
     *
     * <p>A decoding is recorded only for an entry that has none - one registered before
     * decodings were kept - since an entry that has one and differs was reported changed rather
     * than reaching here. It is taken from the document as it would be read now, the best
     * account there is of how it was read; from then on the entry has a decoding to compare, and
     * a later change to its source's configuration is seen.</p>
     *
     * @param existing What the registry recorded for the indexed version
     * @param received What the source named the same version by just now
     * @param decoding How the same bytes would be read into text now, or null when not known
     */
    private void refreshRecord(DocumentMetadata existing, DocumentValidators received,
                               String decoding) {
        DocumentValidators recorded = existing.validators();
        long lastModified = received.hasLastModified()
            ? received.lastModifiedMillis()
            : recorded.lastModifiedMillis();
        String entityTag = received.hasEntityTag() ? received.entityTag() : recorded.entityTag();
        String recordedDecoding = existing.hasDecoding() ? existing.getDecoding() : decoding;
        if (lastModified == recorded.lastModifiedMillis()
                && Objects.equals(entityTag, recorded.entityTag())
                && Objects.equals(recordedDecoding, existing.getDecoding())) {
            return;
        }
        DocumentMetadata refreshed = existing.toBuilder()
            .lastModifiedMillis(lastModified)
            .entityTag(entityTag)
            .decoding(recordedDecoding)
            .build();
        if (registry.replace(existing, refreshed)) {
            LOGGER.debug("Recorded what unchanged document {} of source {} came back with",
                existing.getDocumentId(), existing.getSourceId());
        }
    }

    /**
     * Classifies one crawled document as new, changed or unchanged.
     *
     * <p>A fetch failure is not evidence of either outcome, and the two kinds have to be told
     * apart. A {@link DocumentNotFoundException} means the crawl listed a document that is no
     * longer there, so it is reported as unseen and the deleted-document pass tombstones it. Any
     * other failure is transient - the host is down, the request timed out - and treating it as
     * unseen would tombstone a document that still exists, so the previous version is kept, the
     * failure is logged, and the document is reported unchecked rather than silently recorded as
     * unchanged.</p>
     *
     * <p>A {@link FetchDisallowedException} is not a failure but an answer: the publisher now
     * forbids fetching the document - a robots.txt that disallows it, or the URL it redirects
     * to. It is reported as unseen, exactly as it would be had the crawl left it out, so the
     * index stops serving what the site has asked crawlers to leave alone.</p>
     *
     * @return true if the document was accounted for, false if the source says it is gone
     */
    private boolean processDocument(Source source, String sourceId, String documentId,
                                    ChangeSet.Builder builder) {
        DocumentMetadata existing = registry.get(sourceId, documentId);
        
        if (existing == null) {
            builder.addNew(documentId);
            return true;
        }
        
        try {
            changedVersion(source, documentId, existing)
                .ifPresent(info -> builder.addChanged(documentId, existing, info));
            return true;
        } catch (DocumentNotFoundException e) {
            LOGGER.info("Document {} of source {} disappeared between crawl and fetch; treating as deleted",
                documentId, sourceId);
            return false;
        } catch (FetchDisallowedException e) {
            LOGGER.info("Document {} of source {} may no longer be fetched; treating as deleted: {}",
                documentId, sourceId, e.getMessage());
            return false;
        } catch (SourceException e) {
            LOGGER.warn("Could not determine whether document {} of source {} changed: {}. "
                + "Keeping the indexed version.", documentId, sourceId, e.getMessage());
            builder.addUnchecked(documentId);
            return true;
        }
    }

    /**
     * Returns the document registry.
     *
     * @return The registry
     */
    public DocumentRegistry getRegistry() {
        return registry;
    }

    @Override
    public String toString() {
        return String.format("ChangeDetector[registry=%s]", registry);
    }
}
