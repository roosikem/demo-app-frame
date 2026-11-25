package com.demo.ejb.faceframe;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks the optional {@code face-frame-settings.json} in the stencil zip: editor settings the operations team can
 * change without a frontend release, e.g.
 * <pre>
 * { "features": { "stickers": true, "textMove": false },
 *   "badgeMovement": { "restrictOnText": true },
 *   "stickerEmojis": [], "download": { "size": 2048 } }
 * </pre>
 * Only the known settings are passed on, each with the right JSON type; anything else is dropped and named in the
 * result's {@code dropped} list for the log. The ranges are checked by the editor, which falls back to its own value
 * for every setting that is missing or out of range, so a wrong value never changes more than that one setting.
 */
final class StencilSettings {

    static final String FILE_NAME = "face-frame-settings.json";
    static final String CONTENT_TYPE = "application/json";
    /** Settings are a few hundred bytes; anything larger is not a settings file. */
    static final int MAX_BYTES = 16 * 1024;

    private enum Kind { OBJECT, LIST, NUMBER, FLAG }

    /** The settings the editor accepts from the server (FaceFrameOptions); stencils, labels and URLs are not. */
    private static final Map<String, Kind> KNOWN = Map.ofEntries(
            Map.entry("features", Kind.OBJECT),
            Map.entry("badgeMovement", Kind.OBJECT),
            Map.entry("stickerEmojis", Kind.LIST),
            Map.entry("export", Kind.OBJECT),
            Map.entry("download", Kind.OBJECT),
            Map.entry("zoom", Kind.OBJECT),
            Map.entry("previewSize", Kind.NUMBER),
            Map.entry("minWindowWidth", Kind.NUMBER),
            Map.entry("minContrast", Kind.NUMBER),
            Map.entry("textMaxLength", Kind.NUMBER),
            Map.entry("badgeLineMaxLength", Kind.NUMBER),
            Map.entry("translateStencilTexts", Kind.FLAG),
            // false while checking a settings file: the editor reports each setting it cannot use
            Map.entry("settingsFallback", Kind.FLAG));

    /** The checked settings as served to the browser, and the names of the settings that were left out. */
    record Checked(byte[] json, Set<String> dropped) {
    }

    private StencilSettings() {
    }

    /**
     * @throws StencilException when the file is not a JSON object of reasonable size; the caller then serves no
     *                          settings (the editor uses its own) and keeps the stencils
     */
    static Checked check(byte[] settings) throws StencilException {
        if (settings.length > MAX_BYTES) {
            throw new StencilException(FILE_NAME + ": larger than " + MAX_BYTES / 1024 + " KB");
        }
        try (Jsonb jsonb = JsonbBuilder.create()) {
            Map<?, ?> root = JsonObjects.parse(jsonb, settings, FILE_NAME);
            Map<String, Object> known = new LinkedHashMap<>();
            Set<String> dropped = new TreeSet<>();
            for (Map.Entry<?, ?> entry : root.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Kind kind = KNOWN.get(key);
                if (kind != null && hasKind(entry.getValue(), kind)) {
                    known.put(key, entry.getValue());
                } else {
                    dropped.add(StencilException.printable(key));
                }
            }
            return new Checked(jsonb.toJson(known).getBytes(StandardCharsets.UTF_8), dropped);
        } catch (StencilException e) {
            throw e;
        } catch (Exception e) {
            // JsonbException on broken input; Jsonb.close() declares Exception
            throw new StencilException(FILE_NAME + ": not a valid JSON object ("
                    + StencilException.printable(String.valueOf(e.getMessage())) + ")", e);
        }
    }

    private static boolean hasKind(Object value, Kind kind) {
        return switch (kind) {
            case OBJECT -> value instanceof Map<?, ?>;
            case LIST -> value instanceof List<?>;
            case NUMBER -> value instanceof BigDecimal;
            case FLAG -> value instanceof Boolean;
        };
    }
}
