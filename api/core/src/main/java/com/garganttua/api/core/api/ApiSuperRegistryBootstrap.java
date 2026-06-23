package com.garganttua.api.core.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.repository.IRepository;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IReflection;
import com.garganttua.core.reflection.ObjectAddress;

/**
 * Startup-time super-tenant / super-owner registry bootstrap for {@link Api}. Extracted from
 * {@code Api} to keep that context under the file-size gate; it owns the (a) scan that registers
 * every persisted entity carrying a {@code superTenant} / {@code superOwner} flag, and (b) the
 * direct-write auto-creation of the configured master tenant. Operates on the owning {@link Api}
 * via its public registry methods; all behaviour is identical to the former inline implementation.
 */
final class ApiSuperRegistryBootstrap {

	private static final Logger log = Logger.getLogger(ApiSuperRegistryBootstrap.class);

	private final Api api;

	ApiSuperRegistryBootstrap(Api api) {
		this.api = api;
	}

	/**
	 * Scans the tenant and owner domains and registers every entity whose
	 * {@code superTenant} / {@code superOwner} field is {@code true}. The id registered is the
	 * entity's own uuid (a tenant member carries that uuid as its {@code tenantId}; an owned
	 * entity carries its owner's uuid as its {@code ownerId} — so registry membership is checked
	 * against the caller's tenantId / ownerId at request time, see {@code SecurityExpressions}).
	 */
	void scanSuperRegistries() {
		IReflection reflection = this.api.reflection();
		for (IDomain<?> domain : this.api.getDomains().values()) {
			var def = domain.getDomainDefinition();
			if (def == null) {
				continue;
			}
			boolean isTenant = domain.isTenantEntity() && def.superTenant() != null;
			boolean isOwner = def.owner() != null && def.superOwner() != null;
			if (!isTenant && !isOwner) {
				continue;
			}
			scanDomainForSupers(domain, reflection, isTenant, isOwner);
		}
	}

	private void scanDomainForSupers(IDomain<?> domain, IReflection reflection, boolean isTenant, boolean isOwner) {
		var def = domain.getDomainDefinition();
		ObjectAddress uuidAddress = domain.getEntityDefinition().uuid();
		List<Object> entities;
		try {
			entities = domain.getRepository().getEntities(Optional.empty(), Optional.empty(), Optional.empty());
		} catch (RuntimeException e) {
			log.warn("Could not scan domain '{}' for super-status: {}", domain.getDomainName(), e.getMessage());
			return;
		}
		for (Object entity : entities) {
			String uuid = readStringField(reflection, entity, uuidAddress);
			if (uuid == null) {
				continue;
			}
			if (isTenant && readBooleanField(reflection, entity, def.superTenant())) {
				this.api.registerSuperTenant(uuid);
				log.info("Registered super-tenant '{}' (scanned from domain '{}')", uuid, domain.getDomainName());
			}
			if (isOwner && readBooleanField(reflection, entity, def.superOwner())) {
				this.api.registerSuperOwner(uuid);
				log.info("Registered super-owner '{}' (scanned from domain '{}')", uuid, domain.getDomainName());
			}
		}
	}

	static String readStringField(IReflection reflection, Object entity, ObjectAddress addr) {
		if (addr == null) {
			return null;
		}
		Object v = reflection.getFieldValue(entity, addr.toString());
		return v != null ? v.toString() : null;
	}

	static boolean readBooleanField(IReflection reflection, Object entity, ObjectAddress addr) {
		if (addr == null) {
			return false;
		}
		Object v = reflection.getFieldValue(entity, addr.toString());
		return Boolean.TRUE.equals(v);
	}

	/**
	 * Bootstraps the master tenant row by writing it directly to the tenant domain's repository —
	 * <strong>without</strong> going through the public workflow pipeline. This is a deliberate
	 * framework-internal operation: it runs synchronously during {@code onStart()}, before any user
	 * traffic, so there is no caller to authorize; going through {@code Domain.invoke()} would force
	 * it through {@code VERIFY_AUTHORIZATION}, which historically required a super-tenant
	 * short-circuit that was a security flaw. Consequence: {@code @EntityBeforeCreate} /
	 * {@code @EntityAfterCreate} hooks do NOT fire for the master row (intentional — hook on the
	 * tenant domain's {@code onStart()} instead).
	 */
	void autoCreateMasterTenant(IDomain<?> tenantDomain, String superTenantId) {
		log.info("Auto-creating master tenant with id '{}'", superTenantId);

		IRepository repository = tenantDomain.getRepository();
		if (repository.doesExist(superTenantId)) {
			// Already created on a previous run. Re-register it as super: its status must not depend
			// on the persisted superTenant boolean surviving the entity→DTO mapping. Existence of
			// the master IS the signal.
			this.api.registerSuperTenant(superTenantId);
			log.info("Master tenant '{}' already exists — re-registered as super, skipping auto-creation",
					superTenantId);
			return;
		}

		try {
			Object tenantEntity = buildMasterTenantEntity(tenantDomain, superTenantId);
			this.api.registerSuperTenant(superTenantId);
			persistMasterTenant(tenantDomain, repository, tenantEntity, superTenantId);
		} catch (ReflectiveOperationException | RuntimeException e) {
			log.warn("Could not auto-create master tenant '{}': {}. "
					+ "Consider registering the master tenant entity via .upsert(...) on the tenant domain builder.",
					superTenantId, e.getMessage());
		}
	}

