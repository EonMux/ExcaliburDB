package com.eonmux.excalibur.api.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the descriptor a model reads before deciding whether to call a tool.
 *
 * <p>Separate from {@link McpTools} because the two answer different questions. That class is
 * the catalogue - which tools exist, what each one says about itself, and what each one sends
 * to the node. This one is the shape those answers are written in, and it is the same shape for
 * every tool. Keeping them together meant a file where adding a tool and changing the wire
 * format looked like the same kind of edit.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless; every call returns fresh maps.</p>
 */
final class ToolSchema {

    private ToolSchema() {
        throw new AssertionError("Utility class");
    }

    /**
     * One property of a tool's input schema, and whether it has to be supplied.
     *
     * @param name     The field name the model sends
     * @param schema   Its JSON Schema fragment
     * @param required Whether a call without it is invalid
     */
    record Property(String name, Map<String, Object> schema, boolean required) {
    }

    /**
     * Assembles one tool descriptor in the MCP wire shape.
     *
     * @param name        The tool name
     * @param description What the model reads before calling it
     * @param inputSchema Its arguments
     * @return The descriptor
     */
    static Map<String, Object> tool(String name, String description,
                                    Map<String, Object> inputSchema) {
        Map<String, Object> tool = Json.object();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", inputSchema);
        return tool;
    }

    /**
     * A property the model must supply.
     *
     * @param name        The field name
     * @param type        Its JSON type
     * @param description What it is for
     * @return The property
     */
    static Property required(String name, String type, String description) {
        return new Property(name, propertySchema(type, description, null), true);
    }

    /**
     * A property the model may supply.
     *
     * @param name        The field name
     * @param type        Its JSON type
     * @param description What it is for
     * @param itemType    The element type, for an array, or null
     * @return The property
     */
    static Property optional(String name, String type, String description, String itemType) {
        return new Property(name, propertySchema(type, description, itemType), false);
    }

    /**
     * A string property the model may supply, restricted to a fixed set of values.
     *
     * <p>Listed in the schema rather than only in the description, so a client that validates
     * arguments against the schema catches a misspelt value before the call is made.</p>
     *
     * @param name        The field name
     * @param description What it is for
     * @param values      The values accepted, in the order a model should read them
     * @return The property
     */
    static Property optionalChoice(String name, String description, List<String> values) {
        Map<String, Object> schema = propertySchema("string", description, null);
        schema.put("enum", new ArrayList<Object>(values));
        return new Property(name, schema, false);
    }

    /**
     * A whole-number property the model may supply, with the least value it may take.
     *
     * @param name        The field name
     * @param description What it is for
     * @param minimum     The least value accepted
     * @return The property
     */
    static Property optionalInteger(String name, String description, int minimum) {
        Map<String, Object> schema = propertySchema("integer", description, null);
        schema.put("minimum", minimum);
        return new Property(name, schema, false);
    }

    /**
     * An array property the model may supply, whose elements are described by a schema of their
     * own rather than by a bare type.
     *
     * @param name        The field name
     * @param description What the array is for
     * @param items       The schema one element must satisfy
     * @return The property
     */
    static Property optionalArray(String name, String description, Map<String, Object> items) {
        Map<String, Object> schema = Json.object();
        schema.put("type", "array");
        schema.put("description", description);
        schema.put("items", items);
        return new Property(name, schema, false);
    }

    /**
     * A schema satisfied by a value of any one of several shapes.
     *
     * @param alternatives The shapes, in the order a model should read them
     * @return The schema
     */
    @SafeVarargs
    static Map<String, Object> anyOf(Map<String, Object>... alternatives) {
        Map<String, Object> schema = Json.object();
        schema.put("anyOf", new ArrayList<Object>(List.of(alternatives)));
        return schema;
    }

    /**
     * The schema of a bare value of one JSON type.
     *
     * @param type        The JSON type
     * @param description What the value is
     * @return The schema
     */
    static Map<String, Object> scalar(String type, String description) {
        return propertySchema(type, description, null);
    }

    /**
     * An array-of-objects property, with the shape of one element spelled out.
     *
     * <p>A model given a bare {@code array} guesses the field names of what goes in it, and the
     * guess is wrong often enough that the call fails on its first attempt.</p>
     *
     * @param name           The field name
     * @param description    What the array is for
     * @param itemProperties The properties of one element
     * @return The property
     */
    static Property requiredObjectArray(String name, String description,
                                        Property... itemProperties) {
        Map<String, Object> schema = Json.object();
        schema.put("type", "array");
        schema.put("description", description);
        schema.put("items", object(itemProperties));
        return new Property(name, schema, true);
    }

    /**
     * Assembles an object schema out of its properties.
     *
     * @param properties The properties, in the order a model should read them
     * @return The schema
     */
    static Map<String, Object> object(Property... properties) {
        Map<String, Object> fields = Json.object();
        List<Object> requiredNames = new ArrayList<>();
        for (Property property : properties) {
            fields.put(property.name(), property.schema());
            if (property.required()) {
                requiredNames.add(property.name());
            }
        }

        Map<String, Object> schema = Json.object();
        schema.put("type", "object");
        schema.put("properties", fields);
        schema.put("required", requiredNames);
        return schema;
    }

    private static Map<String, Object> propertySchema(String type, String description,
                                                      String itemType) {
        Map<String, Object> schema = Json.object();
        schema.put("type", type);
        schema.put("description", description);
        if (itemType != null) {
            Map<String, Object> items = Json.object();
            items.put("type", itemType);
            schema.put("items", items);
        }
        return schema;
    }
}
