package com.garganttua.api.core.entity;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.javatuples.Pair;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.commons.entity.EntityWriteOutcome;
import com.garganttua.api.commons.entity.IEntityCreator;
import com.garganttua.api.core.mapper.DefaultMapper;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.ObjectAddress;

/**
 * Enforces the CREATE-time field whitelist. When a domain declares any {@code entity().create(...)}
 * field, creation becomes a whitelist: only the declared fields the caller is authorized for survive
 * on the inbound entity; every other client-supplied field is stripped (set to null) before the
 * framework stamps uuid/tenantId/ownerId and persists. With no {@code create(...)} declared, the
 * entity is returned untouched (creation is unrestricted).
 *
 * <p>Authority rules mirror {@link EntityUpdater}: a null/empty required authority is open; otherwise
 * the caller must carry it in {@code caller.authorities()} (a null/empty list fails). Super-tenant /
 * super-owner status does <strong>not</strong> bypass the gate — being super grants cross-tenant /
 * cross-owner reach, not the authority to valorize a guarded field.
 */
public class EntityCreator implements IEntityCreator {

	private static final IReflection REFLECTION = DefaultMapper.reflection();

	/** Primitive fields cannot hold null and carry no "unset" beyond their zero value — left as-is. */
	private static final Set<String> PRIMITIVES = Set.of(
			"boolean", "byte", "char", "short", "int", "long", "float", "double");

	@Override
	public EntityWriteOutcome create(ICaller caller, Object entity,
			List<Pair<ObjectAddress, String>> createAuthorizations) {
		if (createAuthorizations == null || createAuthorizations.isEmpty()) {
			return EntityWriteOutcome.unrestricted(entity); // no whitelist declared → creation is unrestricted
		}
		if (caller == null) {
			throw new ApiException("Caller is null");
		}

		// The fields the caller may keep: declared AND authorized for.
		Set<String> allowed = new HashSet<>();
		for (Pair<ObjectAddress, String> entry : createAuthorizations) {
			if (isAuthorized(caller, entry.getValue1())) {
				allowed.add(entry.getValue0().toString());
			}
		}

		List<ObjectAddress> applied = new ArrayList<>();
		List<ObjectAddress> rejected = new ArrayList<>();
		try {
			stripUnauthorizedFields(entity, allowed, applied, rejected);
		} catch (ApiException e) {
			throw e;
		} catch (Exception e) {
			throw new ApiException("Failed to strip unauthorized fields at creation", e);
		}
		return new EntityWriteOutcome(entity, applied, rejected);
	}

	/**
	 * Nulls out every non-static, non-transient, non-primitive field that is not in {@code allowed},
	 * and sorts the fields the client actually SENT into the two lists.
	 *
	 * <p>
	 * Only a field carrying a value is reported, on either side: a field the client left out is
	 * null before the strip and null after it, and neither applying nor refusing nothing is worth
	 * telling the caller about. Primitives are skipped for the reason they always were — they carry
	 * no "unset" state to distinguish.
	 * </p>
	 */
	private static void stripUnauthorizedFields(Object entity, Set<String> allowed,
			List<ObjectAddress> applied, List<ObjectAddress> rejected) {
		IClass<?> clazz = IClass.getClass(entity.getClass());
		while (clazz != null) {
			for (IField field : clazz.getDeclaredFields()) {
				int mods = field.getModifiers();
				if (Modifier.isStatic(mods) || Modifier.isTransient(mods)) {
					continue;
				}
				String fieldName = field.getName();
				if (PRIMITIVES.contains(field.getType().getName())) {
					continue;
				}
				boolean carriedAValue = REFLECTION.getFieldValue(entity, fieldName) != null;
				if (allowed.contains(fieldName)) {
					if (carriedAValue) {
						applied.add(new ObjectAddress(fieldName));
					}
					continue;
				}
				if (carriedAValue) {
					rejected.add(new ObjectAddress(fieldName));
				}
				REFLECTION.setFieldValue(entity, fieldName, null);
			}
			clazz = clazz.getSuperclass();
		}
	}

	/** Mirrors {@code EntityUpdater.isAuthorized}: no super bypass; a null/empty list fails a gated field. */
	private static boolean isAuthorized(ICaller caller, String requiredAuthority) {
		if (requiredAuthority == null || requiredAuthority.isEmpty()) {
			return true;
		}
		List<String> callerAuthorities = caller.authorities();
		if (callerAuthorities == null) {
			return false;
		}
		return callerAuthorities.contains(requiredAuthority);
	}
}
