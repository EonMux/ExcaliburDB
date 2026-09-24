package com.eonmux.excalibur.api.embedded;

import java.util.List;

/**
 * What one {@link ExcaliburEmbedded#erase} call removed.
 *
 * <p>The counts {@code POST /v1/erasure} reports, less the ones about compaction and the graph,
 * which the embedded facade does not drive.</p>
 *
 * @param erasedDocumentIds   The documents the node held and no longer holds any part of
 * @param documentsNotFound   Documents named that the node never held under that source
 * @param documentsIncomplete Documents only partly removed; erasing them again finishes the job
 * @param chunksNotRemoved    Chunks storage would not release, so their content is still on disk
 */
public record ErasureOutcome(List<String> erasedDocumentIds, int documentsNotFound,
                             int documentsIncomplete, int chunksNotRemoved) {

    /**
     * @throws NullPointerException if erasedDocumentIds is null
     */
    public ErasureOutcome {
        erasedDocumentIds = List.copyOf(erasedDocumentIds);
    }
}
