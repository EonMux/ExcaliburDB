package com.eonmux.excalibur.api.mcp;

import com.eonmux.excalibur.planner.Grammar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code context_entities} argument of {@code search_graph}: its schema, and how a model's
 * value becomes the body field {@code POST /v1/graph/query} reads.
 *
 * <p>The node takes either an array of names or an array of objects that also carry each
 * entity's grammatical shape and the turn it was mentioned in, and not a mixture of the two. A
 * model is allowed the mixture: it has the names of some entities and the shape of others, and
 * making it rewrite the plain ones as objects is a failed first call for no gain. So an all-name
 * array is forwarded as it was sent, and any array with an object in it is forwarded with every
 * element as an object - a plain name becoming one with only a {@code name}, which the node
 * reads exactly as it reads the plain name.</p>
 *
 * <p>Everything the node would refuse is refused here first, as a tool error naming the element,
 * and so is a field the node does not read. The node ignores a field it does not know, so a
 * misspelt {@code "gramar"} would reach it as an entity of unknown shape - the ambiguity the
 * model sent the shape to rule out, with nothing to say why.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless.</p>
 */
final class ContextEntities {

    /** The argument, and the body field it is forwarded as. */
    static final String FIELD = "context_entities";

    private static final String NAME = "name";
    private static final String GRAMMAR = "grammar";
    private static final String TURN = "turn";
    private static final Set<String> FIELDS = Set.of(NAME, GRAMMAR, TURN);

    private ContextEntities() {
        throw new AssertionError("Utility class");
    }

    /**
     * The argument's schema: an array whose elements are each a name or an object.
     *
     * @return The property
     */
    static ToolSchema.Property property() {
        Map<String, Object> entity = ToolSchema.object(
            ToolSchema.required(NAME, "string", "The entity's name, as it was mentioned."),
            ToolSchema.optionalChoice(GRAMMAR,
                "Its grammatical shape, which decides which pronouns can refer to it: "
                + "'singular_person' for he/she/him/her, 'singular_thing' for it/this/that, "
                + "'plural' for they/them/these, 'unknown' for any. Omit for unknown.",
                grammarNames()),
            ToolSchema.optionalInteger(TURN,
                "The conversation turn it was mentioned in, counting up from 0; higher is more "
                + "recent. When two entities fit a pronoun, the one from the later turn wins, "
                + "and one more than two turns before the question is forgotten. Omit to place "
                + "it in the latest turn.", 0));
        return ToolSchema.optionalArray(FIELD,
            "The entities the conversation so far has mentioned, so a pronoun in the query can be "
            + "resolved - normally the candidates a previous 'needs_input' answer returned, or "
            + "the entities earlier turns were about. Give a name alone when that is all you "
            + "know; give an object with a grammar and a turn when you know them, because "
            + "names alone are all in one turn and of unknown shape, so two of them leave any "
            + "pronoun ambiguous.",
            ToolSchema.anyOf(ToolSchema.scalar("string", "An entity name."), entity));
    }

    /**
     * Reads the argument into the value the node's body field takes.
     *
     * @param args The call's arguments
     * @return A list of names, a list of objects, or null when there is nothing to send
     * @throws IllegalArgumentException if an element is neither a name nor an acceptable object
     */
    static List<Object> read(Map<String, Object> args) {
        List<Object> elements = ToolArguments.objectList(args, FIELD);
        List<Object> names = new ArrayList<>(elements.size());
        List<Object> entities = new ArrayList<>(elements.size());
        boolean anyObject = false;
        for (int i = 0; i < elements.size(); i++) {
            Object element = elements.get(i);
            if (element instanceof String text) {
                if (!text.isBlank()) {
                    names.add(text.trim());
                    Map<String, Object> entity = Json.object();
                    entity.put(NAME, text.trim());
                    entities.add(entity);
                }
            } else if (element instanceof Map<?, ?> map) {
                anyObject = true;
                entities.add(entity(map, i));
            } else {
                throw new IllegalArgumentException(where(i) + "must be a name or an object with a "
                    + "'name', got: " + element);
            }
        }
        List<Object> forwarded = anyObject ? entities : names;
        return forwarded.isEmpty() ? null : List.copyOf(forwarded);
    }

    /**
     * Reads one object element into the object the node reads.
     *
     * @param map   The element
     * @param index Its position, for the message
     * @return The object to forward
     * @throws IllegalArgumentException if it has no name, a field the node does not read, or a
     *                                  shape or turn the node would refuse
     */
    private static Map<String, Object> entity(Map<?, ?> map, int index) {
        for (Object key : map.keySet()) {
            if (!FIELDS.contains(key)) {
                throw new IllegalArgumentException(where(index) + "has a field '" + key + "'; "
                    + "an entity has only '" + NAME + "', '" + GRAMMAR + "' and '" + TURN + "'");
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) map;
        try {
            Map<String, Object> entity = Json.object();
            entity.put(NAME, ToolArguments.requiredString(fields, NAME));
            String grammar = ToolArguments.optionalString(fields, GRAMMAR);
            if (grammar != null) {
                entity.put(GRAMMAR, Grammar.parse(grammar).wireName());
            }
            Integer turn = ToolArguments.wholeNumber(fields, TURN);
            if (turn != null) {
                if (turn < 0 || turn == Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Argument '" + TURN + "' must be a whole "
                        + "number from 0 to " + (Integer.MAX_VALUE - 1) + ", got: " + turn);
                }
                entity.put(TURN, turn);
            }
            return entity;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(where(index) + e.getMessage(), e);
        }
    }

    private static String where(int index) {
        return "Argument '" + FIELD + "' element " + index + ": ";
    }

    /** The shapes, as a request spells them. */
    private static List<String> grammarNames() {
        List<String> names = new ArrayList<>();
        for (Grammar grammar : Grammar.values()) {
            names.add(grammar.wireName());
        }
        return names;
    }
}
