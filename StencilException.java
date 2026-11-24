package com.demo.ejb.faceframe;

import java.util.regex.Pattern;

/**
 * A stencil zip that cannot be read or does not meet the stencil rules.
 */
public class StencilException extends Exception {

    private static final long serialVersionUID = 1L;

    private static final Pattern NOT_PRINTABLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");
    private static final int MAX_NAME_IN_MESSAGE = 100;

    public StencilException(String message) {
        super(message);
    }

    public StencilException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Zip entry names come from outside: no line breaks or control characters in the log, and kept short. */
    static String printable(String text) {
        String cleaned = NOT_PRINTABLE.matcher(text).replaceAll("?");
        return cleaned.length() > MAX_NAME_IN_MESSAGE ? cleaned.substring(0, MAX_NAME_IN_MESSAGE) + "..." : cleaned;
    }
}