	private Object buildMasterTenantEntity(IDomain<?> tenantDomain, String superTenantId)
			throws ReflectiveOperationException {
		// Build a minimal entity via reflection. UUID = superTenantId; the tenantId field is set to
		// the same value only if the entity carries one (a tenant entity may legitimately omit it —
		// its uuid plays that role downstream, see RepositoryFilterTools.buildTenantFilter).
		IClass<?> entityClass = tenantDomain.getEntityClass();
		Object tenantEntity = entityClass.getConstructor().newInstance();

		IReflection reflection = this.api.reflection();
		ObjectAddress uuidAddress = tenantDomain.getEntityDefinition().uuid();
		reflection.setFieldValue(tenantEntity, uuidAddress, superTenantId);

		ObjectAddress tenantIdAddress = tenantDomain.getTenantIdFieldAddress();
		if (tenantIdAddress != null) {
			reflection.setFieldValue(tenantEntity, tenantIdAddress, superTenantId);
		}

		// The master tenant IS a super-tenant. Stamp its superTenant field true so the row is
		// self-describing (a later startup scan re-discovers it).
		ObjectAddress superTenantAddress = tenantDomain.getDomainDefinition().superTenant();
		if (superTenantAddress != null) {
			reflection.setFieldValue(tenantEntity, superTenantAddress, Boolean.TRUE);
		}
		return tenantEntity;
	}

	private void persistMasterTenant(IDomain<?> tenantDomain, IRepository repository,
			Object tenantEntity, String superTenantId) {
		IReflection reflection = this.api.reflection();
		ObjectAddress uuidAddress = tenantDomain.getEntityDefinition().uuid();
		ObjectAddress superTenantAddress = tenantDomain.getDomainDefinition().superTenant();

		// Direct repository write — no workflow, no security pipeline, no lifecycle hooks.
		repository.save(tenantEntity);

		// Sanity check: confirm the entity actually landed.
		if (repository.doesExist(superTenantId)) {
			log.info("Master tenant '{}' auto-created successfully", superTenantId);
			warnIfSuperTenantStampDropped(repository, reflection, uuidAddress, superTenantAddress, tenantDomain, superTenantId);
		} else {
			log.error("repository.save returned but master tenant '{}' is not present in the "
					+ "repository afterwards. Consider registering the master tenant entity "
					+ "via .upsert(...) on the tenant domain builder.", superTenantId);
		}
	}

	/**
	 * Best-effort diagnostic: re-reads the just-persisted master tenant and warns when its
	 * {@code superTenant} boolean did not survive the entity→DTO mapping (the symptom of a DTO that
	 * omits the {@code superTenant} field). The configured super-tenant is registered unconditionally
	 * at startup, so it still functions; but the persisted row is not self-describing.
	 */
	private void warnIfSuperTenantStampDropped(IRepository repository, IReflection reflection,
			ObjectAddress uuidAddress, ObjectAddress superTenantAddress, IDomain<?> tenantDomain, String superTenantId) {
		if (superTenantAddress == null) {
			return;
		}
		try {
			Object reread = repository.getEntities(Optional.empty(), Optional.empty(), Optional.empty()).stream()
					.filter(e -> superTenantId.equals(readStringField(reflection, e, uuidAddress)))
					.findFirst().orElse(null);
			if (reread != null && !readBooleanField(reflection, reread, superTenantAddress)) {
				log.warn("Master tenant '{}' was stamped superTenant=true but the persisted value did not "
						+ "survive persistence — the DTO for domain '{}' does not carry the superTenant "
						+ "field. The configured super-tenant is still registered at startup, so it "
						+ "functions correctly; declare a matching superTenant field on the DTO to make "
						+ "the persisted row self-describing and visible on reads.",
						superTenantId, tenantDomain.getDomainName());
			}
		} catch (RuntimeException e) {
			// Diagnostic only — never let it disturb startup.
			log.trace("Super-tenant stamp diagnostic skipped: {}", e.getMessage());
		}
	}
}
