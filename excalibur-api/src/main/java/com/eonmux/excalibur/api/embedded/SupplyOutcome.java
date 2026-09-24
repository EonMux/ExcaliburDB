package com.eonmux.excalibur.api.embedded;

/**
 * What one {@link ExcaliburEmbedded#supply} call ingested.
 *
 * <p>The same counts {@code POST /v1/documents} answers with. The documents it counts as
 * ingested are searchable when the call returns.</p>
 *
 * @param sourceId          The source the documents were supplied under
 * @param documentsIngested Documents chunked, embedded and published
 * @param chunksIngested    Chunks written for them
 * @param documentsFailed   Documents that could not be ingested; each failure is logged, and a
 *                          replacement that failed leaves the previous version searchable
 * @param stopped           Whether the node began closing during the call, so the counts
 *                          describe a prefix of what was supplied
 */
public record SupplyOutcome(String sourceId, int documentsIngested, long chunksIngested,
                            int documentsFailed, boolean stopped) {
}
