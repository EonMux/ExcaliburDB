/*
 * excalibur-test - Query Result Verification Service
 *
 * Verifies the document-identifier contract of QueryResultItem, which the
 * comparison engines rely on when they sort and match result items.
 */

package com.eonmux.excalibur.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("QueryResultItem document ID contract")
class QueryResultItemContractTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("rejects a null document ID")
        void rejectsNullDocumentId() {
            assertThatThrownBy(() -> new QueryResultItem(null, 0.9f))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("documentId");
        }

        @Test
        @DisplayName("rejects a blank document ID")
        void rejectsBlankDocumentId() {
            assertThatThrownBy(() -> new QueryResultItem("   ", 0.9f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("accepts a populated document ID")
        void acceptsPopulatedDocumentId() {
            QueryResultItem item = new QueryResultItem("doc-1", 0.9f, Map.of("segmentId", 3L));

            assertThat(item.getDocumentId()).isEqualTo("doc-1");
            assertThat(item.getMetadata()).containsEntry("segmentId", 3L);
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderContract {

        @Test
        @DisplayName("build() without a document ID fails instead of producing a null ID")
        void buildWithoutDocumentIdFails() {
            QueryResultItemBuilder builder = QueryResultItemBuilder.builder().score(0.9f);

            assertThatThrownBy(builder::build)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("documentId");
        }

        @Test
        @DisplayName("build() with a document ID produces a sortable item")
        void buildWithDocumentIdProducesSortableItem() {
            QueryResultItem item = QueryResultItemBuilder.builder()
                .documentId("doc-1")
                .score(0.9f)
                .build();

            assertThat(item.getDocumentId()).isEqualTo("doc-1");
        }
    }

    @Nested
    @DisplayName("Sorting")
    class Sorting {

        @Test
        @DisplayName("items are sortable by document ID without a null-tolerant comparator")
        void itemsAreSortableByDocumentId() {
            List<QueryResultItem> items = new java.util.ArrayList<>(List.of(
                new QueryResultItem("doc-2", 0.5f),
                new QueryResultItem("doc-1", 0.9f)
            ));

            items.sort(Comparator.comparing(QueryResultItem::getDocumentId));

            assertThat(items).extracting(QueryResultItem::getDocumentId)
                .containsExactly("doc-1", "doc-2");
        }

        @Test
        @DisplayName("comparing results built by the builder does not throw")
        void comparingBuilderResultsDoesNotThrow() {
            List<QueryResultItem> actual = List.of(
                QueryResultItemBuilder.builder().documentId("doc-1").score(0.9f).build());
            List<QueryResultItem> expected = List.of(
                QueryResultItemBuilder.builder().documentId("doc-1").score(0.9f).build());

            assertThat(new ResultComparator(1e-6).compareResults(actual, expected)).isTrue();
        }
    }
}
