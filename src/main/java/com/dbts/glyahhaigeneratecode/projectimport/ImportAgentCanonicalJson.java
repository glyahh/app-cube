package com.dbts.glyahhaigeneratecode.projectimport;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;

/** Java/Python 共用的规范 JSON；不接受浮点或不完整 Unicode。 */
public final class ImportAgentCanonicalJson {
    private ImportAgentCanonicalJson() { }

    public static byte[] bytes(JsonNode value) {
        var output = new StringBuilder();
        append(value, output, 0);
        byte[] result = output.toString().getBytes(StandardCharsets.UTF_8);
        if (result.length > 2 * 1024 * 1024) throw new IllegalArgumentException("权限对象超过大小限制。");
        return result;
    }

    public static String hash(JsonNode value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(value))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static void append(JsonNode value, StringBuilder output, int depth) {
        if (value == null || depth > 32) throw invalid();
        if (value.isObject()) {
            var keys = new ArrayList<String>();
            value.fieldNames().forEachRemaining(keys::add);
            keys.sort(ImportAgentCanonicalJson::compareCodePoints);
            output.append('{');
            for (int index = 0; index < keys.size(); index++) {
                if (index > 0) output.append(',');
                String key = keys.get(index);
                quote(key, output);
                output.append(':');
                append(value.get(key), output, depth + 1);
            }
            output.append('}');
        } else if (value.isArray()) {
            output.append('[');
            for (int index = 0; index < value.size(); index++) {
                if (index > 0) output.append(',');
                append(value.get(index), output, depth + 1);
            }
            output.append(']');
        } else if (value.isTextual()) quote(value.textValue(), output);
        else if (value.isBoolean()) output.append(value.booleanValue());
        else if (value.isNull()) output.append("null");
        else if (value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() >= 0 && value.longValue() <= 9_007_199_254_740_991L) output.append(value.longValue());
        else throw invalid();
        if (output.length() > 2 * 1024 * 1024) throw invalid();
    }

    private static void quote(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char item = value.charAt(index);
            if (Character.isHighSurrogate(item)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) throw invalid();
                output.append(item).append(value.charAt(index));
                continue;
            }
            if (Character.isLowSurrogate(item)) throw invalid();
            switch (item) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (item < 32) output.append("\\u00").append(Character.forDigit(item >> 4, 16))
                            .append(Character.forDigit(item & 15, 16));
                    else output.append(item);
                }
            }
        }
        output.append('"');
    }

    private static int compareCodePoints(String left, String right) {
        var first = left.codePoints().iterator();
        var second = right.codePoints().iterator();
        while (first.hasNext() && second.hasNext()) {
            int result = Integer.compare(first.nextInt(), second.nextInt());
            if (result != 0) return result;
        }
        return Boolean.compare(first.hasNext(), second.hasNext());
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("权限对象格式无效。"); }
}
