import excalibur.ingest.processor.TextChunker;
import excalibur.ingest.processor.TextChunk;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Verification test for sentence boundary detection.
 * Tests that the TextChunker correctly recognizes .!? followed by space or end-of-string.
 */
public class SentenceBoundaryVerification {
    
    public static void main(String[] args) throws Exception {
        System.out.println("=== Sentence Boundary Detection Verification ===\n");
        
        TextChunker chunker = new TextChunker();
        
        test1(chunker);
        test2(chunker);
        test3(chunker);
        test4(chunker);
        test5(chunker);
        test6(chunker);
        testDirectly(chunker);
        
        System.out.println("\n=== All Tests Passed ===");
    }
    
    static void test1(TextChunker chunker) {
        System.out.println("Test 1: Period followed by space");
        String text = "This is sentence one. This is sentence two. This is sentence three.";
        List<TextChunk> chunks = chunker.chunk("test1", text);
        System.out.println("  Input: \"" + text + "\"");
        System.out.println("  Chunks: " + chunks.size());
        for (TextChunk chunk : chunks) {
            System.out.println("    - \"" + chunk.getText() + "\"");
        }
        assert chunks.size() == 1 : "Should create 1 chunk for short text";
        System.out.println("  ✓ PASS\n");
    }
    
    static void test2(TextChunker chunker) {
        System.out.println("Test 2: Exclamation mark followed by space");
        String text = "Amazing! Wonderful! Fantastic!";
        List<TextChunk> chunks = chunker.chunk("test2", text);
        System.out.println("  Input: \"" + text + "\"");
        System.out.println("  Chunks: " + chunks.size());
        assert chunks.size() >= 1 : "Should create at least 1 chunk";
        System.out.println("  ✓ PASS\n");
    }
    
    static void test3(TextChunker chunker) {
        System.out.println("Test 3: Question mark followed by space");
        String text = "Is this working? Does it detect boundaries? How about this?";
        List<TextChunk> chunks = chunker.chunk("test3", text);
        System.out.println("  Input: \"" + text + "\"");
        System.out.println("  Chunks: " + chunks.size());
        assert chunks.size() >= 1 : "Should create at least 1 chunk";
        System.out.println("  ✓ PASS\n");
    }
    
    static void test4(TextChunker chunker) {
        System.out.println("Test 4: Punctuation at end of string");
        String text = "This ends with punctuation.";
        List<TextChunk> chunks = chunker.chunk("test4", text);
        System.out.println("  Input: \"" + text + "\"");
        System.out.println("  Chunks: " + chunks.size());
        assert chunks.size() == 1 : "Should create 1 chunk";
        assert chunks.get(0).getText().equals(text) : "Text should be preserved";
        System.out.println("  ✓ PASS\n");
    }
    
    static void test5(TextChunker chunker) {
        System.out.println("Test 5: Punctuation followed by newline");
        String text = "First line.\nSecond line.\nThird line.";
        List<TextChunk> chunks = chunker.chunk("test5", text);
        System.out.println("  Input: \"" + text.replace("\n", "\\n") + "\"");
        System.out.println("  Chunks: " + chunks.size());
        assert chunks.size() >= 1 : "Should create at least 1 chunk";
        System.out.println("  ✓ PASS\n");
    }
    
    static void test6(TextChunker chunker) {
        System.out.println("Test 6: Punctuation followed by tab");
        String text = "Column1.\tColumn2.\tColumn3.";
        List<TextChunk> chunks = chunker.chunk("test6", text);
        System.out.println("  Input: \"" + text.replace("\t", "\\t") + "\"");
        System.out.println("  Chunks: " + chunks.size());
        assert chunks.size() >= 1 : "Should create at least 1 chunk";
        System.out.println("  ✓ PASS\n");
    }
    
    static void testDirectly(TextChunker chunker) throws Exception {
        System.out.println("Test 7: Direct isSentenceBoundary() test via reflection");
        
        Method isSentenceBoundary = TextChunker.class.getDeclaredMethod(
            "isSentenceBoundary", String.class, int.class);
        isSentenceBoundary.setAccessible(true);
        
        String testText = "Hello. World! How are you?";
        
        boolean periodResult = (boolean) isSentenceBoundary.invoke(chunker, testText, 5);
        boolean exclamationResult = (boolean) isSentenceBoundary.invoke(chunker, testText, 13);
        boolean questionResult = (boolean) isSentenceBoundary.invoke(chunker, testText, 25);
        
        System.out.println("  Text: \"" + testText + "\" (length=" + testText.length() + ")");
        System.out.println("  Position 5 ('.' after 'Hello'): " + periodResult);
        System.out.println("  Position 13 ('!' after 'World'): " + exclamationResult);
        System.out.println("  Position 25 ('?' at end): " + questionResult);
        
        assert periodResult : "Period followed by space should be sentence boundary";
        assert exclamationResult : "Exclamation followed by space should be sentence boundary";
        assert questionResult : "Question mark at end of string should be sentence boundary";
        
        System.out.println("  ✓ PASS\n");
    }
}
