package com.garganttua.api.core.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.definition.IDtoDefinition;
import com.garganttua.api.commons.entity.EntityWriteOutcome;
import com.garganttua.api.commons.service.WrittenFields;
import com.garganttua.core.mapper.annotations.FieldMappingRule;
import com.garganttua.core.observability.Logger;
import com.garganttua.core.reflection.IClass;
import com.garganttua.core.reflection.IField;
import com.garganttua.core.reflection.ObjectAddress;

/**
 * Renders a write outcome in the CLIENT's vocabulary: entity field addresses become the DTO field
 * names the caller actually sent.
 *
 * <p>
 * A {@code @FieldMappingRule} on a DTO field names the ENTITY field it reads from, so the mapping
 * is read backwards here — DTO field name keyed by entity address. A field the DTO does not carry
 * at all has no name the client would recognise and is therefore left out: naming it would tell the
 * caller about a field it never sent.
 * </p>
 *
 * <p>
 * The DTO used is the domain's FIRST, the same one {@code SerializationExpressions} renders the
 * response body with — so the names reported are the names of the body the caller receives.
 * </p>
 */
public final class WrittenFieldsTranslator {

    private static final Logger log = Logger.getLogger(WrittenFieldsTranslator.class);

    private WrittenFieldsTranslator() {
    }

    /** {@return the outcome rendered with DTO field names}, or {@link WrittenFields#none()}. */
    public static WrittenFields translate(IDomain<?> domain, EntityWriteOutcome outcome) {
        if (outcome == null || domain == null) {
            return WrittenFields.none();
        }
        Map<String, String> wireNames = wireNamesByEntityAddress(domain);
        return new WrittenFields(rename(outcome.applied(), wireNames), rename(outcome.rejected(), wireNames));
    }

    private static List<String> rename(List<ObjectAddress> addresses, Map<String, String> wireNames) {
        List<String> out = new ArrayList<>(addresses.size());
        for (ObjectAddress address : addresses) {
            String wireName = wireNames.get(address.toString());
            if (wireName != null) {
                out.add(wireName);
            }
        }
        return out;
    }

    /**
     * {@code entity field address -> DTO field name}, built from the first DTO's mapping rules. An
     * unmappable DTO yields an empty map rather than a failure: this is a diagnostic header, and it
     * must never be the reason a write fails to answer.
     */
    private static Map<String, String> wireNamesByEntityAddress(IDomain<?> domain) {
        List<? extends IDtoDefinition<?>> dtos = domain.getDomainDefinition().dtoDefinitions();
        if (dtos == null || dtos.isEmpty()) {
            return Map.of();
        }
        IClass<?> dtoClass = dtos.get(0).dtoClass();
        Map<String, String> wireNames = new LinkedHashMap<>();
        try {
            IClass<?> current = dtoClass;
            while (current != null) {
                for (IField field : current.getDeclaredFields()) {
                    collectWireName(field, domain, wireNames);
                }
                current = current.getSuperclass();
            }
        } catch (RuntimeException e) {
            log.debug("Could not read the mapping rules of DTO {}: {}", dtoClass.getName(), e.getMessage());
            return Map.of();
        }
        return wireNames;
    }

    private static void collectWireName(IField dtoField, IDomain<?> domain, Map<String, String> wireNames) {
        FieldMappingRule[] rules =
                dtoField.getAnnotationsByType(IClass.getClass(FieldMappingRule.class));
        if (rules == null || rules.length == 0) {
            // No rule: the mapper falls back to matching field names, so the DTO name IS the
            // entity name.
            wireNames.putIfAbsent(dtoField.getName(), dtoField.getName());
            return;
        }
        FieldMappingRule applicable = mostSpecific(rules, domain.getEntityClass());
        if (applicable != null) {
            wireNames.putIfAbsent(applicable.sourceFieldAddress(), dtoField.getName());
        }
    }

    /**
     * The rule that governs this entity: an exact {@code source()} match wins over a wildcard
     * ({@code void.class}), mirroring how {@code MappingRules.parse} resolves them.
     */
    private static FieldMappingRule mostSpecific(FieldMappingRule[] rules, IClass<?> entityClass) {
        FieldMappingRule wildcard = null;
        for (FieldMappingRule rule : rules) {
            Class<?> source = rule.source();
            if (source == null || source == void.class) {
                wildcard = wildcard == null ? rule : wildcard;
                continue;
            }
            if (entityClass != null && entityClass.represents(source)) {
                return rule;
            }
        }
        return wildcard;
    }
}
