package com.demo.ejb.faceframe;

/**
 * Where the stencil zip is stored. Wraps the file storage service, so the stencil code does not depend on it.
 * <p>
 * Implementation in the real project (the only class that knows the file storage service):
 * <pre>
 * &#64;ApplicationScoped
 * public class FileStorageStencilStorage implements StencilStorage {
 *
 *     &#64;Inject
 *     FileStorageServiceLocal fileStorage;
 *
 *     &#64;Override
 *     public byte[] getFileByFieldId(String fileId) throws StencilException {
 *         try {
 *             return fileStorage.getFileByFieldId(fileId);
 *         } catch (RuntimeException e) {
 *             throw new StencilException("File storage: cannot read stencil zip " + fileId, e);
 *         }
 *     }
 * }
 * </pre>
 */
public interface StencilStorage {

    /**
     * @param fileId id of the stencil zip in the file storage
     * @return the zip file, or {@code null} if there is no file with this id
     */
    byte[] getFileByFieldId(String fileId) throws StencilException;
}
