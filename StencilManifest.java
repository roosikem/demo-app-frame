package com.demo.ejb.faceframe;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonParser;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        JsonArray entries = stencilsOf(parse(manifest));

        JsonArrayBuilder stencils = Json.createArrayBuilder();
        Set<String> ids = new HashSet<>();
        Set<String> listedFiles = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            String where = FILE_NAME + ", stencil " + (i + 1);
            JsonObject entry = entries.get(i) instanceof JsonObject object ? object : null;
            if (entry == null) {
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
        return Json.createObjectBuilder()
                .add("version", VERSION)
                .add("stencils", stencils)
                .build()
                .toString()
                .getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject parse(byte[] manifest) throws StencilException {
        requireShallow(manifest);
        try (JsonParser parser = Json.createParser(new ByteArrayInputStream(manifest))) {
            if (!parser.hasNext() || parser.next() != JsonParser.Event.START_OBJECT) {
                throw new StencilException(FILE_NAME + ": not a valid JSON object");
            }
            JsonObject root = parser.getObject();
            if (parser.hasNext()) {
                throw new StencilException(FILE_NAME + ": not a valid JSON object (text after the end)");
            }
            return root;
        } catch (RuntimeException e) {
            // JsonException, and whatever else a JSON-P implementation throws on broken input
            throw new StencilException(FILE_NAME + ": not a valid JSON object ("
                    + StencilException.printable(String.valueOf(e.getMessage())) + ")", e);
        }
    }

    /** Counts brackets outside of strings, before the parser sees the file. */
    private static void requireShallow(byte[] manifest) throws StencilException {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < manifest.length; i++) {
            byte b = manifest[i];
            if (inString) {
                if (b == '\\') {
                    i++; // skip the escaped character
                } else if (b == '"') {
                    inString = false;
                }
            } else if (b == '"') {
                inString = true;
            } else if (b == '{' || b == '[') {
                if (++depth > MAX_NESTING) {
                    throw new StencilException(FILE_NAME + ": nested more than " + MAX_NESTING + " levels");
                }
            } else if (b == '}' || b == ']') {
                depth--;
            }
        }
    }

    private static JsonArray stencilsOf(JsonObject root) throws StencilException {
        JsonValue version = root.get("version");
        if (version != null && !(version instanceof JsonNumber number && isVersion(number))) {
            throw new StencilException(FILE_NAME + ": version must be " + VERSION);
        }
        if (!(root.get("stencils") instanceof JsonArray stencils)) {
            throw new StencilException(FILE_NAME + ": \"stencils\" list is missing");
        }
        return stencils;
    }

    private static boolean isVersion(JsonNumber number) {
        try {
            return number.intValueExact() == VERSION;
        } catch (ArithmeticException notAnInt) {
            return false;
        }
    }

    private static String id(JsonObject entry, String where) throws StencilException {
        String id = requiredString(entry, "id", where);
        if (!SAFE_ID.matcher(id).matches()) {
            throw new StencilException(where + ": id " + StencilException.printable(id)
                    + " must start with img- and contain only A-Z, a-z, 0-9, _ and - (max. 64 characters)");
        }
        return id;
    }

    private static String file(JsonObject entry, String where, Set<String> imagePaths) throws StencilException {
        String file = requiredString(entry, "file", where);
        if (!imagePaths.contains(file)) {
            throw new StencilException(where + ": file " + StencilException.printable(file)
                    + " is not in the zip (names are case-sensitive)");
        }
        return file;
    }

    private static JsonObject stencil(JsonObject entry, String where, String id, String file)
            throws StencilException {
        JsonObjectBuilder stencil = Json.createObjectBuilder()
                .add("id", id)
                .add("file", file)
                .add("label", text(requiredString(entry, "label", where), MAX_LABEL, where + ", label"))
                .add("category", text(requiredString(entry, "category", where), MAX_CATEGORY, where + ", category"));
        List<String> tags = tags(entry, where);
        if (!tags.isEmpty()) {
            stencil.add("tags", Json.createArrayBuilder(tags));
        }
        return stencil.build();
    }

    private static List<String> tags(JsonObject entry, String where) throws StencilException {
        JsonValue value = entry.get("tags");
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof JsonArray array) || array.size() > MAX_TAGS) {
            throw new StencilException(where + ": tags must be a list of at most " + MAX_TAGS + " texts");
        }
        List<String> tags = new ArrayList<>(array.size());
        for (JsonValue tag : array) {
            if (!(tag instanceof JsonString text)) {
                throw new StencilException(where + ": tags must be texts");
            }
            tags.add(text(text.getString(), MAX_TAG, where + ", tag"));
        }
        return tags;
    }

    private static String requiredString(JsonObject entry, String key, String where) throws StencilException {
        if (!(entry.get(key) instanceof JsonString value)) {
            throw new StencilException(where + ": \"" + key + "\" is missing or not a text");
        }
        return value.getString();
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
