package com.garganttua.api.core.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.core.domain.Domain;

/**
 * Builds the human-readable startup summary ({@code ISummaryContributor} items) for an {@link Api}.
 * Extracted from {@code Api} to keep that context under the file-size gate; pure read-only
 * formatting of the already-built domain contexts.
 */
final class ApiSummary {

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
