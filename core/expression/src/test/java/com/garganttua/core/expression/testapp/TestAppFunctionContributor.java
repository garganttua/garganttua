package com.garganttua.core.expression.testapp;

import java.util.Collection;
import java.util.List;

import com.garganttua.core.expression.dsl.IExpressionFunctionContributor;

/**
 * Test-only {@link IExpressionFunctionContributor} registered via {@code META-INF/services}. It
 * contributes the real {@link AppExpressions} provider plus a deliberately-missing class name, so a
 * single test proves both that an application function resolves through the SPI and that a bad/absent
 * class is isolated (the context still builds and other functions resolve).
 */
public final class TestAppFunctionContributor implements IExpressionFunctionContributor {

    @Override
    public Collection<String> functionClassNames() {
        return List.of(
                "com.garganttua.core.expression.testapp.AppExpressions",
                // deliberately absent — must be skipped without aborting context construction
                "com.garganttua.core.expression.testapp.DoesNotExist");
    }
}
