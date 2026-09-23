package com.garganttua.api.core.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.javatuples.Pair;

import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.definition.IEntityDefinition;
import com.garganttua.api.commons.entity.EntityIndexRule;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.api.core.domain.Domain;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.ObjectAddress;

/**
 * Builds the human-readable startup summary ({@code ISummaryContributor} items) for an {@link Api}.
 * Extracted from {@code Api} to keep that context under the file-size gate; pure read-only
 * formatting of the already-built domain contexts.
 */
final class ApiSummary {

	private static final Logger log = Logger.getLogger(ApiSummary.class);

	private ApiSummary() {
	}

	/** Builds the ordered summary item map for the given API state. */
	static Map<String, String> items(boolean multiTenant, String superTenantId, boolean superTenantAutoCreate,
			Map<String, IDomain<?>> domainContexts) {
		Map<String, String> items = new java.util.LinkedHashMap<>();

		// Global configuration
		String tenancy = multiTenant ? "enabled" : "disabled";
		if (multiTenant && superTenantId != null) {
			tenancy += " (superTenant=" + superTenantId + (superTenantAutoCreate ? ", autoCreate" : "") + ")";
		}
		items.put("Multi-tenancy", tenancy);
		items.put("Domains", String.valueOf(domainContexts.size()));

		java.util.Set<String> daoTypes = new java.util.LinkedHashSet<>();
		int[] counters = {0, 0, 0}; // interfaces, events, securedDomains

		for (Map.Entry<String, IDomain<?>> entry : domainContexts.entrySet()) {
			appendDomainSummary(items, daoTypes, counters, entry.getKey(), entry.getValue());
		}

		appendGlobalSummaries(items, daoTypes, counters, domainContexts.size());
		return items;
	}

	private static void appendDomainSummary(Map<String, String> items, java.util.Set<String> daoTypes,
			int[] counters, String name, IDomain<?> ctx) {
		var def = ctx.getDomainDefinition();
		items.put("Domain '" + name + "'", buildDomainInfoLine(def));

		// DAO
		if (ctx.getRepository() != null) {
			daoTypes.add(ctx.getRepository().getClass().getSimpleName());
		}

		// Operations
		var operations = def.operations();
		if (!operations.isEmpty()) {
			items.put("  operations", operations.stream()
					.map(op -> op.getBusinessOperation().getLabel())
					.collect(Collectors.joining(", ")));
		}

		// Security
		var secDef = (def instanceof com.garganttua.api.core.domain.DomainDefinition<?> dd)
				? dd.domainSecurityDefinition() : null;
		if (secDef != null && !secDef.disabled()) {
			counters[2]++;
			StringBuilder secInfo = new StringBuilder("enabled");
			if (secDef.authenticatorDefinition() != null) {
				secInfo.append(" (authenticator: ").append(secDef.authenticatorDefinition().scope()).append(")");
			}
			items.put("  security", secInfo.toString());
		}

		// Interfaces / Events
		if (ctx instanceof Domain<?> dc) {
			if (dc.getInterfaces() != null) counters[0] += dc.getInterfaces().size();
			if (dc.getEvents() != null) counters[1] += dc.getEvents().size();
		}

		warnUnicitiesWithoutIndex(name, def);
	}

	/**
	 * Names, at assembly, every field the domain declares unique without a matching unique index.
	 *
	 * <p>
	 * Such a constraint is enforced by the framework alone — it reads the collection, then writes.
	 * Two concurrent requests both read "no duplicate" and both write one; nothing in the store
	 * refuses the second. The declaration reads as a guarantee and is not one, which is exactly the
	 * kind of gap that only shows in production, so it is said out loud here rather than trusted.
	 * </p>
	 */
	private static void warnUnicitiesWithoutIndex(String name, IDomainDefinition<?> def) {
		IEntityDefinition<?> entity = def.entityDefinition();
		if (entity == null || entity.unicities() == null) {
			return;
		}
		List<EntityIndexRule> declared = entity.indexes() == null ? List.of() : entity.indexes();
		for (Pair<ObjectAddress, UnicityScope> unicity : entity.unicities()) {
			if (!isBackedByUniqueIndex(declared, unicity)) {
				log.warn("Domain '{}': field '{}' is declared unique with scope {} but carries no matching "
						+ "unique @EntityIndexed — the uniqueness is checked by the framework only (a read, "
						+ "then a write), so two concurrent writes both pass and the database keeps both.",
						name, unicity.getValue0(), unicity.getValue1());
			}
		}
	}

	/** Whether one declared index makes the store itself refuse a duplicate for that unicity. */
	private static boolean isBackedByUniqueIndex(List<EntityIndexRule> declared,
			Pair<ObjectAddress, UnicityScope> unicity) {
		return declared.stream().anyMatch(index -> index.unique()
				&& index.field().equals(unicity.getValue0())
				&& index.scope() == unicity.getValue1());
	}

	private static void appendGlobalSummaries(Map<String, String> items, java.util.Set<String> daoTypes,
			int[] counters, int domainCount) {
		if (!daoTypes.isEmpty()) {
			items.put("DAOs", String.join(", ", daoTypes));
		}
		if (counters[0] > 0) {
			items.put("Interfaces", String.valueOf(counters[0]));
		}
		if (counters[1] > 0) {
			items.put("Event publishers", String.valueOf(counters[1]));
		}
		if (counters[2] > 0) {
			items.put("Secured domains", counters[2] + "/" + domainCount);
		}
	}

	/** Builds the one-line domain summary: {@code Entity -> Dto1, Dto2 [flag, flag]}. */
	private static String buildDomainInfoLine(IDomainDefinition<?> def) {
		StringBuilder domainInfo = new StringBuilder();
		domainInfo.append(def.entityDefinition().entityClass().getSimpleName());

		if (!def.dtoDefinitions().isEmpty()) {
			domainInfo.append(" -> ");
			domainInfo.append(def.dtoDefinitions().stream()
					.map(dto -> dto.dtoClass().getSimpleName())
					.collect(Collectors.joining(", ")));
		}

		List<String> flags = new ArrayList<>();
		if (Boolean.TRUE.equals(def.tenant())) flags.add("tenant");
		if (Boolean.TRUE.equals(def.publik())) flags.add("public");
		if (def.owned() != null) flags.add("owned");
		if (def.shared() != null) flags.add("shared");
		if (def.hiddenable() != null) flags.add("hiddenable");
		if (def.geolocalized() != null) flags.add("geolocalized");
		if (def.superOwner() != null) flags.add("superOwner");
		if (def.superTenant() != null) flags.add("superTenant");
		if (!flags.isEmpty()) {
			domainInfo.append(" [").append(String.join(", ", flags)).append("]");
		}
		return domainInfo.toString();
	}
}
