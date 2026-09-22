package com.eonmux.excalibur.query.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Combining the segment catalog's fingerprint with a second index's version.
 *
 * <p>A lexical or fused answer reads two indexes, and the cache keeps one generation per node.
 * The combined fingerprint has to move when either index moves and stand still when neither
 * does, and an unreadable half must make the whole unreadable rather than quietly shrink to the
 * half that could be read.</p>
 */
@DisplayName("CorpusVersion.alongside")
class CorpusVersionTest {

    private static final CorpusVersion SEGMENTS = new CorpusVersion(3L, 0xABCDL, 2);

    @Test
    @DisplayName("is the same for the same two versions")
    void deterministic() {
        assertThat(SEGMENTS.alongside(7L)).isEqualTo(SEGMENTS.alongside(7L));
        assertThat(SEGMENTS.alongside(7L).readable()).isTrue();
    }

    @Test
    @DisplayName("moves when the second index moves")
    void movesWithTheSecondIndex() {
        assertThat(SEGMENTS.alongside(7L)).isNotEqualTo(SEGMENTS.alongside(8L));
    }

    @Test
    @DisplayName("moves when the segment catalog moves")
    void movesWithTheCatalog() {
        CorpusVersion moved = new CorpusVersion(3L, 0xABCEL, 2);

        assertThat(SEGMENTS.alongside(7L)).isNotEqualTo(moved.alongside(7L));
    }

    @Test
    @DisplayName("differs from the catalog's own fingerprint, so a vector answer and a fused "
        + "one never share a generation by accident")
    void differsFromTheCatalogAlone() {
        assertThat(SEGMENTS.alongside(0L)).isNotEqualTo(SEGMENTS);
    }

    @Test
    @DisplayName("stays unreadable when the catalog could not be read")
    void unreadableStaysUnreadable() {
        assertThat(CorpusVersion.UNREADABLE.alongside(7L)).isEqualTo(CorpusVersion.UNREADABLE);
        assertThat(CorpusVersion.UNREADABLE.alongside(7L).readable()).isFalse();
    }
}
