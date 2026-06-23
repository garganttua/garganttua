package com.garganttua.api.core.entity;

import com.garganttua.api.commons.ApiException;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.ObjectAddress;

/**
 * Build-time required-field validation for {@link EntityBuilder} (id / uuid / tenantId presence),
 * with the developer-facing fix-up messages. Extracted from {@code EntityBuilder} to keep that
 * wide-interface builder under the file-size gate.
 */
final class EntityBuilderValidation {

	private EntityBuilderValidation() {
	}

	static void requireUuid(IClass<?> entityClass, ObjectAddress uuid) {
		if (uuid == null) {
			throw new ApiException("No uuid field declared on entity " + entityClass.getSimpleName()
					+ ". Add .uuid(\"uuid\") (or the matching field name) on the entity builder:\n"
					+ "\n"
					+ "    .domain(" + entityClass.getSimpleName() + ".class)\n"
					+ "        .entity()\n"
					+ "            .id(\"id\")\n"
					+ "            .uuid(\"uuid\")                          // <- missing\n"
					+ "            .tenantId(\"tenantId\")\n"
					+ "        .up()");
		}
	}

	static void requireId(IClass<?> entityClass, ObjectAddress id) {
		if (id == null) {
			throw new ApiException("No id field declared on entity " + entityClass.getSimpleName()
					+ ". Add .id(\"id\") (or the matching field name) on the entity builder:\n"
					+ "\n"
					+ "    .domain(" + entityClass.getSimpleName() + ".class)\n"
					+ "        .entity()\n"
					+ "            .id(\"id\")                              // <- missing\n"
					+ "            .uuid(\"uuid\").tenantId(\"tenantId\")\n"
					+ "        .up()");
		}
	}

	static void requireTenantId(IClass<?> entityClass, ObjectAddress tenantId,
			boolean multiTenantEnabled, boolean tenantDomain) {
		if (tenantId == null && multiTenantEnabled && !tenantDomain) {
			throw new ApiException("No tenantId field declared on entity " + entityClass.getSimpleName()
					+ ". Multi-tenancy is enabled and this domain is not the tenant domain — every entity needs a tenantId. "
					+ "Either add .tenantId(\"tenantId\") on the entity builder, mark this domain as the tenant via .tenant(true), "
					+ "or disable multi-tenancy globally via apiBuilder.multiTenant(false).");
		}
	}
}
