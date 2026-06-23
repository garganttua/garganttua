package com.garganttua.core.mapper;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.garganttua.core.mapper.annotations.MappingIgnore;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.IReflection;

/**
 * Strict-mode coverage check: verifies that every mappable destination field is
 * covered by a resolved {@link MappingRule}, ignoring static/transient/synthetic
 * fields and those annotated with {@link MappingIgnore}.
 */
final class StrictCoverageValidator {
	private final IReflection reflection;

	StrictCoverageValidator(IReflection reflection) {
		this.reflection = reflection;
	}

	void validate(IClass<?> destination, List<MappingRule> rules) throws MapperException {
		Set<String> coveredFields = ConcurrentHashMap.newKeySet();
		for (MappingRule r : rules) {
			if (r.destinationFieldAddress() != null) {
				coveredFields.add(r.destinationFieldAddress().getLastElement());
			}
		}

		List<String> uncovered = new ArrayList<>();
		collectUncoveredFields(destination, coveredFields, uncovered, resolveMappingIgnoreClass());

		if (!uncovered.isEmpty()) {
			throw new MapperException("Strict mode: uncovered destination fields: " + uncovered);
		}
	}

	/** Resolves the {@link MappingIgnore} descriptor, empty if the reflection lookup fails. */
	private Optional<IClass<MappingIgnore>> resolveMappingIgnoreClass() {
		try {
			return Optional.of(this.reflection.getClass(MappingIgnore.class));
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	private void collectUncoveredFields(IClass<?> clazz, Set<String> coveredFields, List<String> uncovered,
			Optional<IClass<MappingIgnore>> mappingIgnoreClass) {
		for (IField field : clazz.getDeclaredFields()) {
			int modifiers = field.getModifiers();
			if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
				continue;
			}
			if (mappingIgnoreClass.isPresent() && field.isAnnotationPresent(mappingIgnoreClass.get())) {
				continue;
			}
			if (!coveredFields.contains(field.getName())) {
				uncovered.add(clazz.getSimpleName() + "." + field.getName());
			}
		}
		IClass<?> superclass = clazz.getSuperclass();
		if (superclass != null && !"java.lang.Object".equals(superclass.getName())) {
			collectUncoveredFields(superclass, coveredFields, uncovered, mappingIgnoreClass);
		}
	}
}
