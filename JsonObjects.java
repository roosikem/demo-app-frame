package com.demo.ejb.faceframe;

import jakarta.json.bind.Jsonb;

import java.io.ByteArrayInputStream;
import java.util.Map;

/**
 * Reads a JSON file from the stencil zip into plain maps and lists with JSON-B. That keeps the JSON types (text,
 * number, true/false), so a wrong type can be reported instead of being converted.
 */
final class JsonObjects {

    /** The formats are at most three levels deep; this also keeps a hostile file away from the parser's recursion. */
    private static final int MAX_NESTING = 8;

    private JsonObjects() {
    }

    /**
     * @param fileName for the messages
     * @return the top-level object
     */
    static Map<?, ?> parse(Jsonb jsonb, byte[] json, String fileName) throws StencilException {
        requireShallowSingleObject(json, fileName);
        Object root = jsonb.fromJson(new ByteArrayInputStream(json), Object.class);
        if (!(root instanceof Map<?, ?> object)) {
            throw new StencilException(fileName + ": not a valid JSON object");
        }
        return object;
    }

    /**
     * Before the parser sees the file: counts brackets outside of strings, and requires that nothing but white
     * space follows the first value (JSON-B would ignore text after the end).
     */
    private static void requireShallowSingleObject(byte[] json, String fileName) throws StencilException {
        int depth = 0;
        boolean inString = false;
        boolean ended = false;
        for (int i = 0; i < json.length; i++) {
            byte b = json[i];
            if (inString) {
                if (b == '\\') {
                    i++; // skip the escaped character
                } else if (b == '"') {
                    inString = false;
                }
            } else if (ended && !isWhitespace(b)) {
                throw new StencilException(fileName + ": not a valid JSON object (text after the end)");
            } else if (b == '"') {
                inString = true;
            } else if (b == '{' || b == '[') {
                if (++depth > MAX_NESTING) {
                    throw new StencilException(fileName + ": nested more than " + MAX_NESTING + " levels");
                }
            } else if (b == '}' || b == ']') {
                ended = --depth == 0;
            }
        }
    }

    private static boolean isWhitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }
}
