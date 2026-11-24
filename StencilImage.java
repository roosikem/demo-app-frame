package com.demo.ejb.faceframe;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Validates stencil images: complete PNG file structure, size and transparency, then decodes the image once so
 * damaged image data is found at load time and not in the browser.
 */
final class StencilImage {

    static final String CONTENT_TYPE = "image/png";

    /** Stencil template size: square canvas, frame inside a radius of 504 px around the centre. */
    private static final int REQUIRED_SIZE_PX = 1024;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    /** Length, type and CRC around every chunk's data. */
    private static final int CHUNK_OVERHEAD = 12;
    /** Critical chunks a decoder must understand (PNG specification, section 4.3); others are rejected. */
    private static final Set<String> KNOWN_CRITICAL = Set.of("IHDR", "PLTE", "IDAT", "IEND");

    private StencilImage() {
    }

    static void validate(String path, byte[] data) throws StencilException {
        requireCompletePng(path, data);
        ImageReader reader = pngReader();
        // memory-backed stream: ImageIO would otherwise buffer to a temp file
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(in, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width != REQUIRED_SIZE_PX || height != REQUIRED_SIZE_PX) {
                throw new StencilException(path + ": " + width + " x " + height
                        + " px, must be " + REQUIRED_SIZE_PX + " x " + REQUIRED_SIZE_PX);
            }
            ImageTypeSpecifier type = reader.getRawImageType(0);
            if (type == null || !type.getColorModel().hasAlpha()) {
                throw new StencilException(path + ": no transparency, the photo would be covered");
            }
            // size is fixed above, so this is at most 1024 x 1024 pixels
            reader.read(0);
        } catch (IOException | RuntimeException e) {
            throw new StencilException(path + ": not a valid PNG file", e);
        } finally {
            reader.dispose();
        }
    }

    /**
     * Walks the chunk list (PNG specification, section 5): every chunk inside the file with a correct CRC, exactly
     * one IHDR and it first, image data in IDAT, no unknown critical chunk, an empty IEND last and nothing after it.
     */
    private static void requireCompletePng(String path, byte[] data) throws StencilException {
        if (data.length < PNG_SIGNATURE.length
                || !Arrays.equals(data, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) {
            throw new StencilException(path + ": not a valid PNG file");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data, PNG_SIGNATURE.length, data.length - PNG_SIGNATURE.length);
        boolean first = true;
        long imageDataBytes = 0;
        while (buffer.remaining() >= CHUNK_OVERHEAD) {
            int length = buffer.getInt();
            if (length < 0 || length > buffer.remaining() - 8) {
                throw new StencilException(path + ": not a valid PNG file (cut off)");
            }
            int typeStart = buffer.position();
            String type = new String(data, typeStart, 4, StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(data, typeStart, 4 + length);
            buffer.position(typeStart + 4 + length);
            if ((int) crc.getValue() != buffer.getInt()) {
                throw new StencilException(path + ": not a valid PNG file (damaged)");
            }
            if (first != "IHDR".equals(type)) {
                throw new StencilException(path + ": not a valid PNG file (IHDR must come first and only once)");
            }
            first = false;
            if (isCritical(type) && !KNOWN_CRITICAL.contains(type)) {
                throw new StencilException(path + ": not a valid PNG file (unknown chunk "
                        + StencilException.printable(type) + ")");
            }
            if ("IDAT".equals(type)) {
                imageDataBytes += length;
            }
            if ("IEND".equals(type)) {
                if (imageDataBytes == 0 || length != 0 || buffer.hasRemaining()) {
                    throw new StencilException(path + ": not a valid PNG file (no image data or data after the end)");
                }
                return;
            }
        }
        throw new StencilException(path + ": not a valid PNG file (cut off)");
    }

    /** Upper case first letter = critical chunk. */
    private static boolean isCritical(String type) {
        return Character.isUpperCase(type.charAt(0));
    }

    private static ImageReader pngReader() {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
        if (!readers.hasNext()) {
            throw new IllegalStateException("No PNG reader available");
        }
        return readers.next();
    }
}
