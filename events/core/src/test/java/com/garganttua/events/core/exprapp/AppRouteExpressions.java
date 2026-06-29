package com.garganttua.events.core.exprapp;

import com.garganttua.core.expression.annotations.Expression;

/**
 * Application-defined {@code @Expression} provider used as the oracle for the events route-stage
 * expression-resolution parity test. It lives in a NON-framework package
 * ({@code com.garganttua.events.core.exprapp}) so it is only reachable by the scan when the events
 * DSL propagates its application package into the shared expression context — exactly the way
 * garganttua-api exposes an app's {@code @Expression} functions in its pipeline stages.
 */
public final class AppRouteExpressions {

    private AppRouteExpressions() {
    }

    /**
     * Trivial application route function: prefixes its argument with {@code "app:"}.
     *
     * @param s the input value
     * @return {@code "app:" + s}
     */
    @Expression(name = "app_route_fn")
    public static String app_route_fn(String s) {
        return "app:" + s;
    }
}
