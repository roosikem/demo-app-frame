package com.demo.ejb.faceframe;

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A stencil image or the stencil manifest, as served to the browser. Immutable: the content is shared by all
 * requests, so it is only handed out as a copy or written to a stream.
 */
public final class StencilFile {

    private final byte[] data;
    private final String contentType;
    private final String etag;

    private StencilFile(byte[] data, String contentType) {
        this.data = data.clone();
        this.contentType = contentType;
        this.etag = hash(this.data);
    }

    public static StencilFile of(byte[] data, String contentType) {
        return new StencilFile(data, contentType);
    }

    /** Media type, e.g. {@code image/png}. */
    public String contentType() {
        return contentType;
    }

    /** Content hash, used as HTTP entity tag. */
    public String etag() {
        return etag;
    }

    public int size() {
        return data.length;
    }

    public void writeTo(OutputStream out) throws IOException {
        out.write(data);
    }

    public byte[] copyOfData() {
        return data.clone();
    }

    @Override
    public String toString() {
        return "StencilFile[" + contentType + ", " + data.length + " bytes, etag " + etag + "]";
    }

    private static String hash(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
