package excalibur.ingest.buffer;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AtomicDocumentBatch.
 */
class AtomicDocumentBatchTest {

    @Test
    void testConstructor_validInput() {
        List<String> chunkIds = Arrays.asList("doc1:0", "doc1:1", "doc1:2");
        List<Long> entryIds = Arrays.asList(100L, 101L, 102L);
        
        AtomicDocumentBatch batch = new AtomicDocumentBatch(
            "doc1", 1000L, 2000L, chunkIds, entryIds);
        
        assertEquals("doc1", batch.getDocumentId());
        assertEquals(1000L, batch.getStartOffset());
        assertEquals(2000L, batch.getEndOffset());
        assertEquals(1000L, batch.getByteSize());
        assertEquals(3, batch.getChunkCount());
        assertEquals(chunkIds, batch.getChunkIdList());
        assertEquals(entryIds, batch.getEntryIdList());
    }

    @Test
    void testConstructor_nullDocumentId() {
        assertThrows(NullPointerException.class, 
            () -> new AtomicDocumentBatch(null, 0L, 100L, 
                Collections.singletonList("chunk"), Collections.singletonList(1L)));
    }

    @Test
    void testConstructor_emptyDocumentId() {
        assertThrows(IllegalArgumentException.class, 
            () -> new AtomicDocumentBatch("", 0L, 100L, 
                Collections.singletonList("chunk"), Collections.singletonList(1L)));
    }

    @Test
    void testConstructor_nullChunkIds() {
        assertThrows(NullPointerException.class, 
            () -> new AtomicDocumentBatch("doc1", 0L, 100L, null, Collections.singletonList(1L)));
    }

    @Test
    void testConstructor_nullEntryIds() {
        assertThrows(NullPointerException.class, 
            () -> new AtomicDocumentBatch("doc1", 0L, 100L, 
                Collections.singletonList("chunk"), null));
    }

    @Test
    void testConstructor_negativeStartOffset() {
        assertThrows(IllegalArgumentException.class, 
            () -> new AtomicDocumentBatch("doc1", -1L, 100L, 
                Collections.singletonList("chunk"), Collections.singletonList(1L)));
    }

    @Test
    void testConstructor_negativeEndOffset() {
        assertThrows(IllegalArgumentException.class, 
            () -> new AtomicDocumentBatch("doc1", 0L, -1L, 
                Collections.singletonList("chunk"), Collections.singletonList(1L)));
    }

    @Test
    void testConstructor_endOffsetNotGreaterThanStart() {
        assertThrows(IllegalArgumentException.class, 
            () -> new AtomicDocumentBatch("doc1", 100L, 100L, 
                Collections.singletonList("chunk"), Collections.singletonList(1L)));
        
        assertThrows(IllegalArgumentException.class, 
            () -> new AtomicDocumentBatch("doc1", 200L, 100L, 
                Collections.singletonList("chunk"), Collections.singletonList(1L)));
    }

    @Test
    void testConstructor_mismatchedSizes() {
        List<String> chunkIds = Arrays.asList("doc1:0", "doc1:1");
        List<Long> entryIds = Collections.singletonList(100L);
        
        assertThrows(IllegalArgumentException.class, 
            () -> new AtomicDocumentBatch("doc1", 0L, 100L, chunkIds, entryIds));
    }

    @Test
    void testGetFirstChunkId() {
        List<String> chunkIds = Arrays.asList("doc1:0", "doc1:1", "doc1:2");
        List<Long> entryIds = Arrays.asList(100L, 101L, 102L);
        
        AtomicDocumentBatch batch = new AtomicDocumentBatch(
            "doc1", 0L, 100L, chunkIds, entryIds);
        
        assertEquals("doc1:0", batch.getFirstChunkId());
        assertEquals("doc1:2", batch.getLastChunkId());
    }

    @Test
    void testGetFirstChunkId_empty() {
        AtomicDocumentBatch batch = new AtomicDocumentBatch(
            "doc1", 0L, 100L, Collections.emptyList(), Collections.emptyList());
        
        assertNull(batch.getFirstChunkId());
        assertNull(batch.getLastChunkId());
    }

    @Test
    void testGetFirstEntryId() {
        List<Long> entryIds = Arrays.asList(100L, 101L, 102L);
        
        AtomicDocumentBatch batch = new AtomicDocumentBatch(
            "doc1", 0L, 100L, Arrays.asList("a", "b", "c"), entryIds);
        
        assertEquals(100L, batch.getFirstEntryId());
        assertEquals(102L, batch.getLastEntryId());
    }

    @Test
    void testGetFirstEntryId_empty() {
        AtomicDocumentBatch batch = new AtomicDocumentBatch(
            "doc1", 0L, 100L, Collections.emptyList(), Collections.emptyList());
        
        assertEquals(-1L, batch.getFirstEntryId());
        assertEquals(-1L, batch.getLastEntryId());
    }

