package com.eonmux.excalibur.ingest.registry;

import com.eonmux.excalibur.ingest.source.FetchedDocument;
import com.eonmux.excalibur.ingest.source.Source;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A source id may contain a colon, so one source's id can be another's followed by
 * {@code ":"} and more. The registry's document key begins with the source id, and a lookup by
 * key prefix then reaches into the longer source: a re-crawl of {@code team} would report every
 * document of {@code team:wiki} as deleted, and erasing {@code team} would erase them.
 */
class ColonSourceIdTest {

    private DocumentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DocumentRegistry();
        registry.register(metadata("team", "guide.md", "guide"));
        registry.register(metadata("team:wiki", "home.md", "home"));
        registry.register(metadata("team:wiki", "faq.md", "faq"));
    }

    @Test
    void aSourceHoldsOnlyItsOwnDocuments() {
        assertEquals(List.of("guide.md"), List.copyOf(registry.getDocumentIds("team")));
        assertEquals(1, registry.size("team"));
        assertEquals(1, registry.getBySource("team").size());

        assertEquals(2, registry.size("team:wiki"));
        assertEquals(2, registry.getBySource("team:wiki").size());
        assertTrue(registry.getDocumentIds("team:wiki").containsAll(List.of("home.md", "faq.md")));
    }

    @Test
    void aRecrawlDoesNotDeleteTheDocumentsOfALongerSourceId() throws Exception {
        ChangeSet changes = new ChangeDetector(registry)
            .detectChanges(new OneDocumentSource("team", "guide.md", "guide"));

        assertEquals(0, changes.getDeletedCount());
        assertEquals(1, changes.getTotalRegistered());
    }

    @Test
    void aDocumentKeyNamesTheSourceThatRegisteredIt() {
        assertEquals("team:wiki", registry.getByKey("team:wiki:home.md").getSourceId());
        assertEquals("team", registry.getByKey("team:guide.md").getSourceId());
        assertNull(registry.getByKey("team:missing.md"));
    }

    private static DocumentMetadata metadata(String sourceId, String documentId, String text) {
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        return DocumentMetadata.builder()
            .sourceId(sourceId)
            .documentId(documentId)
            .contentHash(DocumentRegistry.computeContentHash(content))
            .contentLength(content.length)
            .lastModifiedMillis(1000L)
            .build();
    }

    /** A source whose crawl finds one unchanged document. */
    private record OneDocumentSource(String sourceId, String documentId, String text)
            implements Source {

        @Override
        public String getSourceId() {
            return sourceId;
        }

        @Override
        public String getSourceType() {
            return "mock";
        }

        @Override
        public Stream<String> crawl() {
            return Stream.of(documentId);
        }

        @Override
        public FetchedDocument fetch(String id) {
            return new FetchedDocument(id, text.getBytes(StandardCharsets.UTF_8), "text/plain",
                1000L);
        }

        @Override
        public void close() {
        }
    }
}
