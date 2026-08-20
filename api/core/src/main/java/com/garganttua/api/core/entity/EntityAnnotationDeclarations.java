package com.garganttua.api.core.entity;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Objects;

import org.javatuples.Pair;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.IMethod;
import com.garganttua.core.reflection.IReflectionProvider;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.ReflectionException;
import com.garganttua.core.reflection.fields.FieldResolver;
import com.garganttua.core.reflection.methods.MethodResolver;

/**
 * Resolves the {@code entity().annotation(element, annotation)} declarations of {@link EntityBuilder}
 * to an element address and records the pair once. Extracted from {@code EntityBuilder} to keep that
 * wide-interface builder under the file-size gate.
 */
final class EntityAnnotationDeclarations {

	private EntityAnnotationDeclarations() {
	}

	/** Records the (field address, annotation) pair; a duplicate pair is a no-op. */
	static void field(IClass<?> entityClass, IReflectionProvider provider,
			List<Pair<ObjectAddress, IClass<? extends Annotation>>> annotatedFields,
			IField field, IClass<? extends Annotation> annotation) throws ApiException {
		Objects.requireNonNull(field, "Field cannot be null");
		Objects.requireNonNull(annotation, "Annotation cannot be null");

		ObjectAddress address = FieldResolver.fieldByFieldName(entityClass, provider, field.getName(), null).address();
		addOnce(annotatedFields, address, annotation);
	}

	/** Records the (method address, annotation) pair; a duplicate pair is a no-op. */
	static void method(IClass<?> entityClass, IReflectionProvider provider,
			List<Pair<ObjectAddress, IClass<? extends Annotation>>> annotatedMethods,
			IMethod method, IClass<? extends Annotation> annotation) throws ApiException {
		Objects.requireNonNull(method, "Method cannot be null");
		Objects.requireNonNull(annotation, "Annotation cannot be null");

		try {
			addOnce(annotatedMethods, MethodResolver.methodByMethod(entityClass, provider, method).address(), annotation);
		} catch (ReflectionException e) {
			throw new ApiException(e.getMessage(), e);
		}
	}

	private static void addOnce(List<Pair<ObjectAddress, IClass<? extends Annotation>>> target,
			ObjectAddress address, IClass<? extends Annotation> annotation) {
		Pair<ObjectAddress, IClass<? extends Annotation>> candidate = new Pair<>(address, annotation);
		if (!target.contains(candidate)) {
			target.add(candidate);
		}
	}
}
