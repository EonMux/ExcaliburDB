package com.eonmux.excalibur.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The entities mentioned so far, most recent last.
 *
 * <p>Everything a pronoun can bind to. Bounded, because a pronoun almost never reaches back more
 * than a couple of turns and an unbounded history would let it bind to something the user has long
 * since stopped talking about — a wrong binding that looks plausible is worse than no binding.
 */
public final class ConversationContext {

    /**
     * How many turns back a pronoun may reach.
     *
     * <p>Three is generous for ordinary dialogue. Beyond it, the most recent compatible mention is
     * more likely to be a coincidence than an antecedent.
     */
    public static final int DEFAULT_WINDOW_TURNS = 3;

    private final List<Mention> mentions = new ArrayList<>();
    private final int windowTurns;
    private int currentTurn;

    public ConversationContext() {
        this(DEFAULT_WINDOW_TURNS);
    }

    public ConversationContext(int windowTurns) {
        if (windowTurns < 1) {
            throw new IllegalArgumentException("windowTurns must be at least 1, got " + windowTurns);
        }
        this.windowTurns = windowTurns;
    }

    /** Records a mention in the current turn. */
    public ConversationContext mention(String text, Grammar grammar) {
        mentions.add(new Mention(text, grammar, currentTurn));
        return this;
    }

    /** Moves to the next turn. */
    public ConversationContext nextTurn() {
        currentTurn++;
        return this;
    }

    /**
     * Moves forward to a turn a caller numbered, so the mentions recorded next belong to it.
     *
     * <p>For a conversation rebuilt from a record rather than lived through: the caller knows
     * which turn each entity was mentioned in and replays them oldest first. Turns it skips are
     * turns in which nothing bindable was said, and they count against the window like any
     * other - an entity mentioned before a long run of such turns has been left behind.</p>
     *
     * <p>Only forward. A mention recorded after moving back would sit among later ones as though
     * it were the more recent, and recency is exactly what decides between two candidates of the
     * same shape.</p>
     *
     * @param turn the turn to move to; not earlier than the current one
     * @return this context
     * @throws IllegalArgumentException if {@code turn} is before the current turn
     */
    public ConversationContext advanceTo(int turn) {
        if (turn < currentTurn) {
            throw new IllegalArgumentException("cannot move back to turn " + turn
                + " from turn " + currentTurn + "; record mentions oldest turn first");
        }
        currentTurn = turn;
        return this;
    }

    /** The current turn number. */
    public int currentTurn() {
        return currentTurn;
    }

    /** Mentions still inside the binding window, oldest first. */
    public List<Mention> visibleMentions() {
        int oldestVisible = currentTurn - windowTurns + 1;
        List<Mention> visible = new ArrayList<>();
        for (Mention mention : mentions) {
            if (mention.turn() >= oldestVisible) {
                visible.add(mention);
            }
        }
        return Collections.unmodifiableList(visible);
    }

    /** Whether anything is available to bind to. */
    public boolean isEmpty() {
        return visibleMentions().isEmpty();
    }
}
