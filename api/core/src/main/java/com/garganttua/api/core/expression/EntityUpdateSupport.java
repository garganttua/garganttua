package com.garganttua.api.core.expression;

import java.util.List;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.entity.EntityUpdateRule;
import com.garganttua.api.commons.service.IOperationRequest;
import com.garganttua.api.core.entity.EntityDefinition;
import com.garganttua.api.core.mapper.DefaultMapper;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.IReflection;

/**
 * Update-semantics helpers split out of {@link EntityLifecycleExpressions}: how the incoming request
 * colours the domain's update whitelist (full vs partial body), and the unguarded merge the framework
 * uses when it writes on its own behalf. Extracted to keep that expression registry under the
 * file-size gate; behaviour is identical.
 */
final class EntityUpdateSupport {

	private EntityUpdateSupport() {
	}

	/**
	 * The domain's update whitelist, read through the semantics of the incoming request: as
	 * declared for a full update (PUT), or with every rule forced to {@code ignoreNull} for a
	 * partial one (PATCH). The authority carried by each rule is never altered — a partial body
	 * changes what a {@code null} means, never who may write the field.
	 */
	static List<EntityUpdateRule> updateRules(EntityDefinition<?> entityDef, boolean partial) {
		List<EntityUpdateRule> declared = entityDef.updates();
		if (!partial || declared == null || declared.isEmpty()) {
			return declared;
		}
		return declared.stream().map(EntityUpdateRule::ignoringNull).toList();
	}

	/**
	 * True when the transport flagged the body as carrying only the fields the client means to
	 * change (HTTP PATCH). Like the framework-internal marker, it is server-set only — a client
	 * request can never carry it.
	 */
	static boolean isPartialUpdate(Object request) {
		return flag(request, IOperationRequest.PARTIAL_UPDATE.name());
	}

	/**
	 * True when this request was issued by {@code SecurityExpressions.invokeInternal}
	 * — the framework writing on its own behalf (token persist, key auto-create,
	 * key rotation). The flag is set server-side only; a client-issued request can
	 * never carry it.
	 */
	static boolean isFrameworkInternalWrite(Object request) {
		return flag(request, SecurityExpressions.FRAMEWORK_INTERNAL_WRITE_ARG);
	}

	/** Reads a boolean marker off the operation request, false when absent or not a request. */
	private static boolean flag(Object request, String argName) {
		Object req = ExpressionUtils.unwrapOptional(request);
		if (!(req instanceof IOperationRequest opRequest)) {
			return false;
		}
		return Boolean.TRUE.equals(opRequest.arg(argName).orElse(null));
	}

	/**
	 * Merges every non-null field of {@code updatedEntity} onto {@code storedEntity},
	 * with no authority gate. Null fields are SKIPPED — the same null-means-untouched
	 * rule a partial update applies — so a framework write that only carries part of the
	 * entity (e.g. a startup upsert declaring name but not email) never wipes data it did
	 * not mean to touch.
	 */
	static Object mergeAllNonNullFields(Object storedEntity, Object updatedEntity) {
		if (storedEntity == null || updatedEntity == null) {
			return storedEntity == null ? updatedEntity : storedEntity;
		}
		if (!storedEntity.getClass().equals(updatedEntity.getClass())) {
			throw new ApiException("updateEntity: stored entity type [" + storedEntity.getClass().getSimpleName()
					+ "] and updated entity type [" + updatedEntity.getClass().getSimpleName() + "] mismatch");
		}
		IReflection reflection = DefaultMapper.reflection();
		for (IField field : IClass.getClass(storedEntity.getClass()).getDeclaredFields()) {
			String fieldName = field.getName();
			Object updatedValue = reflection.getFieldValue(updatedEntity, fieldName);
			if (updatedValue != null) {
				reflection.setFieldValue(storedEntity, fieldName, updatedValue);
			}
		}
		return storedEntity;
	}
}
