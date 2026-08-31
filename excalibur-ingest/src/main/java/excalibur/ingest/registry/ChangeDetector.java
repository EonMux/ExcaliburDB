package excalibur.ingest.registry;

import excalibur.ingest.source.DocumentNotFoundException;
import excalibur.ingest.source.FetchedDocument;
import excalibur.ingest.source.Source;
import excalibur.ingest.source.SourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Detects changed and deleted documents during re-crawl operations.
 *
 * <h2>Change Detection Strategy</h2>
 * <p>The detector uses multiple signals to determine if a document has changed:
 * <ol>
 *   <li><strong>Last Modified Timestamp:</strong> If the source provides lastModified,
 *       compare with the stored timestamp. Newer = changed.</li>
 *   <li><strong>Content Length:</strong> If lengths differ, document is changed.</li>
 *   <li><strong>Content Hash:</strong> If both old and new hashes are available and differ,
 *       document is changed.</li>
 * </ol>
 *
 * <h2>Deleted Document Detection</h2>
 * <p>After processing all crawled documents, any document in the registry that was not
 * seen during the crawl is considered deleted.</p>
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
     * Detects changes incrementally, fetching documents in batches.
     *
     * @param source The source to crawl
     * @param batchSize Number of documents to process per batch
     * @param batchConsumer Consumer called for each batch
     * @return Final ChangeSet containing deleted documents
     * @throws SourceException if crawling fails
     */
    public ChangeSet detectChangesIncremental(Source source, int batchSize,
                                              Consumer<ChangeSet> batchConsumer) throws SourceException {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(batchConsumer, "batchConsumer must not be null");
        
        String sourceId = source.getSourceId();
        Set<String> seenDocumentIds = new HashSet<>();
        int totalRegistered = registry.size(sourceId);
        int totalCrawled = 0;
        
        try (Stream<String> crawl = source.crawl()) {
            ChangeSet.Builder batchBuilder = ChangeSet.builder(sourceId);
            batchBuilder.totalRegistered(totalRegistered);
            int batchCount = 0;
            
            for (String documentId : (Iterable<String>) crawl::iterator) {
                if (processDocument(source, sourceId, documentId, batchBuilder)) {
                    seenDocumentIds.add(documentId);
                }
                batchCount++;
                totalCrawled++;
                
                if (batchCount >= batchSize) {
                    batchBuilder.totalCrawled(batchCount);
                    batchConsumer.accept(batchBuilder.build());
                    
                    batchBuilder = ChangeSet.builder(sourceId);
                    batchBuilder.totalRegistered(totalRegistered);
                    batchCount = 0;
                }
            }
            
            if (batchCount > 0) {
                batchBuilder.totalCrawled(batchCount);
                batchConsumer.accept(batchBuilder.build());
            }
        }
        
        ChangeSet.Builder finalBuilder = ChangeSet.builder(sourceId);
        finalBuilder.totalCrawled(totalCrawled);
        finalBuilder.totalRegistered(totalRegistered);
        
        for (String registeredDocId : registry.getDocumentIds(sourceId)) {
            if (!seenDocumentIds.contains(registeredDocId)) {
                DocumentMetadata metadata = registry.get(sourceId, registeredDocId);
                if (metadata != null) {
                    finalBuilder.addDeleted(metadata);
                }
            }
        }
        
        return finalBuilder.build();
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
        
        String sourceId = source.getSourceId();
        DocumentMetadata existing = registry.get(sourceId, documentId);
        
        if (existing == null) {
            return true;
        }
        
        FetchedDocument fetched = source.fetch(documentId);
        DocumentMetadata.FetchedDocumentInfo fetchedInfo = DocumentRegistry.toFetchedInfo(fetched);
        
        return existing.isChanged(fetchedInfo);
    }

    /**
     * Checks if a source has changed since the given timestamp.
     *
     * @param source The source to check
     * @param lastCrawlTime The time of the last crawl
     * @return true if the source may have changes
     * @throws SourceException if change detection fails
     */
    public boolean hasSourceChanged(Source source, Instant lastCrawlTime) throws SourceException {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(lastCrawlTime, "lastCrawlTime must not be null");
        
        return source.hasChanged(lastCrawlTime);
    }

    /**
     * Classifies one crawled document as new, changed or unchanged.
     *
     * <p>A fetch failure is not evidence of either outcome, and the two kinds have to be told
     * apart. A {@link DocumentNotFoundException} means the crawl listed a document that is no
     * longer there, so it is reported as unseen and the deleted-document pass tombstones it. Any
     * other failure is transient - the host is down, the request timed out - and treating it as
     * unseen would tombstone a document that still exists, so the previous version is kept and
     * the failure is logged rather than silently recorded as "unchanged".</p>
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
            FetchedDocument fetched = source.fetch(documentId);
            DocumentMetadata.FetchedDocumentInfo fetchedInfo = DocumentRegistry.toFetchedInfo(fetched);
            
            if (existing.isChanged(fetchedInfo)) {
                builder.addChanged(documentId, existing, fetchedInfo);
            }
            return true;
        } catch (DocumentNotFoundException e) {
            LOGGER.info("Document {} of source {} disappeared between crawl and fetch; treating as deleted",
                documentId, sourceId);
            return false;
        } catch (SourceException e) {
            LOGGER.warn("Could not determine whether document {} of source {} changed: {}. "
                + "Keeping the indexed version.", documentId, sourceId, e.getMessage());
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
