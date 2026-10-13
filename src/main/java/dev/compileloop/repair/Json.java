package dev.compileloop.repair;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict JSON (RFC 8259) for untrusted input: no comments, no trailing commas,
 * no duplicate keys, no unescaped control characters, no lone surrogates, no
 * content after the value, and a nesting limit. Objects become LinkedHashMap,
 * arrays ArrayList, numbers Long or Double.
 */
final class Json {

    static final class ParseException extends Exception {
        ParseException(String message) {
            super(message);
        }
    }

    private final String s;
    private final int maxDepth;
    private int pos;

    private Json(String s, int maxDepth) {
        this.s = s;
        this.maxDepth = maxDepth;
    }

    static Object parse(String text, int maxDepth) throws ParseException {
        Json j = new Json(text, maxDepth);
        j.skipWs();
        Object v = j.value(0);
        j.skipWs();
        if (j.pos != text.length()) {
            throw j.err("unexpected content after the JSON value");
        }
        return v;
    }

    /** JSON string literal with quotes, escaping everything that must be escaped. */
    static String quote(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private Object value(int depth) throws ParseException {
        if (depth > maxDepth) {
            throw err("nesting too deep");
        }
        if (pos >= s.length()) {
            throw err("unexpected end of input");
        }
        char c = s.charAt(pos);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                literal("true");
                return Boolean.TRUE;
            case 'f':
                literal("false");
                return Boolean.FALSE;
            case 'n':
                literal("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw err("unexpected character '" + printable(c) + "'");
        }
    }

    private Map<String, Object> object(int depth) throws ParseException {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            if (peek() != '"') {
                throw err("expected a string key");
            }
            String key = string();
            skipWs();
            expect(':');
            skipWs();
            Object v = value(depth + 1);
            if (map.containsKey(key)) {
                throw err("duplicate key \"" + key + "\"");
            }
            map.put(key, v);
            skipWs();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                pos--;
                throw err("expected ',' or '}'");
            }
        }
    }

    private List<Object> array(int depth) throws ParseException {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(value(depth + 1));
            skipWs();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                pos--;
                throw err("expected ',' or ']'");
            }
        }
    }

    private String string() throws ParseException {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw err("unterminated string");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c < 0x20) {
                pos--;
                throw err("unescaped control character in string");
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= s.length()) {
                throw err("unterminated escape");
            }
            char e = s.charAt(pos++);
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
                    if (pos + 4 > s.length()) {
                        throw err("truncated \\u escape");
                    }
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        int d = Character.digit(s.charAt(pos + i), 16);
                        if (d < 0) {
                            throw err("invalid \\u escape");
                        }
                        code = code * 16 + d;
                    }
                    pos += 4;
                    sb.append((char) code);
                }
                default -> {
                    pos--;
                    throw err("invalid escape '\\" + printable(e) + "'");
                }
            }
        }
        String result = sb.toString();
        if (hasLoneSurrogate(result)) {
            throw err("string contains an unpaired UTF-16 surrogate");
        }
        return result;
    }

    private Number number() throws ParseException {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        if (peek() == '0') {
            pos++;
        } else if (peek() >= '1' && peek() <= '9') {
            while (peek() >= '0' && peek() <= '9') {
                pos++;
            }
        } else {
            throw err("invalid number");
        }
        boolean integer = true;
        if (peek() == '.') {
            integer = false;
            pos++;
            if (!(peek() >= '0' && peek() <= '9')) {
                throw err("invalid number");
            }
            while (peek() >= '0' && peek() <= '9') {
                pos++;
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            integer = false;
            pos++;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            if (!(peek() >= '0' && peek() <= '9')) {
                throw err("invalid number");
            }
            while (peek() >= '0' && peek() <= '9') {
                pos++;
            }
        }
        String text = s.substring(start, pos);
        if (integer) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                // too large for long: fall through to double
            }
        }
        return Double.parseDouble(text);
    }

    private void literal(String word) throws ParseException {
        if (!s.startsWith(word, pos)) {
            throw err("unexpected token");
        }
        pos += word.length();
    }

    private void expect(char c) throws ParseException {
        if (peek() != c) {
            throw err("expected '" + c + "'");
        }
        pos++;
    }

    private char peek() {
        return pos < s.length() ? s.charAt(pos) : '\0';
    }

    private char next() throws ParseException {
        if (pos >= s.length()) {
            throw err("unexpected end of input");
        }
        return s.charAt(pos++);
    }

    private void skipWs() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private ParseException err(String message) {
        return new ParseException(message + " at position " + pos);
    }

    private static String printable(char c) {
        return c < 0x20 || c == 0x7f ? String.format("\\u%04x", (int) c) : String.valueOf(c);
    }

    static boolean hasLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }
}
