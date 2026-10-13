package dev.compileloop.repair;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

/**
 * Minimal pretty-printing JSON writer for reports. Handles maps, collections,
 * strings, numbers, booleans, enums, paths and null. Strings are escaped by the
 * same routine the provider code uses, so control characters in model-written
 * text can never reach a report unescaped.
 */
final class JsonWriter {

    private JsonWriter() {
    }

    static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int level) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            sb.append(Json.quote(s));
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Enum<?> e) {
            sb.append(Json.quote(e.name()));
        } else if (v instanceof Path p) {
            sb.append(Json.quote(p.toString().replace('\\', '/')));
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(sb, level + 1);
                sb.append(Json.quote(String.valueOf(e.getKey()))).append(": ");
                write(sb, e.getValue(), level + 1);
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            pad(sb, level);
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            if (c.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            int i = 0;
            for (Object o : c) {
                pad(sb, level + 1);
                write(sb, o, level + 1);
                sb.append(++i < c.size() ? ",\n" : "\n");
            }
            pad(sb, level);
            sb.append(']');
        } else {
            sb.append(Json.quote(String.valueOf(v)));
        }
    }

    private static void pad(StringBuilder sb, int level) {
        sb.append("  ".repeat(level));
    }
}
