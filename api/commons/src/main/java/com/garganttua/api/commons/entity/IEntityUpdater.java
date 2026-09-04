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
	 *
	 * <p><b>Changed in 3.0.0-ALPHA17</b> — this returned the merged entity alone. It now also reports
	 * which fields it wrote and which it dropped, so that a caller can be told a named field was not
	 * applied instead of reading a {@code 200} and assuming it was. The merged entity is
	 * {@link EntityWriteOutcome#entity()}.
	 */
	EntityWriteOutcome update(ICaller caller, Object storedEntity, Object updatedEntity,
			List<EntityUpdateRule> updateRules) throws ApiException;

}
