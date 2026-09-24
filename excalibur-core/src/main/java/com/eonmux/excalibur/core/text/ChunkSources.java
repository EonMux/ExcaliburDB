package com.eonmux.excalibur.core.text;

/**
 * Which source a chunk belongs to.
 *
 * <p>Not derivable from the chunk id alone. A chunk id begins with its source's id, but a source
 * id may contain a colon, so {@code team:wiki:home.md:chunk:0} begins with the id of source
 * {@code team} as much as with that of {@code team:wiki}. Only the record written when the
 * document was registered says which one it came from, so the answer is the document registry's
 * rather than anything parsed out of the name.</p>
 */
@FunctionalInterface
public interface ChunkSources {

    /**
     * Names the source whose document a chunk is.
     *
     * @param chunkId The chunk's id, {@code sourceId:documentId:chunk:N}
     * @return The source id, or null when no registered document holds the chunk
     */
    String sourceOf(String chunkId);
}
