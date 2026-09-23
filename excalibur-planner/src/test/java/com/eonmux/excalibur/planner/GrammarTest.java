package com.eonmux.excalibur.planner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The grammatical shapes as a request spells them.
 */
@DisplayName("Grammar")
class GrammarTest {

    @Test
    @DisplayName("every shape round-trips through its wire name")
    void wireNamesRoundTrip() {
        for (Grammar grammar : Grammar.values()) {
            assertThat(Grammar.parse(grammar.wireName())).isEqualTo(grammar);
        }
        assertThat(Grammar.SINGULAR_PERSON.wireName()).isEqualTo("singular_person");
    }

    @Test
    @DisplayName("case and surrounding space are not part of the name")
    void parsingIsLenientAboutCase() {
        assertThat(Grammar.parse(" Singular_Thing ")).isEqualTo(Grammar.SINGULAR_THING);
        assertThat(Grammar.parse("PLURAL")).isEqualTo(Grammar.PLURAL);
    }

    @Test
    @DisplayName("anything else is refused, naming what would have been accepted")
    void nonsenseIsRefused() {
        // Refused rather than read as unknown: a caller that said "person" and was treated as
        // having said nothing gets the ambiguity it sent the shape to avoid.
        for (String bad : new String[] {"person", "", "singular person"}) {
            assertThatThrownBy(() -> Grammar.parse(bad))
                .as("%s", bad)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("singular_person")
                .hasMessageContaining("unknown");
        }
        assertThatThrownBy(() -> Grammar.parse(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
