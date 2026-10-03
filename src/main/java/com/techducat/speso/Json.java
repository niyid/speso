package com.techducat.speso;

import java.util.*;

/**
 * Minimal JSON, enough for the RPC and the feed parsers (no dependencies).
 * parse(): objects -> LinkedHashMap, arrays -> ArrayList, integers -> Long, other numbers -> Double.
 * write(): Map, Iterable, long[], String, Number, Boolean, null.
 * The parser is strict, depth-limited, and throws IllegalArgumentException on anything malformed.
 */
final class Json {
    private Json() {}
    private static final int MAX_DEPTH = 32;

    // ------------------------------------------------------------------ writing

    static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        w(sb, o);
        return sb.toString();
    }

    private static void w(StringBuilder sb, Object o) {
        if (o == null) sb.append("null");
        else if (o instanceof String s) str(sb, s);
        else if (o instanceof Boolean || o instanceof Long || o instanceof Integer) sb.append(o);
        else if (o instanceof Double d) sb.append(d.isNaN() || d.isInfinite() ? "null" : d.toString());
        else if (o instanceof long[] a) {
            sb.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
            sb.append(']');
        } else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(sb, String.valueOf(e.getKey()));
                sb.append(':');
                w(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object x : it) { if (!first) sb.append(','); first = false; w(sb, x); }
            sb.append(']');
        } else str(sb, String.valueOf(o));
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }

    /** Ordered map builder: Json.obj("a", 1, "b", "x"). */
    static Map<String, Object> obj(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("odd arguments");
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ------------------------------------------------------------------ parsing

    static Object parse(String s) {
        Parser p = new Parser(s);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("trailing characters at " + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String s) {
        Object o = parse(s);
        if (!(o instanceof Map)) throw new IllegalArgumentException("expected a JSON object");
        return (Map<String, Object>) o;
    }

    private static final class Parser {
        final String s; int i = 0;
        Parser(String s) { this.s = s; }

        IllegalArgumentException err(String m) { return new IllegalArgumentException("bad JSON: " + m + " at " + i); }
        void ws() { while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++; }
        char peek() { if (i >= s.length()) throw err("unexpected end"); return s.charAt(i); }
        void expect(char c) { if (peek() != c) throw err("expected '" + c + "'"); i++; }

        Object value(int depth) {
            if (depth > MAX_DEPTH) throw err("too deep");
            char c = peek();
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object(int depth) {
            expect('{'); ws();
            Map<String, Object> m = new LinkedHashMap<>();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws(); expect(':'); ws();
                m.put(k, value(depth + 1));
                ws();
                if (peek() == ',') { i++; continue; }
                expect('}');
                return m;
            }
        }

        List<Object> array(int depth) {
            expect('['); ws();
            List<Object> l = new ArrayList<>();
            if (peek() == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                if (peek() == ',') { i++; continue; }
                expect(']');
                return l;
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek(); i++;
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = peek(); i++;
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw err("bad \\u escape");
                            try { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); }
                            catch (NumberFormatException ex) { throw err("bad \\u escape"); }
                            i += 4;
                        }
                        default -> throw err("bad escape");
                    }
                } else if (c < 0x20) throw err("control character in string");
                else sb.append(c);
            }
        }

        Object number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(st, i);
            if (t.isEmpty()) throw err("unexpected character");
            try {
                if (t.matches("-?\\d{1,18}")) return Long.parseLong(t);
                return Double.parseDouble(t);
            } catch (NumberFormatException e) { throw err("bad number"); }
        }
    }
}
