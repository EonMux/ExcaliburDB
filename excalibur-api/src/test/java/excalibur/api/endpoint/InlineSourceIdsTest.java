package excalibur.api.endpoint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link InlineSourceIds}.
 */
class InlineSourceIdsTest {

    private final InlineSourceIds ids = new InlineSourceIds(256);

    @Test
    @DisplayName("reports an id nothing was supplied under")
    void reportsAnUnknownId() {
        assertThat(ids.isSupplied("notes")).isFalse();
    }

    @Test
    @DisplayName("reports an id documents were supplied under")
    void reportsARememberedId() {
        ids.remember("notes");

        assertThat(ids.isSupplied("notes")).isTrue();
    }

    @Test
    @DisplayName("distinguishes ids rather than answering for any of them")
    void distinguishesIds() {
        ids.remember("notes");

        assertThat(ids.isSupplied("articles")).isFalse();
    }

    @Test
    @DisplayName("takes the same id twice without complaint")
    void isIdempotent() {
        ids.remember("notes");
        ids.remember("notes");

        assertThat(ids.isSupplied("notes")).isTrue();
    }

    @Test
    @DisplayName("refuses a new id past the bound rather than growing without limit")
    void refusesPastTheBound() {
        InlineSourceIds bounded = new InlineSourceIds(2);

        assertThat(bounded.remember("one")).isTrue();
        assertThat(bounded.remember("two")).isTrue();
        assertThat(bounded.remember("three")).isFalse();
        assertThat(bounded.isSupplied("three"))
            .as("a refused id must not be left behind, or it blocks a crawl it never held")
            .isFalse();
    }

    @Test
    @DisplayName("keeps taking batches for an id it already holds once full")
    void keepsTakingKnownIdsWhenFull() {
        InlineSourceIds bounded = new InlineSourceIds(1);
        bounded.remember("notes");

        assertThat(bounded.remember("notes"))
            .as("a second batch under a source the node already holds is not a new source")
            .isTrue();
        assertThat(bounded.remember("articles")).isFalse();
    }

    @Test
    @DisplayName("refuses a non-positive bound, which would hold nothing")
    void refusesANonPositiveBound() {
        assertThatThrownBy(() -> new InlineSourceIds(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses a null id rather than storing one")
    void refusesNulls() {
        assertThatThrownBy(() -> ids.remember(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ids.isSupplied(null)).isInstanceOf(NullPointerException.class);
    }
}
