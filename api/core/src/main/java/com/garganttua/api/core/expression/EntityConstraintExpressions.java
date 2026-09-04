package com.garganttua.api.core.expression;

import java.util.List;
import java.util.Optional;

import org.javatuples.Pair;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.entity.MandatoryPolicy;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.repository.IRepository;
import com.garganttua.api.core.entity.EntityDefinition;
import com.garganttua.api.core.filter.Filter;
import com.garganttua.core.expression.annotations.Expression;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.annotations.Reflected;

import static com.garganttua.api.core.expression.ExpressionUtils.*;

/**
 * The declarative constraints an entity must satisfy before it is written: the fields that must
 * carry a value, and those whose value must be unique.
 *
 * <p>
 * Split out of {@link EntityLifecycleExpressions} to keep that class within the size gate. The two
 * belong together and away from the lifecycle hooks: both answer "may this entity be persisted as
 * it stands?" from the entity DEFINITION alone, where the hooks hand control to a consumer's code.
 * </p>
 *
 * <p>
 * These functions are discovered by the package scan on {@code com.garganttua.api.core.expression}
 * ({@code ApiBuilderBuild}), so the class must stay in this package; under AOT it also needs its
 * entry in {@code ApiCoreInfrastructureSeed}, or the functions silently vanish.
 * </p>
 */
@Reflected(queryAllPublicMethods = true)
public class EntityConstraintExpressions {

	@Expression(name = "validateMandatories",
			description = "Validates the @EntityMandatory fields of a COMPLETE entity: every one must be non-null, and "
					+ "a field declared nonBlank must additionally not be empty or whitespace. This is the creation "
					+ "rule; validateProvidedMandatories carries the partial-body rule for updates.")
	public static void validateMandatories(Object entity, Object context) {
		try {
			IDomain<?> dc = toDomain(context);
			EntityDefinition<?> entityDef = (EntityDefinition<?>) dc.getEntityDefinition();
			List<Pair<ObjectAddress, MandatoryPolicy>> mandatories = entityDef.mandatories();
			if (mandatories == null || mandatories.isEmpty()) return;

			for (Pair<ObjectAddress, MandatoryPolicy> mandatory : mandatories) {
				ObjectAddress address = mandatory.getValue0();
				Object value = REFLECTION.getFieldValue(entity, address.toString());
				if (value == null) {
					throw ApiException.badRequest("Mandatory field '" + address + "' is null");
				}
				if (mandatory.getValue1() == MandatoryPolicy.nonBlank && isBlank(value)) {
					throw ApiException.badRequest("Mandatory field '" + address + "' is empty");
				}
			}
		} catch (ApiException e) {
			throw e;
		} catch (Exception e) {
			throw new ApiException("Failed to validate mandatory fields", e);
		}
	}

	@Expression(name = "validateProvidedMandatories",
			description = "The UPDATE counterpart of validateMandatories. Since PATCH became the update verb the body "
					+ "is partial, so replaying the creation rule would refuse nearly every request a screen emits. "
					+ "This looks ONLY at what the client actually sent: an absent field and a null field pass (the "
					+ "latter is already the ignoreNull semantics), while an empty or whitespace value on a field "
					+ "declared nonBlank is refused — it is an explicit erasure of a field that must carry a value.")
	public static void validateProvidedMandatories(Object submitted, Object context) {
		Object body = unwrapOptional(submitted);
		if (body == null) return;
		try {
			IDomain<?> dc = toDomain(context);
			EntityDefinition<?> entityDef = (EntityDefinition<?>) dc.getEntityDefinition();
			List<Pair<ObjectAddress, MandatoryPolicy>> mandatories = entityDef.mandatories();
			if (mandatories == null || mandatories.isEmpty()) return;

			for (Pair<ObjectAddress, MandatoryPolicy> mandatory : mandatories) {
				if (mandatory.getValue1() != MandatoryPolicy.nonBlank) {
					continue;
				}
				ObjectAddress address = mandatory.getValue0();
				Object value = REFLECTION.getFieldValue(body, address.toString());
				// null covers BOTH "absent from the body" and "explicitly null": the mapping renders
				// them identically, and both are meant to pass.
				if (value != null && isBlank(value)) {
					throw ApiException.badRequest(
							"Mandatory field '" + address + "' cannot be set to an empty value");
				}
			}
		} catch (ApiException e) {
			throw e;
		} catch (Exception e) {
			throw new ApiException("Failed to validate provided mandatory fields", e);
		}
	}

	/**
	 * Whether a supplied value counts as "no value" under {@link MandatoryPolicy#nonBlank}. Only
	 * character data can be blank; any other type that is present carries a value by definition.
	 */
	private static boolean isBlank(Object value) {
		return value instanceof CharSequence text && text.toString().isBlank();
	}

	@Expression(name = "validateUnicity", description = "Checks unicity constraints against existing entities in repository")
	public static void validateUnicity(Object entity, Object repository, Object context) {
		try {
			IDomain<?> dc = toDomain(context);
			EntityDefinition<?> entityDef = (EntityDefinition<?>) dc.getEntityDefinition();
			List<Pair<ObjectAddress, UnicityScope>> unicities = entityDef.unicities();
			if (unicities == null || unicities.isEmpty()) return;

			IRepository repo = (IRepository) repository;
			ObjectAddress tenantIdAddress = entityDef.tenantId();
			ObjectAddress uuidAddress = entityDef.uuid();

			Object currentUuid = uuidAddress != null
					? REFLECTION.getFieldValue(entity, uuidAddress.toString())
					: null;

			for (Pair<ObjectAddress, UnicityScope> unicity : unicities) {
				checkUnicityConstraint(entity, repo, unicity, tenantIdAddress, uuidAddress, currentUuid);
			}
		} catch (ApiException e) {
			throw e;
		} catch (Exception e) {
			throw new ApiException("Failed to validate unicity constraints", e);
		}
	}

	private static void checkUnicityConstraint(Object entity, IRepository repo,
			Pair<ObjectAddress, UnicityScope> unicity, ObjectAddress tenantIdAddress,
			ObjectAddress uuidAddress, Object currentUuid) {
		ObjectAddress fieldAddress = unicity.getValue0();
		UnicityScope scope = unicity.getValue1();
		Object fieldValue = REFLECTION.getFieldValue(entity, fieldAddress.toString());
		if (fieldValue == null) {
			return;
		}

		Filter fieldFilter = Filter.eq(fieldAddress.toString(), fieldValue);

		IFilter queryFilter = fieldFilter;
		if (scope == UnicityScope.tenant && tenantIdAddress != null) {
			Object tenantId = REFLECTION.getFieldValue(entity, tenantIdAddress.toString());
			if (tenantId != null) {
				Filter tenantFilter = Filter.eq(tenantIdAddress.toString(), tenantId);
				queryFilter = Filter.and(fieldFilter, tenantFilter);
			}
		}

		if (currentUuid != null && uuidAddress != null) {
			Filter excludeSelf = Filter.ne(uuidAddress.toString(), currentUuid);
			queryFilter = Filter.and((Filter) queryFilter, excludeSelf);
		}

		List<Object> existing = repo.getEntities(Optional.empty(), Optional.of(queryFilter), Optional.empty());
		if (!existing.isEmpty()) {
			throw new ApiException("Unicity constraint violated for field '" + fieldAddress + "'");
		}
	}
}
