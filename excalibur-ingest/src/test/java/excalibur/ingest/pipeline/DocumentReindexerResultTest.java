package excalibur.ingest.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for DocumentReindexer inner classes domain correctness.
 */
@DisplayName("DocumentReindexer inner classes")
class DocumentReindexerResultTest {

    @Nested
    @DisplayName("DocumentResult")
    class DocumentResultTest {

        @Test
        @DisplayName("stores all result fields")
        void storesAllResultFields() {
            DocumentReindexer.DocumentResult result = new DocumentReindexer.DocumentResult(
                "doc-123", 10, 8, 2, 1500L, "Some errors occurred");

            assertThat(result.getDocumentId()).isEqualTo("doc-123");
            assertThat(result.getTotalChunks()).isEqualTo(10);
            assertThat(result.getSuccessfulChunks()).isEqualTo(8);
            assertThat(result.getFailedChunks()).isEqualTo(2);
            assertThat(result.getElapsedMillis()).isEqualTo(1500L);
            assertThat(result.getError()).isEqualTo("Some errors occurred");
        }

        @Test
        @DisplayName("hasError returns true when error is not null")
        void hasErrorReturnsTrueWhenErrorNotNull() {
            DocumentReindexer.DocumentResult result = new DocumentReindexer.DocumentResult(
                "doc-1", 5, 5, 0, 100L, "Error message");

            assertThat(result.hasError()).isTrue();
        }

        @Test
        @DisplayName("hasError returns false when error is null")
        void hasErrorReturnsFalseWhenErrorNull() {
            DocumentReindexer.DocumentResult result = new DocumentReindexer.DocumentResult(
                "doc-1", 5, 5, 0, 100L, null);

            assertThat(result.hasError()).isFalse();
        }

        @Test
        @DisplayName("hasError returns false when error is empty string")
        void hasErrorReturnsFalseWhenErrorEmpty() {
            DocumentReindexer.DocumentResult result = new DocumentReindexer.DocumentResult(
                "doc-1", 5, 5, 0, 100L, "");

            assertThat(result.hasError()).isFalse();
        }

