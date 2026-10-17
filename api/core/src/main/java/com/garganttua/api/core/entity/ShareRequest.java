package com.garganttua.api.core.entity;

import java.util.List;

import com.garganttua.api.core.SuppressFBWarnings;
import com.garganttua.core.reflection.annotations.Reflected;

/**
 * The body of the {@code share} operation ({@code PUT /<domain>/{uuid}/shares}): the complete list of
 * grants the entity carries afterwards, as {@link com.garganttua.api.commons.entity.ShareGrant}
 * tokens. An empty list stops sharing the entity.
 *
 * <p>
 * {@code @Reflected} for the same reason as {@code AuthenticationRequest}: the body is deserialized
 * into this type by name, which native-image cannot do without a descriptor.
 * </p>
 *
 * @param grants {@code r|w:tenant:<tenantId>} and {@code r|w:owner:<ownerId>} tokens
 */
@SuppressFBWarnings(value = {"EI_EXPOSE_REP"}, justification = "The grants list is copied into an unmodifiable list by the canonical constructor.")
@Reflected(queryAllDeclaredConstructors = true, queryAllDeclaredMethods = true, allDeclaredFields = true)
public record ShareRequest(List<String> grants) {

	/** Copies the list, so the request does not change under the caller's feet. */
	public ShareRequest {
		// Not List.copyOf: it throws on a null element, before the grants are validated with a message.
		grants = grants == null ? List.of() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(grants));
	}
}
