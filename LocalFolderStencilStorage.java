package com.demo.ejb.faceframe;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Demo stand-in for the file storage service, so this demo deploys without it: the file id is a file name in
 * the folder given by {@value #FOLDER_PROPERTY}. Not part of the real project: there {@link StencilStorage} is
 * implemented with {@code FileStorageServiceLocal} (see there) and this class is deleted.
 */
@ApplicationScoped
public class LocalFolderStencilStorage implements StencilStorage {

    static final String FOLDER_PROPERTY = "faceframe.demo.storage.dir";

    private static final Pattern SAFE_FILE_ID = Pattern.compile("[\\w-]+(\\.zip)?");

    @Override
    public byte[] getFileByFieldId(String fileId) throws StencilException {
        String folder = System.getProperty(FOLDER_PROPERTY);
        if (folder == null || folder.isBlank()) {
            throw new StencilException("Demo storage: system property " + FOLDER_PROPERTY + " is not set");
        }
        if (!SAFE_FILE_ID.matcher(fileId).matches()) {
            throw new StencilException("Demo storage: invalid file id " + StencilException.printable(fileId));
        }
        Path file = Path.of(folder).resolve(fileId);
        try {
            if (Files.size(file) > StencilZipService.MAX_ZIP_BYTES) {
                throw new StencilException("Demo storage: " + fileId + " is too large");
            }
            return Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new StencilException("Demo storage: cannot read " + fileId, e);
        }
    }
}
