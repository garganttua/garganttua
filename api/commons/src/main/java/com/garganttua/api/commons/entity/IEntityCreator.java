package com.garganttua.api.commons.entity;

import java.util.List;

import org.javatuples.Pair;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.core.reflection.ObjectAddress;

/**
 * Applies the CREATE-time field whitelist: strips from a freshly-deserialized entity every field
 * the caller is not authorized to valorize at creation. The CREATE-time analogue of
 * {@link IEntityUpdater}.
 */
@FunctionalInterface
public interface IEntityCreator {

	/**
	 * Strips the fields the caller may not valorize, and reports what it stripped.
	 *
	 * <p><b>Changed in 3.0.0-ALPHA17</b> — this returned the stripped entity alone; it now also
	 * carries the fields the client sent and did not get, for the same reason as
	 * {@link IEntityUpdater#update}. The entity is {@link EntityWriteOutcome#entity()}.
	 */
	EntityWriteOutcome create(ICaller caller, Object entity,
			List<Pair<ObjectAddress, String>> createAuthorizations) throws ApiException;

}
