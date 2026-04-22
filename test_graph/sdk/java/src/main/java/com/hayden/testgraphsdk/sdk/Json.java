package com.hayden.testgraphsdk.sdk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled JSON read/write used by the SDK to keep JBang scripts
 * dependency-free. Scope is intentionally narrow: flat string values,
 * ordered maps, and homogeneous lists — exactly what the Context[]
 * wire format needs.
 *
 * Swap for Jackson if the envelope ever grows beyond this shape.
 */
final class Json {

    private Json() {}

    static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
        return b.toString();
    }

    /** Minimal parser. Accepts: string, object<string,string>, array<object>, null. */
    static Object parse(String s) {
        return new Parser(s).readValue();
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> asStringMap(Object v) {
        if (v == null) return Map.of();
        Map<String, Object> m = (Map<String, Object>) v;
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : m.entrySet()) {
            out.put(e.getKey(), e.getValue() == null ? null : e.getValue().toString());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> asObjectList(Object v) {
        if (v == null) return List.of();
        return (List<Map<String, Object>>) v;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; this.i = 0; }

        Object readValue() {
            skipWs();
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end of json");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBool();
                case 'n' -> readNull();
                default -> readNumberOrRaw();
            };
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { i++; return out; }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                Object value = readValue();
                out.put(key, value);
                skipWs();
                char c = s.charAt(i++);
                if (c == '}') return out;
                if (c != ',') throw err("expected , or }");
            }
        }

        List<Object> readArray() {
            expect('[');
            List<Object> out = new ArrayList<>();
            skipWs();
            if (peek() == ']') { i++; return out; }
            while (true) {
                out.add(readValue());
                skipWs();
                char c = s.charAt(i++);
                if (c == ']') return out;
                if (c != ',') throw err("expected , or ]");
            }
        }

        String readString() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char n = s.charAt(i++);
                    switch (n) {
                        case '"', '\\', '/' -> b.append(n);
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'u' -> {
                            int cp = Integer.parseInt(s.substring(i, i + 4), 16);
                            b.append((char) cp);
                            i += 4;
                        }
                        default -> b.append(n);
                    }
                } else {
                    b.append(c);
                }
            }
            throw err("unterminated string");
        }

        Boolean readBool() {
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw err("expected boolean");
        }

        Object readNull() {
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw err("expected null");
        }

        /** Numbers are returned as String to avoid widening; caller can parse. */
        String readNumberOrRaw() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) break;
                i++;
            }
            return s.substring(start, i);
        }

        void expect(char c) {
            skipWs();
            if (i >= s.length() || s.charAt(i) != c) throw err("expected '" + c + "'");
            i++;
        }

        void skipWs() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        char peek() { skipWs(); return s.charAt(i); }

        IllegalArgumentException err(String msg) {
            return new IllegalArgumentException("json parse error at " + i + ": " + msg);
        }
    }
}
