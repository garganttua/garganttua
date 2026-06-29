package com.garganttua.core.aot.annotation.scanner.app;

import com.garganttua.core.aot.annotation.scanner.TestIndexed;

/**
 * Second test stand-in declaring an {@code @Expression}-style static method,
 * used to prove that {@code M:} entries are merged across multiple index URLs
 * resolved at the same resource path (per-module application indices).
 */
public final class AppExpressionsTwo {

    private AppExpressionsTwo() {
    }

    /**
     * A method indexed via a {@code M:} entry in a SECOND index URL.
     *
     * @param value an argument
     * @return the argument unchanged
     */
    @TestIndexed
    public static String other(String value) {
        return value;
    }
}
