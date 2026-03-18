package excalibur.api.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A minimal JSON serialization/deserialization utility.
 * No external dependencies - uses only standard Java libraries.
 * <p>
 * Thread-safety: This class is thread-safe for use across concurrent requests.
 */
public class Json {
    
    /**
     * Escapes a string for JSON output.
     * 
     * @param str The string to escape
     * @return The escaped string
     */
    public static String escape(String str) {
        if (str == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : str.toCharArray()) {
            sb.append(switch (c) {
                case '"' -> "\\\"";
                case '\\' -> "\\\\";
                case '\b' -> "\\b";
                case '\f' -> "\\f";
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                default -> c < 0x20 ? String.format("\\u%04x", (int) c) : c;
            });
        }
        return sb.toString();
    }
    
    /**
     * Gets the HTTP method name.
     * 
     * @param method The HTTP method
     * @return The HTTP method name as a string
     */
    public static String name(HttpMethod method) {
        return method.name();
    }
    
    /**
     * Creates a JSON string from a primitive boolean.
     * 
     * @param value The boolean value
     * @return JSON "true" or "false"
     */
    public static String booleanValue(boolean value) {
        return value ? "true" : "false";
    }
    
    /**
     * Creates a JSON string from a primitive int.
     * 
     * @param value The integer value
     * @return JSON number string
     */
    public static String intValue(int value) {
        return String.valueOf(value);
    }
    
    /**
     * Creates a JSON string from a primitive long.
     * 
     * @param value The long value
     * @return JSON number string
     */
    public static String longValue(long value) {
        return String.valueOf(value);
    }
    
    /**
     * Creates a JSON string from a primitive float.
     * 
     * @param value The float value
     * @return JSON number string
     */
    public static String floatValue(float value) {
        return String.valueOf(value);
    }
    
    /**
     * Creates a JSON string from a primitive double.
     * 
     * @param value The double value
     * @return JSON number string
     */
    public static String doubleValue(double value) {
        return String.valueOf(value);
    }
    
    /**
     * Creates a JSON string from a String.
     * 
     * @param value The string value
     * @return JSON quoted string
     */
    public static String stringValue(String value) {
        return "\"" + escape(value) + "\"";
    }
    
