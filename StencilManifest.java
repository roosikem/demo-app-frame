package com.demo.ejb.faceframe;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Checks the {@code stencils.json} delivered in the stencil zip against the images in the same zip.
 * <p>
 * The file is the master list: every image is listed exactly once, every entry has its image, ids are unique. Only
 * the known fields are passed on to the browser, in the order of the file. Format:
 * <pre>
 * { "version": 1, "stencils": [
 *   { "id": "img-star-ring", "file": "Celebration/star-ring.png", "label": "Star ring",
 *     "category": "Celebration", "tags": ["stars"] } ] }
 * </pre>
 * Read with JSON-B into plain maps and lists, not into classes: that keeps the JSON types (text, number, true/false),
 * so a wrong type is reported instead of being converted (JSON-B would turn {@code 1.5} into a version 1).
 */
final class StencilManifest {

    static final String FILE_NAME = "stencils.json";
    static final String CONTENT_TYPE = "application/json";

    private static final int VERSION = 1;
    /**
     * Same characters as the editor accepts; the {@code img-} prefix keeps them apart from the built-in stencils
     * (the editor ignores a stencil whose id is already taken).
     */
    private static final Pattern SAFE_ID = Pattern.compile("img-[A-Za-z0-9][\\w-]{0,59}");
    /**
     * Control characters, line/paragraph separators, text direction controls, byte order mark and HTML brackets.
     * Other invisible characters stay allowed: zero width (non-)joiner and soft hyphen are needed for emoji
     * sequences and for some languages.
     */
    private static final Pattern UNSAFE_CHARS =
            Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF<>]");
    private static final Pattern EDGE_SPACE = Pattern.compile("(?:^[\\p{Z}\\s]+)|(?:[\\p{Z}\\s]+$)");
    /** The format is two levels deep; this also keeps a hostile file away from the parser's recursion. */
    private static final int MAX_NESTING = 8;
    // the editor cuts longer texts (counted in UTF-16 units, like String.length()), so they are rejected here
    private static final int MAX_LABEL = 40;
    private static final int MAX_CATEGORY = 30;
    private static final int MAX_TAGS = 10;
    private static final int MAX_TAG = 30;

    private StencilManifest() {
    }

    /**
     * @param manifest   {@code stencils.json} from the zip, or {@code null} when the zip has none
     * @param imagePaths paths of the validated images, e.g. {@code Celebration/confetti.png}
     * @return the checked manifest as served to the browser
     */
    static byte[] check(byte[] manifest, Set<String> imagePaths) throws StencilException {
        if (manifest == null) {
            throw new StencilException(FILE_NAME + " is missing in the zip");
        }
        try (Jsonb jsonb = JsonbBuilder.create()) {
            List<?> entries = stencilsOf(parse(jsonb, manifest));

            List<Map<String, Object>> stencils = new ArrayList<>(entries.size());
            Set<String> ids = new HashSet<>();
            Set<String> listedFiles = new HashSet<>();
            for (int i = 0; i < entries.size(); i++) {
                String where = FILE_NAME + ", stencil " + (i + 1);
                if (!(entries.get(i) instanceof Map<?, ?> entry)) {
                    throw new StencilException(where + ": must be an object");
                }
                String id = id(entry, where);
                if (!ids.add(id)) {
                    throw new StencilException(where + ": id " + id + " is used twice");
                }
                String file = file(entry, where, imagePaths);
                if (!listedFiles.add(file)) {
                    throw new StencilException(where + ": file " + file + " is listed twice");
                }
                stencils.add(stencil(entry, where, id, file));
            }

            Set<String> unlisted = new TreeSet<>(imagePaths);
            unlisted.removeAll(listedFiles);
            if (!unlisted.isEmpty()) {
                throw new StencilException(FILE_NAME + ": no entry for "
                        + StencilException.printable(String.join(", ", unlisted)));
            }
            // LinkedHashMap: the fields are written in this order
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("version", VERSION);
            result.put("stencils", stencils);
            return jsonb.toJson(result).getBytes(StandardCharsets.UTF_8);
        } catch (StencilException e) {
            throw e;
        } catch (Exception e) {
            // JsonbException on broken input; Jsonb.close() declares Exception
            throw new StencilException(FILE_NAME + ": not a valid JSON object ("
                    + StencilException.printable(String.valueOf(e.getMessage())) + ")", e);
        }
    }

    private static Map<?, ?> parse(Jsonb jsonb, byte[] manifest) throws StencilException {
        requireShallowSingleObject(manifest);
        Object root = jsonb.fromJson(new ByteArrayInputStream(manifest), Object.class);
        if (!(root instanceof Map<?, ?> object)) {
            throw new StencilException(FILE_NAME + ": not a valid JSON object");
        }
        return object;
    }

    /**
     * Before the parser sees the file: counts brackets outside of strings, and requires that nothing but white
     * space follows the first value (JSON-B would ignore text after the end).
     */
    private static void requireShallowSingleObject(byte[] manifest) throws StencilException {
        int depth = 0;
        boolean inString = false;
        boolean ended = false;
        for (int i = 0; i < manifest.length; i++) {
            byte b = manifest[i];
            if (inString) {
                if (b == '\\') {
                    i++; // skip the escaped character
                } else if (b == '"') {
                    inString = false;
                }
            } else if (ended && !isWhitespace(b)) {
                throw new StencilException(FILE_NAME + ": not a valid JSON object (text after the end)");
            } else if (b == '"') {
                inString = true;
            } else if (b == '{' || b == '[') {
                if (++depth > MAX_NESTING) {
                    throw new StencilException(FILE_NAME + ": nested more than " + MAX_NESTING + " levels");
                }
            } else if (b == '}' || b == ']') {
                ended = --depth == 0;
            }
        }
    }

    private static boolean isWhitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }

    private static List<?> stencilsOf(Map<?, ?> root) throws StencilException {
        Object version = root.get("version");
        if (version != null && !(version instanceof BigDecimal number && isVersion(number))) {
            throw new StencilException(FILE_NAME + ": version must be " + VERSION);
        }
        if (!(root.get("stencils") instanceof List<?> stencils)) {
            throw new StencilException(FILE_NAME + ": \"stencils\" list is missing");
        }
        return stencils;
    }

    private static boolean isVersion(BigDecimal number) {
        try {
            return number.intValueExact() == VERSION;
        } catch (ArithmeticException notAnInt) {
            return false;
        }
    }

    private static String id(Map<?, ?> entry, String where) throws StencilException {
        String id = requiredString(entry, "id", where);
        if (!SAFE_ID.matcher(id).matches()) {
            throw new StencilException(where + ": id " + StencilException.printable(id)
                    + " must start with img- and contain only A-Z, a-z, 0-9, _ and - (max. 64 characters)");
        }
        return id;
    }

    private static String file(Map<?, ?> entry, String where, Set<String> imagePaths) throws StencilException {
        String file = requiredString(entry, "file", where);
        if (!imagePaths.contains(file)) {
            throw new StencilException(where + ": file " + StencilException.printable(file)
                    + " is not in the zip (names are case-sensitive)");
        }
        return file;
    }

    private static Map<String, Object> stencil(Map<?, ?> entry, String where, String id, String file)
            throws StencilException {
        Map<String, Object> stencil = new LinkedHashMap<>();
        stencil.put("id", id);
        stencil.put("file", file);
        stencil.put("label", text(requiredString(entry, "label", where), MAX_LABEL, where + ", label"));
        stencil.put("category", text(requiredString(entry, "category", where), MAX_CATEGORY, where + ", category"));
        List<String> tags = tags(entry, where);
        if (!tags.isEmpty()) {
            stencil.put("tags", tags);
        }
        return stencil;
    }

    private static List<String> tags(Map<?, ?> entry, String where) throws StencilException {
        if (!entry.containsKey("tags")) {
            return List.of();
        }
        if (!(entry.get("tags") instanceof List<?> list) || list.size() > MAX_TAGS) {
            throw new StencilException(where + ": tags must be a list of at most " + MAX_TAGS + " texts");
        }
        List<String> tags = new ArrayList<>(list.size());
        for (Object tag : list) {
            if (!(tag instanceof String text)) {
                throw new StencilException(where + ": tags must be texts");
            }
            tags.add(text(text, MAX_TAG, where + ", tag"));
        }
        return tags;
    }

    private static String requiredString(Map<?, ?> entry, String key, String where) throws StencilException {
        if (!(entry.get(key) instanceof String value)) {
            throw new StencilException(where + ": \"" + key + "\" is missing or not a text");
        }
        return value;
    }

    private static String text(String value, int maxLength, String where) throws StencilException {
        // like JavaScript's trim(): also no-break and ideographic spaces, which String.strip() keeps
        String trimmed = EDGE_SPACE.matcher(value).replaceAll("");
        if (trimmed.isEmpty()) {
            throw new StencilException(where + " is empty");
        }
        if (UNSAFE_CHARS.matcher(trimmed).find()) {
            throw new StencilException(where + " contains control characters, text direction marks or < >: "
                    + StencilException.printable(trimmed));
        }
        if (trimmed.length() > maxLength) {
            throw new StencilException(where + " is longer than " + maxLength + " characters: "
                    + StencilException.printable(trimmed));
        }
        return trimmed;
    }
}
