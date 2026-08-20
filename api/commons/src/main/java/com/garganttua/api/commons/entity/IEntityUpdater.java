package com.garganttua.api.commons.entity;

import java.util.List;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.caller.ICaller;

@FunctionalInterface
public interface IEntityUpdater {

	/**
	 * Merges the authorized fields of {@code updatedEntity} onto {@code storedEntity}.
	 *
	 * <p>Each rule decides whether a {@code null} incoming value erases the stored value (the
	 * default) or leaves it untouched ({@link EntityUpdateRule#ignoreNull()}).
	 */
	Object update(ICaller caller, Object storedEntity, Object updatedEntity,
			List<EntityUpdateRule> updateRules) throws ApiException;

}
