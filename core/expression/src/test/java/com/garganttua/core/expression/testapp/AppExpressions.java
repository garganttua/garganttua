package com.garganttua.core.expression.testapp;

import com.garganttua.core.expression.annotations.Expression;

import jakarta.annotation.Nonnull;

/**
 * Test-only application {@code @Expression} provider living in a NON-framework package, used to
 * prove that {@code IExpressionFunctionContributor} makes an application's own functions resolvable
 * in an expression context without relying on the runtime/AOT package scanner.
 */
public final class AppExpressions {

    private AppExpressions() {
    }

    /**
     * Application function that echoes its argument with a fixed prefix, registered under the name
     * {@code app_test_fn}.
     *
     * @param value the value to echo; never {@code null}
     * @return {@code "app:" + value}
     */
    @Expression(name = "app_test_fn", description = "Test-app echo function registered via SPI")
    public static String appTestFn(@Nonnull String value) {
        return "app:" + value;
    }
}
