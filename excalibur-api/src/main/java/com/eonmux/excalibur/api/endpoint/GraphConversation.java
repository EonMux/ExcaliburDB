package com.eonmux.excalibur.api.endpoint;

import com.eonmux.excalibur.api.protocol.JsonText;
import com.eonmux.excalibur.planner.ConversationContext;
import com.eonmux.excalibur.planner.Grammar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The conversation a graph question is asked in, rebuilt from the request that asked it.
 *
 * <p>The client owns the history and the server holds no session. That keeps
 * {@code POST /v1/graph/query} stateless and horizontally scalable, and it means a client can
 * replay or edit the context it is being answered against - which is the only way to correct a
 * mis-bound pronoun.</p>
 *
 * <h2>Two spellings of {@code context_entities}</h2>
 * <p>An array of names, each of unknown shape and all said in the one turn before the question:</p>
 * <pre>{@code "context_entities": ["Ada Lovelace", "Analytical Engine"]}</pre>
 * <p>Or an array of objects, each a name with what the client knows about it:</p>
 * <pre>{@code
 * "context_entities": [
 *   {"name": "Charles Babbage", "grammar": "singular_person", "turn": 0},
 *   {"name": "Ada Lovelace",    "grammar": "singular_person", "turn": 1},
 *   {"name": "Analytical Engine", "grammar": "singular_thing", "turn": 1}
 * ]
 * }</pre>
 *
 * <p>The object form is what makes coreference work. {@code grammar} is the mention's shape, in
 * {@link Grammar}'s vocabulary, and it is what stops "it" binding a person and "she" binding an
 * engine. {@code turn} numbers the turn the entity was mentioned in, higher being more recent;
 * between two mentions a pronoun could bind, the one from the later turn wins, and a mention
 * older than the {@linkplain ConversationContext#DEFAULT_WINDOW_TURNS window} is not bound to at
 * all. The question itself is the turn after the latest one given. With only names, every mention
 * is of unknown shape and in the same turn, so two of them leave any pronoun ambiguous - which is
 * what that spelling always meant, and still does.</p>
 *
 * <p>Both fields are optional. An entry without {@code grammar} is of unknown shape and binds any
 * pronoun. An entry without {@code turn} belongs to the latest turn given, which is where a name
 * sent without one has always been placed.</p>
 *
 * <p>The two spellings are not mixed in one array. A client that has a shape or a turn for some
 * entities sends the others as objects with only a {@code name}.</p>
 *
 * <h2>Refused, not repaired</h2>
 * <p>A shape that is not one of the four, a turn that is not a whole number, and an entry with no
 * name are refused with the entry's position rather than read as absent. The caller sent them to
 * rule a binding in or out, and a request answered as though they had not been sent binds the
 * pronoun they were meant to settle - with nothing in the response to say so.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless.</p>
 */
final class GraphConversation {

    /** The request field holding the conversation. */
    static final String FIELD = "context_entities";

    /**
     * Most conversation entities accepted in one request.
     *
     * <p>Every mention is a candidate each pronoun in the question is compared against, and a name
     * that can appear in the disambiguation question the endpoint answers with - so an unbounded
     * array is both unbounded work and an unbounded response. The binding window is three turns of
     * a conversation; a hundred and twenty-eight entities inside it is already far more than a
     * person mentions.</p>
     */
    static final int MAX_ENTITIES = 128;

    /** Longest single conversation entity accepted, in characters. Longer than this is not a name. */
    static final int MAX_ENTITY_LENGTH = 256;

    /**
     * Highest turn number accepted.
     *
     * <p>One below the largest {@code int}, because the question is asked in the turn after the
     * latest one given and that turn has to be a number too.</p>
     */
    static final int MAX_TURN = Integer.MAX_VALUE - 1;

    private static final String NAME = "name";
    private static final String GRAMMAR = "grammar";
    private static final String TURN = "turn";

    private GraphConversation() {
        throw new AssertionError("Utility class");
    }

    /**
     * One entity the client says was mentioned.
     *
     * @param name    The name, as mentioned
     * @param grammar Its shape, {@link Grammar#UNKNOWN} when the client did not say
     * @param turn    The turn it was mentioned in, or null when the client did not say
     */
    private record Entry(String name, Grammar grammar, Integer turn) {
    }

    /**
     * Reads the conversation out of a request body.
     *
     * @param body The request body
     * @return The conversation, empty when the request carried none
     * @throws IllegalArgumentException if {@code context_entities} is present and malformed, or
     *                                  beyond a bound; the message names the entry and the bound
     */
    static ConversationContext from(String body) {
        List<Entry> entries = entriesOf(body);
        ConversationContext context = new ConversationContext();
        if (entries == null) {
            return context;
        }

        int latest = 0;
        for (Entry entry : entries) {
            if (entry.turn() != null) {
                latest = Math.max(latest, entry.turn());
            }
        }
        int latestTurn = latest;
        List<Entry> ordered = new ArrayList<>(entries);
        // Stable, so entries of one turn keep the order they were sent in; nothing reads that
        // order, because within a turn there is no evidence of which mention came last.
        ordered.sort(Comparator.comparingInt(entry -> turnOf(entry, latestTurn)));
        for (Entry entry : ordered) {
            context.advanceTo(turnOf(entry, latestTurn)).mention(entry.name(), entry.grammar());
        }
        // The question is the turn after the conversation it follows.
        return context.advanceTo(latestTurn).nextTurn();
    }

    /** The turn an entry is placed in: its own, or the latest turn given when it named none. */
    private static int turnOf(Entry entry, int latestTurn) {
        return entry.turn() != null ? entry.turn() : latestTurn;
    }

    /**
     * Reads the entries, in either spelling.
     *
     * @param body The request body
     * @return The entries, or null when the field is absent
     * @throws IllegalArgumentException if the field is malformed or beyond a bound
     */
    private static List<Entry> entriesOf(String body) {
        String[] names;
        try {
            names = JsonText.stringArray(body, FIELD);
        } catch (IllegalArgumentException notNames) {
            return objectEntriesOf(body);
        }
        if (names == null) {
            return null;
        }
        requireWithinBound(names.length);
        List<Entry> entries = new ArrayList<>(names.length);
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            requireNameLength(name, i);
            // A blank name mentions nothing, and a pronoun cannot bind it.
            if (name != null && !name.isBlank()) {
                entries.add(new Entry(name, Grammar.UNKNOWN, null));
            }
        }
        return entries;
    }

    /**
     * Reads the entries of the object spelling, which is what the field has to be when it is
     * not an array of names.
     *
     * @param body The request body
     * @return The entries
     * @throws IllegalArgumentException if the field is not an array of objects either, or an
     *                                  entry or the count is not acceptable
     */
    private static List<Entry> objectEntriesOf(String body) {
        String[] objects;
        try {
            objects = JsonText.objectArray(body, FIELD);
        } catch (IllegalArgumentException notObjects) {
            throw new IllegalArgumentException("'" + FIELD + "' must be an array of entity "
                + "names, or an array of objects each with a '" + NAME + "' and optionally a '"
                + GRAMMAR + "' and a '" + TURN + "' - not a mixture of the two", notObjects);
        }
        requireWithinBound(objects.length);
        List<Entry> entries = new ArrayList<>(objects.length);
        for (int i = 0; i < objects.length; i++) {
            entries.add(entryOf(objects[i], i));
        }
        return entries;
    }

    /**
     * Reads one entry of the object spelling.
     *
     * @param object The entry's raw JSON
     * @param index  Its position, for the message
     * @return The entry
     * @throws IllegalArgumentException if its name, shape or turn is not acceptable
     */
    private static Entry entryOf(String object, int index) {
        String where = "'" + FIELD + "' entry " + index + ": ";
        try {
            String name = JsonText.string(object, NAME);
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("'" + NAME + "' is required and must not be "
                    + "blank");
            }
            if (name.length() > MAX_ENTITY_LENGTH) {
                throw new IllegalArgumentException("'" + NAME + "' is " + name.length()
                    + " characters; at most " + MAX_ENTITY_LENGTH + " are accepted");
            }

            String shape = JsonText.string(object, GRAMMAR);
            Grammar grammar = shape == null ? Grammar.UNKNOWN : Grammar.parse(shape);

            Integer turn = null;
            if (JsonText.has(object, TURN)) {
                long value = JsonText.longValue(object, TURN, 0L);
                if (value < 0 || value > MAX_TURN) {
                    throw new IllegalArgumentException("'" + TURN + "' must be a whole number "
                        + "from 0 to " + MAX_TURN + ", got " + value);
                }
                turn = (int) value;
            }
            return new Entry(name, grammar, turn);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where + e.getMessage(), e);
        }
    }

    /**
     * Refuses more entries than the bound.
     *
     * <p>Refused rather than truncated: dropping half a context silently changes which entity a
     * pronoun binds to, and the caller would read the answer as though it had been given the whole
     * conversation.</p>
     *
     * @param count How many entries were sent
     * @throws IllegalArgumentException if there are more than {@link #MAX_ENTITIES}
     */
    private static void requireWithinBound(int count) {
        if (count > MAX_ENTITIES) {
            throw new IllegalArgumentException("'" + FIELD + "' holds " + count + " entries; at "
                + "most " + MAX_ENTITIES + " are accepted");
        }
    }

    /**
     * Refuses a name longer than the bound.
     *
     * @param name  The name, possibly null
     * @param index Its position, for the message
     * @throws IllegalArgumentException if it is longer than {@link #MAX_ENTITY_LENGTH}
     */
    private static void requireNameLength(String name, int index) {
        if (name != null && name.length() > MAX_ENTITY_LENGTH) {
            throw new IllegalArgumentException("'" + FIELD + "' entry " + index + " is "
                + name.length() + " characters; at most " + MAX_ENTITY_LENGTH + " are accepted");
        }
    }
}
