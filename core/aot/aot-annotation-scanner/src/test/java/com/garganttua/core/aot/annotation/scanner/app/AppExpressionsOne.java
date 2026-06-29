package com.garganttua.core.aot.annotation.scanner.app;

import com.garganttua.core.aot.annotation.scanner.TestIndexed;

/**
 * Test stand-in for an application class declaring an {@code @Expression}-style
 * static method, discoverable only through an AOT index file (no FQN registrar,
 * no classpath-scanning fallback). Mirrors how a real application exposes
 * expression functions.
 */
public final class AppExpressionsOne {

    private AppExpressionsOne() {
    }

    /**
     * A method indexed via a {@code M:} entry under
     * {@code META-INF/garganttua/index/}.
     *
     * @param s an argument
     * @return the argument unchanged
     */
    @TestIndexed
    public static String fn(String s) {
        return s;
    }
}