    /**
     * Creates a JSON object with key-value pairs.
     * 
     * @param map The map of key-value pairs
     * @return JSON object string
     */
    public static String objectValue(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(escape(entry.getKey()));
            sb.append(":");
            sb.append(value(entry.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }
    
    /**
     * Creates a JSON array from a list of values.
     * 
     * @param list The list of values
     * @return JSON array string
     */
    public static String arrayValue(List<Object> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(value(item));
        }
        sb.append("]");
        return sb.toString();
    }
    
    /**
     * Serializes any Java object to JSON.
     * 
     * @param value The value to serialize
     * @return JSON string
     */
    public static String value(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean b) {
            return booleanValue(b);
        }
        if (value instanceof Integer i) {
            return intValue(i);
        }
        if (value instanceof Long l) {
            return longValue(l);
        }
        if (value instanceof Float f) {
            return floatValue(f);
        }
        if (value instanceof Double d) {
            return doubleValue(d);
        }
        if (value instanceof String s) {
            return stringValue(s);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> stringMap = new HashMap<>();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                if (entry.getKey() instanceof String) {
                    stringMap.put(entry.getKey().toString(), entry.getValue());
                } else {
                    stringMap.put("" + entry.getKey(), entry.getValue());
                }
            }
            return objectValue(stringMap);
        }
        if (value instanceof List<?> l) {
            List<Object> resultList = new ArrayList<>();
            for (Object item : l) {
                resultList.add(value(item));
            }
            return arrayValue(resultList);
        }
        if (value instanceof Json) {
            return ((Json) value).toString();
        }
        throw new IllegalArgumentException("Cannot serialize " + value.getClass().getName() + " to JSON");
    }
    
    /**
     * Parses a JSON string into a Map<String, Object>.
     * 
     * @param json The JSON string
     * @return Map representation of the JSON
     * @throws IOException if the JSON is invalid
     */
    public static Map<String, Object> parse(String json) throws IOException {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        return parse(new StringReader(json));
    }
    
    /**
     * Parses a JSON string from an InputStream into a Map<String, Object>.
     * 
     * @param inputStream The input stream containing JSON
     * @return Map representation of the JSON
     * @throws IOException if reading or parsing fails
     */
    public static Map<String, Object> parse(InputStream inputStream) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            sb.append(buffer, 0, bytesRead);
        }
        return parse(sb.toString());
    }
    
    /**
     * Parses a JSON string into a Map<String, Object>.
     * 
     * @param reader The JSON string reader
     * @return Map representation of the JSON
     * @throws IOException if the JSON is invalid
     */
    public static Map<String, Object> parse(StringReader reader) throws IOException {
        char[] buffer = new char[8192];
        int pos = 0;
        int len = reader.read(buffer);
        if (len == -1) {
            throw new IOException("Empty input");
        }
        int end = pos + len;
        
        while (pos < end) {
            char c = buffer[pos++];
            if (c == '\n') continue;
            if (c == '\r') {
                if (pos < end && buffer[pos] == '\n') pos++;
                continue;
            }
            if (c == ' ') continue;
            if (c == '\t') continue;
            break;
        }
        
        if (pos >= end || buffer[pos - 1] != '{') {
            throw new IOException("Expected '{' at start");
        }
        pos++;
        
        Map<String, Object> result = new HashMap<>();
        
        while (pos < end) {
            char c = buffer[pos++];
            if (c == '}') {
                pos--;
                break;
            }
            if (c != '"') {
                throw new IOException("Expected '"' at start of key");
            }
            
            StringBuilder key = new StringBuilder();
            while (pos < end && buffer[pos] != '"') {
                char ch = buffer[pos++];
                if (ch == '\\') {
                    if (pos >= end) break;
                    char escaped = buffer[pos++];
                    switch (escaped) {
                        case '"' -> key.append('"');
                        case '\\' -> key.append('\\');
                        case '/' -> key.append('/');
                        case 'b' -> key.append('\b');
                        case 'f' -> key.append('\f');
                        case 'n' -> key.append('\n');
                        case 'r' -> key.append('\r');
                        case 't' -> key.append('\t');
                        case 'u' -> {
                            if (pos + 4 >= end) throw new IOException("Invalid unicode escape");
                            String hex = new String(buffer, pos, 4);
                            pos += 4;
                            int codePoint = Integer.parseInt(hex, 16);
                            key.append(Character.charAt(codePoint));
                            break;
                        }
                        default -> throw new IOException("Invalid escape sequence");
                    }
                } else {
                    key.append(ch);
                }
            }
            if (pos >= end) {
                throw new IOException("Unexpected end of input");
            }
            pos++;
            
            if (pos >= end || buffer[pos] != ':') {
                throw new IOException("Expected ':'");
            }
            pos++;
            
            char firstChar = buffer[pos];
            pos++;
            Object value;
            
            if (firstChar == '"') {
                StringBuilder str = new StringBuilder();
                while (pos < end && buffer[pos] != '"') {
                    char ch = buffer[pos++];
                    if (ch == '\\') {
                        if (pos >= end) break;
                        char escaped = buffer[pos++];
                        switch (escaped) {
                            case '"' -> str.append('"');
                            case '\\' -> str.append('\\');
                            case '/' -> str.append('/');
                            case 'b' -> str.append('\b');
                            case 'f' -> str.append('\f');
                            case 'n' -> str.append('\n');
                            case 'r' -> str.append('\r');
                            case 't' -> str.append('\t');
                            case 'u' -> {
                                if (pos + 4 >= end) throw new IOException("Invalid unicode escape");
                                String hex = new String(buffer, pos, 4);
                                pos += 4;
                                int codePoint = Integer.parseInt(hex, 16);
                                str.append(Character.charAt(codePoint));
                                break;
                            }
                            default -> throw new IOException("Invalid escape sequence");
                        }
                    } else {
                        str.append(ch);
                    }
                }
                if (pos >= end) throw new IOException("Unexpected end of input");
                pos++;
                value = str.toString();
            } else if (firstChar == '{') {
                value = parse(reader);
            } else if (firstChar == '[') {
                value = parseArray(reader);
            } else if (firstChar == '"') {
                value = str(buffer, pos, end);
            } else if (firstChar == '-' || Character.isDigit(firstChar)) {
                value = parseNumber(reader);
            } else if (firstChar == 't' || firstChar == 'f') {
                value = parseBoolean(reader);
            } else if (firstChar == 'n') {
                value = null;
            } else {
                throw new IOException("Unexpected character: " + firstChar);
            }
            
            result.put(key.toString(), value);
        }
        
        return result;
    }
    
    /**
     * Parses a JSON array into a List<Object>.
     * 
     * @param reader The JSON reader
     * @return List representation of the JSON array
     * @throws IOException if parsing fails
     */
    private static List<Object> parseArray(StringReader reader) throws IOException {
        char[] buffer = new char[8192];
        int pos = 0;
        int len = reader.read(buffer);
        if (len == -1) {
            throw new IOException("Empty array");
        }
        int end = pos + len;
        
        if (buffer[pos] != '[') {
            throw new IOException("Expected '['");
        }
        pos++;
        
        List<Object> result = new ArrayList<>();
        
        while (pos < end) {
            char c = buffer[pos++];
            if (c == ']') {
                return result;
            }
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ' ') continue;
            
            Object value;
            if (c == '"') {
                StringBuilder str = new StringBuilder();
                pos++;
                while (pos < end && buffer[pos] != '"') {
                    char ch = buffer[pos++];
                    if (ch == '\\') {
                        if (pos >= end) break;
                        char escaped = buffer[pos++];
                        switch (escaped) {
                            case '"' -> str.append('"');
                            case '\\' -> str.append('\\');
                            case '/' -> str.append('/');
                            case 'b' -> str.append('\b');
                            case 'f' -> str.append('\f');
                            case 'n' -> str.append('\n');
                            case 'r' -> str.append('\r');
                            case 't' -> str.append('\t');
                            case 'u' -> {
                                if (pos + 4 >= end) throw new IOException("Invalid unicode escape");
                                String hex = new String(buffer, pos, 4);
                                pos += 4;
                                int codePoint = Integer.parseInt(hex, 16);
                                str.append(Character.charAt(codePoint));
                                break;
                            }
                            default -> throw new IOException("Invalid escape sequence");
                        }
                    } else {
                        str.append(ch);
                    }
                }
                if (pos >= end) throw new IOException("Unexpected end of input");
                pos++;
                value = str.toString();
            } else if (c == '{') {
                value = parse(reader);
            } else if (c == '[') {
                value = parseArray(reader);
            } else if (c == '"') {
                value = str(buffer, pos, end);
            } else if (c == '-' || Character.isDigit(c)) {
                value = parseNumber(reader);
            } else if (c == 't' || c == 'f') {
                value = parseBoolean(reader);
            } else if (c == 'n') {
                value = null;
            } else {
                throw new IOException("Unexpected character: " + c);
            }
            result.add(value);
        }
        
        throw new IOException("Unexpected end of input");
    }
    
    /**
     * Parses a JSON number from the buffer.
     * 
     * @param buffer The buffer to parse from
     * @param pos Current position
     * @param end End of buffer
     * @return The parsed number as String
     */
    private static String str(char[] buffer, int pos, int end) {
        StringBuilder sb = new StringBuilder();
        pos++;
        while (pos < end && buffer[pos] != '"') {
            char c = buffer[pos++];
            if (c == '\\') {
                if (pos >= end) break;
                char escaped = buffer[pos++];
                switch (escaped) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 >= end) throw new IOException("Invalid unicode escape");
                        String hex = new String(buffer, pos, 4);
                        pos += 4;
                        int codePoint = Integer.parseInt(hex, 16);
                        sb.append(Character.charAt(codePoint));
                        break;
                    }
                    default -> throw new IOException("Invalid escape sequence");
                }
            } else {
                sb.append(c);
            }
        }
        if (pos >= end) throw new IOException("Unexpected end of input");
        pos++;
        return sb.toString();
    }
    
    /**
     * Parses a JSON number from the reader.
     * 
     * @param reader The JSON reader
     * @return The parsed number as String
     * @throws IOException if parsing fails
     */
    private static String parseNumber(StringReader reader) throws IOException {
        char[] buffer = new char[8192];
        int pos = 0;
        int len = reader.read(buffer);
        if (len == -1) throw new IOException("Empty input");
        int end = pos + len;
        
        char first = buffer[pos++];
        if (first == '-') {
            if (pos >= end) throw new IOException("Expected digit");
            pos++;
        }
        
        StringBuilder sb = new StringBuilder();
        while (pos < end && Character.isDigit(buffer[pos])) {
            sb.append(buffer[pos++]);
        }
        
        if (pos < end && buffer[pos] == '.') {
            sb.append('.');
            pos++;
            while (pos < end && Character.isDigit(buffer[pos])) {
                sb.append(buffer[pos++]);
            }
        }
        
        if (pos < end && (buffer[pos] == 'e' || buffer[pos] == 'E')) {
            sb.append(buffer[pos++]);
            if (pos < end && (buffer[pos] == '+' || buffer[pos] == '-')) {
                sb.append(buffer[pos++]);
            }
            while (pos < end && Character.isDigit(buffer[pos])) {
                sb.append(buffer[pos++]);
            }
        }
        
        return sb.toString();
    }
    
    /**
     * Parses a JSON boolean from the reader.
     * 
     * @param reader The JSON reader
     * @return true for "true", false for "false"
     * @throws IOException if parsing fails
     */
    private static Object parseBoolean(StringReader reader) throws IOException {
        char[] buffer = new char[8192];
        int pos = 0;
        int len = reader.read(buffer);
        if (len == -1) throw new IOException("Empty input");
        int end = pos + len;
        
        if (buffer[pos] == 't') {
            if (pos + 4 > end || buffer[pos + 1] != 'r' || buffer[pos + 2] != 'u' || buffer[pos + 3] != 'e') {
                throw new IOException("Invalid boolean");
            }
            pos += 4;
            return true;
        } else if (buffer[pos] == 'f') {
            if (pos + 5 > end || buffer[pos + 1] != 'a' || buffer[pos + 2] != 'l' || buffer[pos + 3] != 's' || buffer[pos + 4] != 'e') {
                throw new IOException("Invalid boolean");
            }
            pos += 5;
            return false;
        } else {
            throw new IOException("Invalid boolean");
        }
    }
    
    /**
     * Parses a JSON string from the reader.
     * 
     * @param reader The JSON reader
     * @return The parsed string
     * @throws IOException if parsing fails
     */
    private static String parse(StringReader reader) throws IOException {
        char[] buffer = new char[8192];
        int pos = 0;
        int len = reader.read(buffer);
        if (len == -1) throw new IOException("Empty input");
        int end = pos + len;
        
        if (buffer[pos] != '"') {
            throw new IOException("Expected '"'");
        }
        pos++;
        
        StringBuilder sb = new StringBuilder();
        while (pos < end && buffer[pos] != '"') {
            char c = buffer[pos++];
            if (c == '\\') {
                if (pos >= end) break;
                char escaped = buffer[pos++];
                switch (escaped) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 >= end) throw new IOException("Invalid unicode escape");
                        String hex = new String(buffer, pos, 4);
                        pos += 4;
                        int codePoint = Integer.parseInt(hex, 16);
                        sb.append(Character.charAt(codePoint));
                        break;
                    }
                    default -> throw new IOException("Invalid escape sequence");
                }
            } else {
                sb.append(c);
            }
        }
        if (pos >= end) throw new IOException("Unexpected end of input");
        pos++;
        return sb.toString();
    }
    
    /**
     * Writes a JSON object to an output stream.
     * 
     * @param outputStream The output stream
     * @param data The object to serialize
     * @throws IOException if writing fails
     */
    public static void write(OutputStream outputStream, Map<String, Object> data) throws IOException {
        write(outputStream, data);
    }
    
    /**
     * Writes a JSON object to a writer.
     * 
     * @param writer The writer
     * @param data The object to serialize
     * @throws IOException if writing fails
     */
    public static void write(Writer writer, Map<String, Object> data) throws IOException {
        if (data == null || data.isEmpty()) {
            writer.write("{}\n");
            return;
        }
        writer.write("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!first) {
                writer.write(", ");
            }
            first = false;
            writer.write(escape(entry.getKey()));
            writer.write(":");
            writer.write(value(entry.getValue()));
        }
        writer.write("}\n");
    }
    
    /**
     * Reads a JSON object from an input stream.
     * 
     * @param inputStream The input stream
     * @return The parsed map
     * @throws IOException if reading fails
     */
    public static Map<String, Object> read(InputStream inputStream) throws IOException {
        return parse(inputStream);
    }
    
    /**
     * Reads a JSON array from an input stream.
     * 
     * @param inputStream The input stream
     * @return The parsed array
     * @throws IOException if reading fails
     */
    public static List<Object> readArray(InputStream inputStream) throws IOException {
        Map<String, Object> map = parse(inputStream);
        if (map.containsKey("result") && map.get("result") instanceof List) {
            return (List<Object>) map.get("result");
        }
        if (map.containsKey("data") && map.get("data") instanceof List) {
            return (List<Object>) map.get("data");
        }
        throw new IOException("JSON does not contain array at root or 'result' or 'data' key");
    }
    
    @Override
    public String toString() {
        return value(this);
    }
    
    @FunctionalInterface
    public interface StringReader {
        int read(char[] buffer);
    }
}
