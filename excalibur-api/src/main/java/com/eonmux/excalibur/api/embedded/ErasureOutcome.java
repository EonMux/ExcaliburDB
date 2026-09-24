package com.eonmux.excalibur.api.embedded;

import java.util.List;

/**
 * What one {@link ExcaliburEmbedded#erase} or {@link ExcaliburEmbedded#eraseSource} call removed.
 *
 * <p>The counts {@code POST /v1/erasure} reports, less the ones about compaction and the graph,
 * which the embedded facade does not drive.</p>
 *
 * @param erasedDocumentIds   The documents the node held and no longer holds any part of
 * @param documentsNotFound   Documents named that the node never held under that source; always
 *                            zero for a whole-source erasure, which names none
 * @param documentsIncomplete Documents only partly removed; erasing them again finishes the job
 * @param documentsRemaining  Documents of the source the call did not reach, because they were
 *                            supplied while it ran; always zero for an erasure of named
 *                            documents
 * @param chunksNotRemoved    Chunks storage would not release, so their content is still on disk
 * @param sourceDeregistered  Whether a crawl registered under the source id in this data
 *                            directory was deregistered, so a served node no longer crawls it;
 *                            only a whole-source erasure deregisters
 */
public record ErasureOutcome(List<String> erasedDocumentIds, int documentsNotFound,
                             int documentsIncomplete, int documentsRemaining,
                             int chunksNotRemoved, boolean sourceDeregistered) {

    /**
     * @throws NullPointerException if erasedDocumentIds is null
     */
    public ErasureOutcome {
        erasedDocumentIds = List.copyOf(erasedDocumentIds);
    }
}
