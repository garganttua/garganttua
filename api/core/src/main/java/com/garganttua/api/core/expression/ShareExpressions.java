package com.garganttua.api.core.expression;

import static com.garganttua.api.core.expression.ExpressionUtils.REFLECTION;
import static com.garganttua.api.core.expression.ExpressionUtils.toDomain;
import static com.garganttua.api.core.expression.ExpressionUtils.unwrapOptional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.entity.ShareGrant;
import com.garganttua.api.core.entity.ShareRequest;
import com.garganttua.core.expression.annotations.Expression;
import com.garganttua.core.reflection.ObjectAddress;
import com.garganttua.core.reflection.annotations.Reflected;

/**
 * The grants field of a shared entity ({@code .shares(field)}): written by the {@code share}
 * operation, and by nothing a client sends through create or update.
 *
 * <p>
 * A grantee holding a write grant may change the entity, so the update path must not let it change
 * the grants themselves: it could lend the entity further, or take back the owner's own grants.
 * {@link #guardOnCreate} and {@link #guardOnUpdate} keep a client write away from the field; a
 * framework-internal write (seed, upsert, {@code SecurityExpressions.invoke*}) passes untouched.
 * </p>
 */
@Reflected(queryAllPublicMethods = true)
public final class ShareExpressions {

	private ShareExpressions() {
	}

	@Expression(name = "applyShareGrants", description = "Replaces the grants of a shared entity with those of a ShareRequest (or a list of tokens). Every token is validated (r|w:tenant:<id> or r|w:owner:<id>) and duplicates are dropped, first one kept. Throws on a malformed token or a domain without .shares(field).")
	public static Object applyShareGrants(Object storedEntity, Object grants, Object context) {
		Object entity = unwrapOptional(storedEntity);
		IDomain<?> domain = toDomain(context);
		ObjectAddress field = sharesField(domain);
		if (field == null) {
			throw new ApiException("Domain '" + domain.getDomainName() + "' declares no grants field: "
					+ "declare one with .shares(field) to share its entities");
		}
		List<String> tokens = validated(tokensOf(unwrapOptional(grants)));
		try {
			REFLECTION.setFieldValue(entity, field.toString(), tokens);
		} catch (Exception e) {
			throw new ApiException("Failed to write the grants of entity in domain '" + domain.getDomainName() + "'", e);
		}
		return entity;
	}

	/** Drops the grants a client put in the body of a create: only the share operation writes them. */
	static void guardOnCreate(Object entity, IDomain<?> domain, Object request) {
		ObjectAddress field = sharesField(domain);
		if (field == null || entity == null || EntityUpdateSupport.isFrameworkInternalWrite(request)) {
			return;
		}
		write(entity, field, null);
	}

	/** Reads the grants before a client update merges the body, for {@link #guardOnUpdate}. */
	static Object grantsBefore(Object storedEntity, IDomain<?> domain) {
		ObjectAddress field = sharesField(domain);
		if (field == null || storedEntity == null) {
			return null;
		}
		try {
			return REFLECTION.getFieldValue(storedEntity, field.toString());
		} catch (Exception e) {
			throw new ApiException("Failed to read the grants of entity in domain '" + domain.getDomainName() + "'", e);
		}
	}

	/** Puts back the grants a client update may have overwritten. */
	static void guardOnUpdate(Object mergedEntity, Object grantsBefore, IDomain<?> domain, Object request) {
		ObjectAddress field = sharesField(domain);
		if (field == null || mergedEntity == null || EntityUpdateSupport.isFrameworkInternalWrite(request)) {
			return;
		}
		write(mergedEntity, field, grantsBefore);
	}

	private static ObjectAddress sharesField(IDomain<?> domain) {
		return domain.getDomainDefinition().shares();
	}

	private static void write(Object entity, ObjectAddress field, Object value) {
		try {
			REFLECTION.setFieldValue(entity, field.toString(), value);
		} catch (Exception e) {
			throw new ApiException("Failed to guard the grants field " + field, e);
		}
	}

	private static Collection<?> tokensOf(Object grants) {
		if (grants instanceof ShareRequest request) {
			return request.grants() == null ? List.of() : request.grants();
		}
		if (grants instanceof Collection<?> collection) {
			return collection;
		}
		if (grants == null) {
			return List.of();
		}
		throw new ApiException("A share request carries a list of grants, got " + grants.getClass().getSimpleName());
	}

	private static List<String> validated(Collection<?> raw) {
		Set<String> tokens = new LinkedHashSet<>();
		for (Object token : raw) {
			tokens.add(ShareGrant.parse(token == null ? null : token.toString()).token());
		}
		return new ArrayList<>(tokens);
	}
}
