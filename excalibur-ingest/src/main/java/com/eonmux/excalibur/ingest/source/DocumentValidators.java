package com.eonmux.excalibur.ingest.source;

/**
 * What a source reported about the version of a document that is already indexed, in the form
 * it can be asked about again.
 *
 * <h2>Why these two and not the content hash</h2>
 * <p>A content hash can only be compared after the body has been transferred, which is the cost
 * a re-crawl is trying not to pay. These are the values an HTTP origin issued with the version
 * it served - {@code ETag} and {@code Last-Modified} - and the origin itself can compare them
 * against what it holds now, answering {@code 304 Not Modified} without sending the body. They
 * are kept in the document registry beside the hash, so a node that restarted between crawls
 * asks with the same validators the previous process received.</p>
 *
 * <p>Either may be absent. A generated page often carries neither, a static file server both,
 * and a document indexed by a build that did not record entity tags carries only its
 * timestamp. A source asks with whatever is present and, with nothing present, has nothing to
 * ask with and fetches in full.</p>
 *
 * @param lastModifiedMillis The {@code Last-Modified} the source reported, in epoch millis, or
 *                           0 when it reported none
 * @param entityTag          The entity tag exactly as the source reported it, or null for none
 */
public record DocumentValidators(long lastModifiedMillis, String entityTag) {

    /** Validators for a version about which the source said nothing it could be asked about. */
    public static final DocumentValidators NONE = new DocumentValidators(0L, null);

    /**
     * Normalises an unknown timestamp to 0 and a blank tag to null.
     */
    public DocumentValidators {
        lastModifiedMillis = Math.max(0L, lastModifiedMillis);
        entityTag = entityTag == null || entityTag.isBlank() ? null : entityTag;
    }

    /**
     * Whether the source reported a modification time for the version.
     *
     * @return true when there is a timestamp to ask with
     */
    public boolean hasLastModified() {
        return lastModifiedMillis > 0;
    }

    /**
     * Whether the source reported an entity tag for the version.
     *
     * @return true when there is a tag to ask with
     */
    public boolean hasEntityTag() {
        return entityTag != null;
    }

    /**
     * Whether there is anything here a source could ask its origin about.
     *
     * @return true when neither validator is present
     */
    public boolean isEmpty() {
        return !hasLastModified() && !hasEntityTag();
    }
}
