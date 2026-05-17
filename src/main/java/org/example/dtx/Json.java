package org.example.dtx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {
    private Json() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String input) {
        Object value = parse(input);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("JSON object expected: " + input);
        }
        return (Map<String, Object>) map;
    }

    static Object parse(String input) {
        Parser parser = new Parser(input);
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.isEnd()) {
            throw new IllegalArgumentException("Trailing JSON input at " + parser.index);
        }
        return value;
    }

    static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    static String pretty(Object value) {
        StringBuilder out = new StringBuilder();
        writePrettyValue(out, value, 0);
        return out.toString();
    }

    static String str(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static String str(Map<String, Object> object, String key, String defaultValue) {
        String value = str(object, key);
        return value == null ? defaultValue : value;
    }

    static long longValue(Map<String, Object> object, String key, long defaultValue) {
        Object value = object.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    static int intValue(Map<String, Object> object, String key, int defaultValue) {
        return Math.toIntExact(longValue(object, key, defaultValue));
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (value == null) {
            return List.of();
        }
        return (List<Object>) value;
    }

    static Map<String, Object> obj(Object... pairs) {
        Map<String, Object> object = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            object.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return object;
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            writeString(out, string);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                writeValue(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeValue(out, item);
            }
            out.append(']');
        } else {
            writeString(out, String.valueOf(value));
        }
    }

    private static void writePrettyValue(StringBuilder out, Object value, int indent) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            writeString(out, string);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            writePrettyMap(out, map, indent);
        } else if (value instanceof Iterable<?> iterable) {
            writePrettyArray(out, iterable, indent);
        } else {
            writeString(out, String.valueOf(value));
        }
    }

    private static void writePrettyMap(StringBuilder out, Map<?, ?> map, int indent) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{').append('\n');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',').append('\n');
            }
            first = false;
            appendIndent(out, indent + 2);
            writeString(out, String.valueOf(entry.getKey()));
            out.append(": ");
            writePrettyValue(out, entry.getValue(), indent + 2);
        }
        out.append('\n');
        appendIndent(out, indent);
        out.append('}');
    }

    private static void writePrettyArray(StringBuilder out, Iterable<?> iterable, int indent) {
        List<Object> values = new ArrayList<>();
        for (Object item : iterable) {
            values.add(item);
        }
        if (values.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[').append('\n');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',').append('\n');
            }
            appendIndent(out, indent + 2);
            writePrettyValue(out, values.get(i), indent + 2);
        }
        out.append('\n');
        appendIndent(out, indent);
        out.append(']');
    }

    private static void appendIndent(StringBuilder out, int indent) {
        out.append(" ".repeat(Math.max(0, indent)));
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input;
        }

        private boolean isEnd() {
            return index >= input.length();
        }

        private void skipWhitespace() {
            while (!isEnd() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        private Object readValue() {
            skipWhitespace();
            if (isEnd()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char c = input.charAt(index);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                expect('}');
                return object;
            }
            while (true) {
                String key = readString();
                skipWhitespace();
                expect(':');
                Object value = readValue();
                object.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    expect('}');
                    return object;
                }
                expect(',');
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> array = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                expect(']');
                return array;
            }
            while (true) {
                array.add(readValue());
                skipWhitespace();
                if (peek(']')) {
                    expect(']');
                    return array;
                }
                expect(',');
            }
        }

        private String readString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (!isEnd()) {
                char c = input.charAt(index++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (isEnd()) {
                    throw new IllegalArgumentException("Bad JSON escape");
                }
                char escaped = input.charAt(index++);
                switch (escaped) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (index + 4 > input.length()) {
                            throw new IllegalArgumentException("Bad unicode escape");
                        }
                        String hex = input.substring(index, index + 4);
                        out.append((char) Integer.parseInt(hex, 16));
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("Bad JSON escape: \\" + escaped);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private Object readLiteral(String literal, Object value) {
            if (!input.startsWith(literal, index)) {
                throw new IllegalArgumentException("Expected " + literal + " at " + index);
            }
            index += literal.length();
            return value;
        }

        private Number readNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            while (!isEnd() && Character.isDigit(input.charAt(index))) {
                index++;
            }
            boolean floating = false;
            if (!isEnd() && input.charAt(index) == '.') {
                floating = true;
                index++;
                while (!isEnd() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
            }
            if (!isEnd() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                floating = true;
                index++;
                if (!isEnd() && (input.charAt(index) == '+' || input.charAt(index) == '-')) {
                    index++;
                }
                while (!isEnd() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
            }
            String raw = input.substring(start, index);
            if (raw.isBlank() || raw.equals("-")) {
                throw new IllegalArgumentException("Bad JSON number at " + start);
            }
            return floating ? Double.parseDouble(raw) : Long.parseLong(raw);
        }

        private boolean peek(char expected) {
            return !isEnd() && input.charAt(index) == expected;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (isEnd() || input.charAt(index) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at " + index);
            }
            index++;
        }
    }
}
