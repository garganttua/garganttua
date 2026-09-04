package com.garganttua.api.core.entity;

import java.util.ArrayList;
import java.util.List;

import com.garganttua.api.core.mapper.DefaultMapper;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.commons.entity.EntityUpdateRule;
import com.garganttua.api.commons.entity.EntityWriteOutcome;
import com.garganttua.api.commons.entity.IEntityUpdater;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.ObjectAddress;

/**
 * Merges the authorized fields of an incoming entity onto the stored one.
 *
 * <p><b>Null handling.</b> Each {@link EntityUpdateRule} carries its own policy. By default
 * ({@code ignoreNull = false}) a {@code null} incoming value ERASES the stored value — PUT
 * semantics: the client body describes the full state of every updatable field. A field declared
 * with {@code ignoreNull = true} opts into PATCH semantics: a {@code null} means "not supplied" and
 * the stored value is left untouched.
 */
public class EntityUpdater implements IEntityUpdater{

	private static final IReflection REFLECTION = DefaultMapper.reflection();

	@Override
	public EntityWriteOutcome update(ICaller caller, Object storedEntity, Object updatedEntity,
			List<EntityUpdateRule> updateRules) {
		if (updateRules == null || updateRules.isEmpty()) {
			return EntityWriteOutcome.unrestricted(storedEntity);
		}
		if (caller == null) {
			throw new ApiException("Caller is null");
		}
		if (!storedEntity.getClass().equals(updatedEntity.getClass())) {
			throw new ApiException("Stored entity type [" + storedEntity.getClass().getSimpleName()
					+ "] and updated entity type [" + updatedEntity.getClass().getSimpleName() + "] mismatch");
		}

		List<ObjectAddress> applied = new ArrayList<>();
		List<ObjectAddress> rejected = new ArrayList<>();
		try {
			for (EntityUpdateRule rule : updateRules) {
				ObjectAddress fieldAddress = rule.field();
				String fieldName = fieldAddress.toString();
				Object updatedValue = REFLECTION.getFieldValue(updatedEntity, fieldName);
				if (!isAuthorized(caller, rule.authority())) {
					// Refused for lack of the required authority. Reported only when the client
					// actually SENT a value: a null here means the field was absent from the body
					// (or explicitly null), and refusing to write nothing is not a refusal to report.
					if (updatedValue != null) {
						rejected.add(fieldAddress);
					}
					continue;
				}
				if (updatedValue == null && rule.ignoreNull()) {
					continue;
				}
				REFLECTION.setFieldValue(storedEntity, fieldName, updatedValue);
				applied.add(fieldAddress);
			}
		} catch (ApiException e) {
			throw e;
		} catch (Exception e) {
			throw new ApiException("Failed to update entity", e);
		}

		return new EntityWriteOutcome(storedEntity, applied, rejected);
	}

	/**
	 * Decides whether the caller may write the field guarded by
	 * {@code requiredAuthority}. The rules mirror
	 * {@code SecurityExpressions.callerHasAuthority}:
	 *
	 * <ul>
	 *   <li>No authority required (null or empty) → allowed.</li>
	 *   <li>Otherwise the caller must carry the named authority in
	 *       {@code caller.authorities()}; a {@code null} or empty list
	 *       fails the check (the previous "null means unrestricted"
	 *       behaviour was a security hole — a freshly-built
	 *       {@code Caller.createTenantCaller} has null authorities and
	 *       must not bypass field-level gates).</li>
	 * </ul>
	 *
	 * <p>Super-tenant / super-owner status does <strong>not</strong> bypass the
	 * gate: being super grants cross-tenant / cross-owner reach, not the
	 * authority to mutate a guarded field — a super caller must still carry it.
	 */
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