    @Test
    void testContainsOffset() {
        AtomicDocumentBatch batch = new AtomicDocumentBatch(
            "doc1", 1000L, 2000L, 
            Collections.singletonList("chunk"), Collections.singletonList(1L));
        
        // Inside range
        assertTrue(batch.containsOffset(1000L));
        assertTrue(batch.containsOffset(1500L));
        assertTrue(batch.containsOffset(1999L));
        
        // Outside range
        assertFalse(batch.containsOffset(999L));
        assertFalse(batch.containsOffset(2000L));
        assertFalse(batch.containsOffset(2001L));
    }

    @Test
    void testIsEmpty() {
        AtomicDocumentBatch empty = new AtomicDocumentBatch(
            "doc1", 0L, 100L, Collections.emptyList(), Collections.emptyList());
        assertTrue(empty.isEmpty());
        
        AtomicDocumentBatch nonEmpty = new AtomicDocumentBatch(
            "doc1", 0L, 100L, 
            Collections.singletonList("chunk"), Collections.singletonList(1L));
        assertFalse(nonEmpty.isEmpty());
    }

    @Test
    void testGetByteSize() {
        AtomicDocumentBatch batch = new AtomicDocumentBatch(
            "doc1", 1000L, 2500L, 
            Collections.singletonList("chunk"), Collections.singletonList(1L));
        
        assertEquals(1500L, batch.getByteSize());
    }

    @Test
    void testEquals() {
        List<String> chunkIds = Arrays.asList("doc1:0", "doc1:1");
        List<Long> entryIds = Arrays.asList(100L, 101L);
        
        AtomicDocumentBatch batch1 = new AtomicDocumentBatch(
            "doc1", 0L, 100L, chunkIds, entryIds);
        AtomicDocumentBatch batch2 = new AtomicDocumentBatch(
            "doc1", 0L, 100L, chunkIds, entryIds);
        AtomicDocumentBatch batch3 = new AtomicDocumentBatch(
            "doc2", 0L, 100L, chunkIds, entryIds);
        
        assertEquals(batch1, batch1); // Same instance
        assertEquals(batch1, batch2); // Equal values
        assertNotEquals(batch1, batch3); // Different documentId
        assertNotEquals(batch1, null);
        assertNotEquals(batch1, "string");
    }

    @Test
    void testHashCode() {
        List<String> chunkIds = Arrays.asList("doc1:0", "doc1:1");
        List<Long> entryIds = Arrays.asList(100L, 101L);
        
        AtomicDocumentBatch batch1 = new AtomicDocumentBatch(
            "doc1", 0L, 100L, chunkIds, entryIds);
        AtomicDocumentBatch batch2 = new AtomicDocumentBatch(
            "doc1", 0L, 100L, chunkIds, entryIds);
        
        assertEquals(batch1.hashCode(), batch2.hashCode());
    }

    @Test
    void testToString() {
        List<String> chunkIds = Arrays.asList("doc1:0");
        List<Long> entryIds = Arrays.asList(100L);
        
        AtomicDocumentBatch batch = new AtomicDocumentBatch(
            "doc1", 1000L, 2500L, chunkIds, entryIds);
        
        String str = batch.toString();
        
        assertTrue(str.contains("doc1"));
        assertTrue(str.contains("1000"));
        assertTrue(str.contains("2500"));
        assertTrue(str.contains("1")); // chunk count
        assertTrue(str.contains("1500")); // byte size
    }

    @Test
    void testBuilder() {
        List<String> chunkIds = Arrays.asList("doc1:0", "doc1:1");
        List<Long> entryIds = Arrays.asList(100L, 101L);
        
        AtomicDocumentBatch batch = AtomicDocumentBatch.builder()
            .documentId("doc1")
            .startOffset(0L)
            .endOffset(100L)
            .chunkIds(chunkIds)
            .entryIds(entryIds)
            .build();
        
        assertEquals("doc1", batch.getDocumentId());
        assertEquals(0L, batch.getStartOffset());
        assertEquals(100L, batch.getEndOffset());
        assertEquals(chunkIds, batch.getChunkIdList());
        assertEquals(entryIds, batch.getEntryIdList());
    }

    @Test
    void testBuilder_nullChunkIds() {
        AtomicDocumentBatch batch = AtomicDocumentBatch.builder()
            .documentId("doc1")
            .startOffset(0L)
            .endOffset(100L)
            .chunkIds(null)
            .entryIds(null)
            .build();
        
        assertEquals(Collections.emptyList(), batch.getChunkIdList());
        assertEquals(Collections.emptyList(), batch.getEntryIdList());
    }

    @Test
    void testImmutability() {
        List<String> chunkIds = new java.util.ArrayList<>(Arrays.asList("doc1:0"));
        List<Long> entryIds = new java.util.ArrayList<>(Arrays.asList(100L));
        
        AtomicDocumentBatch batch = new AtomicDocumentBatch(
            "doc1", 0L, 100L, Collections.unmodifiableList(chunkIds), entryIds);
        
        // Verify the lists are unmodifiable (would throw if we tried to modify)
        assertThrows(UnsupportedOperationException.class, 
            () -> batch.getChunkIdList().add("new"));
        assertThrows(UnsupportedOperationException.class, 
            () -> batch.getEntryIdList().add(999L));
    }
}
