package com.demo.ejb.faceframe;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Loads the FaceFrame stencil zip (PNG images plus {@code stencils.json}) and unpacks it into memory. Images in the
 * folder {@value #STICKER_FOLDER} are stickers, all others are stencils. An optional {@code face-frame-settings.json}
 * holds editor settings; a broken one is left out (logged), the stencils are still served.
 * <p>
 * The zip is read from the file storage ({@link StencilStorage}) under the file id configured with the system
 * property {@value #FILE_ID_PROPERTY} or the environment variable {@value #FILE_ID_ENV}. A zip is accepted as a
 * whole or not at all.
 */
@ApplicationScoped
public class StencilZipService {

    static final String FILE_ID_PROPERTY = "faceframe.stencils.file-id";
    static final String FILE_ID_ENV = "FACEFRAME_STENCILS_FILE_ID";

    static final String STICKER_FOLDER = "stickers/";
    static final int MAX_STENCILS = 200;
    static final int MAX_STICKERS = 100;
    static final int MAX_FILE_BYTES = 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 50L * 1024 * 1024;
    /** Room for the stencils plus folders and files that are skipped (e.g. {@code __MACOSX/}). */
    static final int MAX_ENTRIES = 1000;
    static final long MAX_ZIP_BYTES = MAX_TOTAL_BYTES + 10L * 1024 * 1024;

    private static final Logger LOG = Logger.getLogger(StencilZipService.class.getName());

    private static final Pattern STENCIL_PATH =
            Pattern.compile("[A-Za-z0-9][\\w-]*(/[A-Za-z0-9][\\w-]*)?\\.png", Pattern.CASE_INSENSITIVE);
    private static final Set<String> IGNORED_FILES = Set.of(".DS_Store", "Thumbs.db", ".gitkeep");
    private static final String IGNORED_FOLDER = "__MACOSX/";

    private final StencilStorage storage;

    @Inject
    public StencilZipService(StencilStorage storage) {
        this.storage = storage;
    }

    /** Required by CDI for the client proxy of an application scoped bean; not for direct use. */
    protected StencilZipService() {
        this(null);
    }

    /**
     * Reads the configured zip from the file storage and unpacks it.
     *
     * @return the stencil images and the checked {@code stencils.json}, keyed by path
     */
    public Map<String, StencilFile> load() throws StencilException {
        String fileId = fileId();
        byte[] zip = storage.getFileByFieldId(fileId);
        if (zip == null) {
            throw new StencilException("No stencil zip with file id " + StencilException.printable(fileId)
                    + " in the file storage");
        }
        Map<String, StencilFile> files = unpack(zip);
        long bytes = files.values().stream().mapToLong(StencilFile::size).sum();
        long stickers = files.keySet().stream().filter(StencilZipService::isSticker).count();
        long stencils = files.keySet().stream().filter(p -> p.endsWith(".png") && !isSticker(p)).count();
        boolean settings = files.containsKey(StencilSettings.FILE_NAME);
        LOG.info(() -> String.format(Locale.ROOT, "Loaded %d stencils, %d stickers%s (%.1f MB) from file id %s",
                stencils, stickers, settings ? " and editor settings" : "", bytes / (1024.0 * 1024.0),
                StencilException.printable(fileId)));
        return files;
    }

    /** Same configuration style as the rest of the application: system property first, then environment. */
    private static String fileId() throws StencilException {
        String fileId = System.getProperty(FILE_ID_PROPERTY);
        if (fileId == null || fileId.isBlank()) {
            fileId = System.getenv(FILE_ID_ENV);
        }
        if (fileId == null || fileId.isBlank()) {
            throw new StencilException("No stencil zip configured: set -D" + FILE_ID_PROPERTY + " or " + FILE_ID_ENV);
        }
        return fileId.strip();
    }

    public Map<String, StencilFile> unpack(byte[] zip) throws StencilException {
        if (zip.length > MAX_ZIP_BYTES) {
            throw new StencilException("Stencil zip is larger than " + megabytes(MAX_ZIP_BYTES));
        }
        Map<String, StencilFile> images = new TreeMap<>();
        byte[] manifest = null;
        byte[] settings = null;
        long totalBytes = 0;
        int entries = 0;
        int stencils = 0;
        int stickers = 0;

        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (++entries > MAX_ENTRIES) {
                    throw new StencilException("Zip contains more than " + MAX_ENTRIES + " entries");
                }
                String path = entry.getName();
                boolean skipped = entry.isDirectory() || isIgnored(path);
                boolean isManifest = path.equals(StencilManifest.FILE_NAME);
                boolean isSettings = path.equals(StencilSettings.FILE_NAME);
                // the name is checked before anything is read
                if (isManifest) {
                    requireSingle(manifest, path);
                } else if (isSettings) {
                    requireSingle(settings, path);
                } else if (!skipped) {
                    requireValidPath(path, images);
                    if (isSticker(path)) {
                        requireRoom(++stickers, MAX_STICKERS, "stickers");
                    } else {
                        requireRoom(++stencils, MAX_STENCILS, "stencils");
                    }
                }
                // skipped entries are read too: moving to the next entry would unpack them without a limit
                byte[] data = read(in, StencilException.printable(path));
                totalBytes += data.length;
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw new StencilException("Zip exceeds " + megabytes(MAX_TOTAL_BYTES) + " unpacked");
                }
                if (skipped) {
                    continue;
                }
                if (isManifest) {
                    manifest = data;
                    continue;
                }
                if (isSettings) {
                    settings = data;
                    continue;
                }
                if (isSticker(path)) {
                    StencilImage.validateSticker(path, data);
                } else {
                    StencilImage.validate(path, data);
                }
                images.put(path, StencilFile.of(data, StencilImage.CONTENT_TYPE));
            }
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException: entry name that is not valid UTF-8
            throw new StencilException("Cannot read stencil zip: " + StencilException.printable(
                    String.valueOf(e.getMessage())), e);
        }
        if (entries == 0) {
            throw new StencilException("Stencil zip is empty or not a zip file");
        }

        Map<String, StencilFile> files = new HashMap<>(images);
        byte[] checked = StencilManifest.check(manifest, images.keySet());
        files.put(StencilManifest.FILE_NAME, StencilFile.of(checked, StencilManifest.CONTENT_TYPE));
        if (settings != null) {
            addSettings(files, settings);
        }
        return files;
    }

    /** Settings only change how the editor behaves: a broken file must not cost the stencils. */
    private static void addSettings(Map<String, StencilFile> files, byte[] settings) {
        try {
            StencilSettings.Checked checked = StencilSettings.check(settings);
            if (!checked.dropped().isEmpty()) {
                LOG.warning(() -> StencilSettings.FILE_NAME + ": unknown or wrong type, left out: "
                        + String.join(", ", checked.dropped()));
            }
            files.put(StencilSettings.FILE_NAME, StencilFile.of(checked.json(), StencilSettings.CONTENT_TYPE));
        } catch (StencilException e) {
            LOG.warning(() -> e.getMessage() + "; the editor uses its own settings");
        }
    }

    private static void requireSingle(byte[] earlier, String path) throws StencilException {
        if (earlier != null) {
            throw new StencilException(path + ": duplicate entry");
        }
    }

    private static void requireValidPath(String path, Map<String, StencilFile> images) throws StencilException {
        if (!STENCIL_PATH.matcher(path).matches()) {
            throw new StencilException(StencilException.printable(path)
                    + ": only PNG files named [A-Za-z0-9_-], at most one subfolder");
        }
        if (images.containsKey(path)) {
            throw new StencilException(path + ": duplicate entry");
        }
    }

    private static void requireRoom(int count, int max, String what) throws StencilException {
        if (count > max) {
            throw new StencilException("Zip contains more than " + max + " " + what);
        }
    }

    /** Case-sensitive like all paths in the zip: {@code Stickers/} is a stencil category. */
    static boolean isSticker(String path) {
        return path.startsWith(STICKER_FOLDER);
    }

    /** Reads at most one byte past the limit: the size declared in the zip is not trusted. */
    private static byte[] read(ZipInputStream in, String path) throws IOException, StencilException {
        byte[] data = in.readNBytes(MAX_FILE_BYTES + 1);
        if (data.length > MAX_FILE_BYTES) {
            throw new StencilException(path + ": larger than " + megabytes(MAX_FILE_BYTES));
        }
        return data;
    }

    private static boolean isIgnored(String path) {
        return path.startsWith(IGNORED_FOLDER) || IGNORED_FILES.contains(path.substring(path.lastIndexOf('/') + 1));
    }

    private static String megabytes(long bytes) {
        return bytes / (1024 * 1024) + " MB";
    }
}
