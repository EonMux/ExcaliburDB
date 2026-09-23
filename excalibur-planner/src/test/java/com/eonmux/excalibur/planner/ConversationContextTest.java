package com.eonmux.excalibur.planner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A conversation rebuilt from what a client sent, turn by turn.
 *
 * <p>The server holds no session, so the turns a client numbers are the only record of which
 * entity was mentioned most recently - and recency is what binds a pronoun between two candidates
 * of the same shape, and what lets the window forget one mentioned too long ago.</p>
 */
@DisplayName("Conversation context")
class ConversationContextTest {

    @Test
    @DisplayName("a mention is recorded in the turn the context has been advanced to")
    void advancingPlacesMentionsInThatTurn() {
        ConversationContext context = new ConversationContext()
            .advanceTo(4).mention("Ada Lovelace", Grammar.SINGULAR_PERSON)
            .advanceTo(9).mention("Analytical Engine", Grammar.SINGULAR_THING);

        assertThat(context.currentTurn()).isEqualTo(9);
        assertThat(context.visibleMentions())
            .extracting(Mention::text, Mention::turn)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("Analytical Engine", 9));
    }

    @Test
    @DisplayName("a gap between turns counts against the window like the turns it skips")
    void gapsCountAgainstTheWindow() {
        // Turn 7 then turn 8: both inside a three-turn window that ends at the question's turn 9.
        ConversationContext near = new ConversationContext()
            .advanceTo(7).mention("Charles Babbage", Grammar.SINGULAR_PERSON)
            .advanceTo(8).mention("Ada Lovelace", Grammar.SINGULAR_PERSON)
            .advanceTo(9);
        assertThat(near.visibleMentions()).extracting(Mention::text)
            .containsExactly("Charles Babbage", "Ada Lovelace");

        // Turn 6 is three turns before the question and has left the window.
        ConversationContext far = new ConversationContext()
            .advanceTo(6).mention("Charles Babbage", Grammar.SINGULAR_PERSON)
            .advanceTo(8).mention("Ada Lovelace", Grammar.SINGULAR_PERSON)
            .advanceTo(9);
        assertThat(far.visibleMentions()).extracting(Mention::text)
            .containsExactly("Ada Lovelace");
    }

    @Test
    @DisplayName("advancing to the current turn is allowed; going back is refused")
    void turnsOnlyMoveForward() {
        ConversationContext context = new ConversationContext().advanceTo(3);

        assertThat(context.advanceTo(3).currentTurn()).isEqualTo(3);
        // A mention placed in a turn already left would be recorded as more recent than the
        // mentions after it, which is the one ordering a pronoun is bound by.
        assertThatThrownBy(() -> context.advanceTo(2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("2")
            .hasMessageContaining("3");
    }

    @Test
    @DisplayName("nextTurn and advanceTo describe the same clock")
    void nextTurnIsOneStep() {
        ConversationContext context = new ConversationContext().advanceTo(5).nextTurn();

        assertThat(context.currentTurn()).isEqualTo(6);
    }
}
