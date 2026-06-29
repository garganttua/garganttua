package com.garganttua.events.connectors.api;

import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.dsl.ReflectionBuilder;
import com.garganttua.core.reflection.runtime.RuntimeReflectionProvider;

/**
 * Installs a JVM {@code IReflection} on the global {@link IClass} holder so test fixtures can call
 * {@link IClass#getClass(Class)} (used to build {@code OperationDefinition} entities). Idempotent and
 * safe to invoke from every test's {@code @BeforeAll}.
 */
final class TestReflection {

	private static volatile boolean installed;

	private TestReflection() {
	}

	static synchronized void install() {
		if (installed) {
			return;
		}
		IClass.setReflection(ReflectionBuilder.builder()
				.withProvider(new RuntimeReflectionProvider())
				.build());
		installed = true;
	}
}
