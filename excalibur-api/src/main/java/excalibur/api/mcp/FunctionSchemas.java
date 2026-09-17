package excalibur.api.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The same tool catalogue, in the shape each function-calling API actually accepts.
 *
 * <h2>Why three shapes and not one</h2>
 * <p>They describe the same thing and disagree about how to spell it. MCP puts a tool's schema
 * under {@code inputSchema}; Anthropic requires {@code input_schema} and treats the camel-cased
 * one as an unknown field beside a missing required one; OpenAI wraps each tool as
 * {@code {"type": "function", "function": {...}}} and reads the schema from {@code parameters}.
 * Each of the three is a 400 at the other two.</p>
 *
 * <p>{@code --print-schema} wrote the MCP shape and the README described it as the form the
 * OpenAI and Anthropic APIs take, which is the one thing it was not. The flag exists so that a
 * caller not speaking MCP does not have to write the schemas by hand, and a file that has to be
 * rewritten before it can be sent does not do that.</p>
 *
 * <h2>What is not translated</h2>
 * <p>The descriptions. They are the interface a model reads, they were written once against
 * what the endpoints actually do, and a per-API rewording would be three descriptions to keep
 * true instead of one.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; every call builds the catalogue afresh.</p>
 */
public final class FunctionSchemas {

    /** The MCP wire shape, as {@code tools/list} returns it. */
    public static final String MCP = "mcp";

    /** The shape the Anthropic Messages API takes in its {@code tools} array. */
    public static final String ANTHROPIC = "anthropic";

    /** The shape the OpenAI Chat Completions API takes in its {@code tools} array. */
    public static final String OPENAI = "openai";

    /** Every shape this class can emit, in the order {@link #allShapes()} writes them. */
    public static final List<String> SHAPES = List.of(MCP, ANTHROPIC, OPENAI);

    private FunctionSchemas() {
        throw new AssertionError("Utility class");
    }

    /**
     * The catalogue as the Anthropic Messages API takes it.
     *
     * @return One object per tool, each with {@code name}, {@code description} and
     *         {@code input_schema}
     */
    public static List<Object> anthropic() {
        List<Object> tools = new ArrayList<>();
        for (Object described : McpTools.catalogue()) {
            Map<String, Object> tool = Json.object();
            tool.put("name", Json.stringAt(described, "name"));
            tool.put("description", Json.stringAt(described, "description"));
            tool.put("input_schema", Json.objectAt(described, "inputSchema"));
            tools.add(tool);
        }
        return tools;
    }

    /**
     * The catalogue as the OpenAI Chat Completions API takes it.
     *
     * @return One object per tool, each a {@code function} wrapper whose {@code parameters}
     *         carry the schema
     */
    public static List<Object> openAi() {
        List<Object> tools = new ArrayList<>();
        for (Object described : McpTools.catalogue()) {
            Map<String, Object> function = Json.object();
            function.put("name", Json.stringAt(described, "name"));
            function.put("description", Json.stringAt(described, "description"));
            function.put("parameters", Json.objectAt(described, "inputSchema"));

            Map<String, Object> tool = Json.object();
            tool.put("type", "function");
            tool.put("function", function);
            tools.add(tool);
        }
        return tools;
    }

    /**
     * One named shape, written as the JSON a caller sends.
     *
     * @param shape One of {@link #SHAPES}, in any case
     * @return {@code {"tools": [...]}} in that shape
     * @throws IllegalArgumentException if the shape is not one this class knows
     */
    public static String shaped(String shape) {
        String normalised = shape == null ? "" : shape.trim().toLowerCase(Locale.ROOT);
        return Json.write(Map.of("tools", switch (normalised) {
            case MCP -> McpTools.catalogue();
            case ANTHROPIC -> anthropic();
            case OPENAI -> openAi();
            // Named rather than defaulted: a caller that asked for a shape and silently got a
            // different one discovers it as a 400 from an API that did not write this file.
            default -> throw new IllegalArgumentException("Unknown schema shape: " + shape
                + ". Use one of " + String.join(", ", SHAPES) + ".");
        }));
    }

    /**
     * Every shape at once, each under its own key.
     *
     * <p>What {@code --print-schema} writes when the caller does not name one. Self-describing
     * rather than defaulted to whichever shape came first: the three are not interchangeable,
     * and a caller who has not said which it wants is better served by a document that shows
     * all three than by one that silently picks.</p>
     *
     * @return {@code {"mcp": [...], "anthropic": [...], "openai": [...]}}
     */
    public static String allShapes() {
        Map<String, Object> document = Json.object();
        document.put(MCP, McpTools.catalogue());
        document.put(ANTHROPIC, anthropic());
        document.put(OPENAI, openAi());
        return Json.write(document);
    }
}
