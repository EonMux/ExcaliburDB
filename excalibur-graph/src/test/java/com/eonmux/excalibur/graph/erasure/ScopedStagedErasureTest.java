package com.eonmux.excalibur.graph.erasure;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.provenance.SourceType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("Which staged records an erasure scope reaches")
class ScopedStagedErasureTest {

    private static final Provenance FROM_DOC_7 = new Provenance(SourceType.DOCUMENT, 7L, 90, 2);
    private static final Provenance FROM_DOC_8 = new Provenance(SourceType.DOCUMENT, 8L, 90, 3);

    @Test
    @DisplayName("a source scope reaches the facts and snippets of that source and no labels")
    void sourceScope() {
        ScopedStagedErasure erasure =
            ScopedStagedErasure.of(new ErasureScope.Source(SourceType.DOCUMENT, 7L));

        assertThat(erasure.erasesFact(1, 0, 2, 0, 0, FROM_DOC_7)).isTrue();
        assertThat(erasure.erasesFact(1, 0, 2, 0, 0, FROM_DOC_8)).isFalse();
        assertThat(erasure.erasesFact(1, 0, 2, 0, 0,
            new Provenance(SourceType.CONVERSATION, 7L, 90, 2)))
            .as("the same number from another kind of source is another source")
            .isFalse();
        assertThat(erasure.erasesSnippet(1, FROM_DOC_7)).isTrue();
        assertThat(erasure.erasesSnippet(1, Provenance.UNKNOWN)).isFalse();
        assertThat(erasure.erasesLabel(1))
            .as("withdrawing a document must not take the names it mentioned with it")
            .isFalse();
    }

    @Test
    @DisplayName("an entity scope reaches both endpoints, its snippets and its label")
    void entityScope() {
        ScopedStagedErasure erasure = ScopedStagedErasure.of(new ErasureScope.Entity(5));

        assertThat(erasure.erasesFact(5, 0, 2, 0, 0, FROM_DOC_7)).isTrue();
        assertThat(erasure.erasesFact(2, 0, 5, 0, 0, FROM_DOC_7)).isTrue();
        assertThat(erasure.erasesFact(2, 0, 3, 0, 0, FROM_DOC_7)).isFalse();
        assertThat(erasure.erasesSnippet(5, Provenance.UNKNOWN)).isTrue();
        assertThat(erasure.erasesLabel(5)).isTrue();
        assertThat(erasure.erasesLabel(6)).isFalse();
    }

    @Test
    @DisplayName("an extractor scope reaches what that extractor build produced")
    void extractorScope() {
        ScopedStagedErasure erasure = ScopedStagedErasure.of(new ErasureScope.ExtractorVersion(3));

        assertThat(erasure.erasesFact(1, 0, 2, 0, 0, FROM_DOC_8)).isTrue();
        assertThat(erasure.erasesFact(1, 0, 2, 0, 0, FROM_DOC_7)).isFalse();
    }

    @Test
    @DisplayName("several scopes erase what any one of them covers, and none erase nothing")
    void combinedScopes() {
        ScopedStagedErasure erasure = ScopedStagedErasure.of(List.of(
            new ErasureScope.Source(SourceType.DOCUMENT, 7L), new ErasureScope.Entity(9)));

        assertThat(erasure.erasesFact(1, 0, 2, 0, 0, FROM_DOC_7)).isTrue();
        assertThat(erasure.erasesFact(9, 0, 2, 0, 0, FROM_DOC_8)).isTrue();
        assertThat(erasure.erasesSnippet(9, FROM_DOC_8)).isTrue();
        assertThat(erasure.erasesLabel(9)).isTrue();

        ScopedStagedErasure none = ScopedStagedErasure.of(List.of());
        assertThat(none.erasesFact(1, 0, 2, 0, 0, FROM_DOC_7)).isFalse();
        assertThat(none.erasesSnippet(1, FROM_DOC_7)).isFalse();
        assertThat(none.erasesLabel(1)).isFalse();
    }

    @Test
    @DisplayName("a null scope is refused rather than matching nothing")
    void nullRefused() {
        assertThatNullPointerException().isThrownBy(() -> ScopedStagedErasure.of((ErasureScope) null));
        assertThatNullPointerException().isThrownBy(
            () -> ScopedStagedErasure.of((List<ErasureScope>) null));
    }
}
