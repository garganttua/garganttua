package com.garganttua.api.core.expression;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.core.expression.annotations.Expression;

/**
 * No method of an expression class may share an expression's name and arity without being an expression itself.
 *
 * <p>
 * The expression context binds each {@code @Expression} method by re-resolving it on its class by name and
 * signature. A second method of the same name and parameter count — an internal helper — made
 * {@code invokeAuthenticate} ambiguous in a native image (3.0.0-ALPHA34, local build): the API did not mount,
 * every route answered 404, and nothing showed on the JVM, where the parameter types tell the two apart. The
 * resolver now prefers the identical signature; this keeps the trap from being set again, at build time, where
 * a native run is not needed to see it.
 * </p>
 */
@DisplayName("Expression classes — no helper shares an expression's name and arity")
class ExpressionNameClashTest {

	@Test
	@DisplayName("every method sharing an expression's name and arity is itself an expression")
	void noClash() throws Exception {
		List<String> clashes = new ArrayList<>();
		for (Class<?> type : expressionClasses()) {
			for (Method expression : type.getDeclaredMethods()) {
				if (!expression.isAnnotationPresent(Expression.class)) {
					continue;
				}
				for (Method other : type.getDeclaredMethods()) {
					if (!other.equals(expression) && !other.isSynthetic()
							&& other.getName().equals(expression.getName())
							&& other.getParameterCount() == expression.getParameterCount()
							&& !other.isAnnotationPresent(Expression.class)) {
						clashes.add(type.getSimpleName() + "#" + other.getName() + "/" + other.getParameterCount());
					}
				}
			}
		}
		assertTrue(clashes.isEmpty(), () -> "helpers named like an expression, same arity — rename them: " + clashes);
	}

	@Test
	@DisplayName("the scan does see the expression classes")
	void scanSeesThem() throws Exception {
		assertTrue(expressionClasses().contains(SecurityAuthenticationExpressions.class));
	}

	/** The top-level classes of this package, read from the compiled classes, and the AOT infrastructure seed. */
	private static List<Class<?>> expressionClasses() throws URISyntaxException, ClassNotFoundException {
		String pkg = ExpressionNameClashTest.class.getPackageName();
		URL dir = SecurityAuthenticationExpressions.class.getResource(
				"/" + pkg.replace('.', '/') + "/" + SecurityAuthenticationExpressions.class.getSimpleName() + ".class");
		File folder = new File(dir.toURI()).getParentFile();
		List<Class<?>> classes = new ArrayList<>();
		String[] names = folder.list((d, name) -> name.endsWith(".class") && !name.contains("$")
				&& !name.startsWith("AOT"));
		for (String name : names == null ? new String[0] : names) {
			Class<?> type = Class.forName(pkg + "." + name.substring(0, name.length() - ".class".length()));
			if (!type.getSimpleName().endsWith("Test")) {
				classes.add(type);
			}
		}
		classes.add(com.garganttua.api.core.aot.ApiCoreInfrastructureSeed.class);
		return classes;
	}
}