        @Test
        @DisplayName("isSuccess returns true when no failed chunks and chunks exist")
        void isSuccessReturnsTrueWhenNoFailedChunks() {
            DocumentReindexer.DocumentResult result = new DocumentReindexer.DocumentResult(
                "doc-1", 5, 5, 0, 100L, null);

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("isSuccess returns false when failed chunks exist")
        void isSuccessReturnsFalseWhenFailedChunksExist() {
            DocumentReindexer.DocumentResult result = new DocumentReindexer.DocumentResult(
                "doc-1", 5, 3, 2, 100L, "2 chunks failed");

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("isSuccess returns false when total chunks is zero")
        void isSuccessReturnsFalseWhenZeroChunks() {
            DocumentReindexer.DocumentResult result = new DocumentReindexer.DocumentResult(
                "doc-1", 0, 0, 0, 100L, "No chunks produced");

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("toString contains key fields")
        void toStringContainsKeyFields() {
            DocumentReindexer.DocumentResult result = new DocumentReindexer.DocumentResult(
                "doc-123", 10, 8, 2, 1500L, null);

            String str = result.toString();

            assertThat(str).contains("doc-123");
            assertThat(str).contains("chunks=10");
            assertThat(str).contains("success=8");
            assertThat(str).contains("failed=2");
        }
    }

    @Nested
    @DisplayName("ReindexResult")
    class ReindexResultTest {

        @Test
        @DisplayName("builder creates result with all fields")
        void builderCreatesResultWithAllFields() {
            DocumentReindexer.ReindexResult result = DocumentReindexer.ReindexResult.builder("source-1")
                .documentsIndexed(10)
                .documentsTombstoned(2)
                .chunksCreated(50)
                .chunksTombstoned(10)
                .chunksFailed(3)
                .build();

            assertThat(result.getSourceId()).isEqualTo("source-1");
            assertThat(result.getDocumentsIndexed()).isEqualTo(10);
            assertThat(result.getDocumentsTombstoned()).isEqualTo(2);
            assertThat(result.getChunksCreated()).isEqualTo(50);
            assertThat(result.getChunksTombstoned()).isEqualTo(10);
            assertThat(result.getChunksFailed()).isEqualTo(3);
        }

        @Test
        @DisplayName("builder rejects null sourceId")
        void builderRejectsNullSourceId() {
            assertThatThrownBy(() -> DocumentReindexer.ReindexResult.builder(null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("hasErrors returns false when no errors")
        void hasErrorsReturnsFalseWhenNoErrors() {
            DocumentReindexer.ReindexResult result = DocumentReindexer.ReindexResult.builder("source-1")
                .build();

            assertThat(result.hasErrors()).isFalse();
            assertThat(result.getErrorCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("hasErrors returns true when errors added")
        void hasErrorsReturnsTrueWhenErrorsAdded() {
            DocumentReindexer.ReindexResult result = DocumentReindexer.ReindexResult.builder("source-1")
                .addError("fetch", "doc-1", "Failed to fetch")
                .build();

            assertThat(result.hasErrors()).isTrue();
            assertThat(result.getErrorCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("isSuccess returns true when no errors and documents indexed")
        void isSuccessReturnsTrueWhenNoErrorsAndDocumentsIndexed() {
            DocumentReindexer.ReindexResult result = DocumentReindexer.ReindexResult.builder("source-1")
                .documentsIndexed(10)
                .build();

            assertThat(result.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("isSuccess returns false when errors exist")
        void isSuccessReturnsFalseWhenErrorsExist() {
            DocumentReindexer.ReindexResult result = DocumentReindexer.ReindexResult.builder("source-1")
                .documentsIndexed(10)
                .addError("fetch", "doc-1", "Failed")
                .build();

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("isSuccess returns false when no documents indexed")
        void isSuccessReturnsFalseWhenNoDocumentsIndexed() {
            DocumentReindexer.ReindexResult result = DocumentReindexer.ReindexResult.builder("source-1")
                .build();

            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("addDocumentResult increments counters")
        void addDocumentResultIncrementsCounters() {
            DocumentReindexer.DocumentResult docResult = new DocumentReindexer.DocumentResult(
                "doc-1", 5, 4, 1, 100L, "1 chunk failed");

            DocumentReindexer.ReindexResult result = DocumentReindexer.ReindexResult.builder("source-1")
                .addDocumentResult(docResult)
                .build();

            assertThat(result.getDocumentsIndexed()).isEqualTo(1);
            assertThat(result.getChunksCreated()).isEqualTo(4);
            assertThat(result.getChunksFailed()).isEqualTo(1);
        }

        @Test
        @DisplayName("getErrors returns immutable list")
        void getErrorsReturnsImmutableList() {
            DocumentReindexer.ReindexResult result = DocumentReindexer.ReindexResult.builder("source-1")
                .addError("fetch", "doc-1", "Failed")
                .build();

            List<DocumentReindexer.ReindexResult.OperationError> errors = result.getErrors();

            assertThatThrownBy(() -> errors.add(null))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("toString contains key fields")
        void toStringContainsKeyFields() {
            DocumentReindexer.ReindexResult result = DocumentReindexer.ReindexResult.builder("source-1")
                .documentsIndexed(10)
                .chunksCreated(50)
                .build();

            String str = result.toString();

            assertThat(str).contains("source=source-1");
            assertThat(str).contains("indexed=10");
            assertThat(str).contains("chunksCreated=50");
        }
    }

    @Nested
    @DisplayName("OperationError")
    class OperationErrorTest {

        @Test
        @DisplayName("stores all fields")
        void storesAllFields() {
            DocumentReindexer.ReindexResult.OperationError error =
                new DocumentReindexer.ReindexResult.OperationError("fetch", "doc-123", "Connection refused");

            assertThat(error.getComponent()).isEqualTo("fetch");
            assertThat(error.getId()).isEqualTo("doc-123");
            assertThat(error.getMessage()).isEqualTo("Connection refused");
        }

        @Test
        @DisplayName("toString contains all fields")
        void toStringContainsAllFields() {
            DocumentReindexer.ReindexResult.OperationError error =
                new DocumentReindexer.ReindexResult.OperationError("embedding", "chunk-5", "Timeout");

            String str = error.toString();

            assertThat(str).contains("embedding");
            assertThat(str).contains("chunk-5");
            assertThat(str).contains("Timeout");
        }
    }
}
