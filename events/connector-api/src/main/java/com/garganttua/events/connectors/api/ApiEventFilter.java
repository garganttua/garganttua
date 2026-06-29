package com.garganttua.events.connectors.api;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.garganttua.api.commons.event.IEvent;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.operation.OperationDefinition;
import com.garganttua.core.observability.Logger;

/**
 * In-memory evaluator of an {@link IFilter} tree against an api business {@link IEvent}.
 *
 * <p>It lets an application restrict which events the {@link ApiEventsConnector} forwards — most
 * usefully by <b>operation</b> (e.g. only {@code create}/{@code update}/{@code readAll}), but also by
 * domain, response code, tenant/owner/user identity and arbitrary {@code in}/{@code out} payload
 * fields. The filter tree is the {@code $}-prefixed structure produced by the api-core
 * {@code Filter} factories (e.g. {@code Filter.in("operation","create","update","readAll")}):</p>
 *
 * <ul>
 *   <li>{@code $and}/{@code $or}/{@code $nor} — boolean combinators over their sub-filters (all /
 *       any / none must match);</li>
 *   <li>{@code $field} — carries the logical field name in {@link IFilter#getValue()} and exactly one
 *       operator sub-filter; the field is resolved to the event's value, then the operator is
 *       evaluated against it;</li>
 *   <li>operator leaves {@code $eq}/{@code $ne}/{@code $gt}/{@code $gte}/{@code $lt}/{@code $lte}/
 *       {@code $regex}/{@code $empty}/{@code $in}/{@code $nin} — compare the resolved field value
 *       against the operator's value (or value-only sub-literals for {@code $in}/{@code $nin}).</li>
 * </ul>
 *
 * <p><b>Field-name mapping</b> (case-insensitive):</p>
 * <ul>
 *   <li>{@code operation} / {@code businessOperation} → the {@code BusinessOperation} name of
 *       {@link IEvent#getOperation()} (e.g. {@code create}, {@code update}, {@code readAll});</li>
 *   <li>{@code technicalOperation} → the technical-operation name;</li>
 *   <li>{@code domain} / {@code domainName} → the operation domain name;</li>
 *   <li>{@code code} → {@link IEvent#getCode()} name;</li>
 *   <li>{@code tenantId} / {@code ownerId} / {@code userId} → the respective getters;</li>
 *   <li>a dotted {@code in.<field>} / {@code out.<field>} → reflected best-effort from
 *       {@link IEvent#getIn()} / {@link IEvent#getOut()} (unresolved → {@code null}).</li>
 * </ul>
 *
 * <p>Comparison is by string form for robustness (enum vs string), with numeric comparison for the
 * ordering operators when both sides parse as numbers. The evaluator is <b>defensive</b>: it never
 * throws — a {@code null} filter passes everything, and any malformed/unknown shape logs at debug
 * and evaluates to {@code false} rather than crashing the pipeline.</p>
 */
final class ApiEventFilter {

	private static final Logger LOG = Logger.getLogger(ApiEventFilter.class);

	static final String OP_AND = "$and";
	static final String OP_OR = "$or";
	static final String OP_NOR = "$nor";
	static final String OP_FIELD = "$field";
	static final String OP_EQ = "$eq";
	static final String OP_NE = "$ne";
	static final String OP_GT = "$gt";
	static final String OP_GTE = "$gte";
	static final String OP_LT = "$lt";
	static final String OP_LTE = "$lte";
	static final String OP_REGEX = "$regex";
	static final String OP_EMPTY = "$empty";
	static final String OP_IN = "$in";
	static final String OP_NIN = "$nin";

	private ApiEventFilter() {
	}

	/**
	 * Returns a defensive copy of the given filter tree, or {@code null} when none is supplied. The
	 * {@link IFilter} tree is mutable, so callers snapshot it to stay immune to later mutation.
	 *
	 * @param filter the filter to copy, or {@code null}
	 * @return a clone of {@code filter}, or {@code null} when {@code filter} is {@code null}
	 */
	static IFilter snapshot(IFilter filter) {
		if (filter == null) {
			return null;
		}
		return filter.clone();
	}

	/**
	 * Evaluate {@code filter} against {@code event}.
	 *
	 * @param filter the filter tree, or {@code null} to pass everything
	 * @param event  the business event under test
	 * @return {@code true} when the event matches (or no filter is set); {@code false} otherwise,
	 *         including on any malformed filter — this method never throws
	 */
	static boolean matches(IFilter filter, IEvent event) {
		if (filter == null) {
			return true;
		}
		try {
			return evaluate(filter, event);
		} catch (RuntimeException e) {
			LOG.debug("Api event filter evaluation failed, dropping event: {}", e.getMessage());
			return false;
		}
	}

	private static boolean evaluate(IFilter filter, IEvent event) {
		String name = filter.getName();
		if (name == null) {
			return false;
		}
		return switch (name) {
			case OP_AND -> all(filter.getFilters(), event);
			case OP_OR -> any(filter.getFilters(), event);
			case OP_NOR -> none(filter.getFilters(), event);
			case OP_FIELD -> evaluateField(filter, event);
			default -> {
				LOG.debug("Unknown api event filter operator {}", name);
				yield false;
			}
		};
	}

	private static boolean all(List<IFilter> filters, IEvent event) {
		return filters != null && !filters.isEmpty() && filters.stream().allMatch(f -> evaluate(f, event));
	}

	private static boolean any(List<IFilter> filters, IEvent event) {
		return filters != null && filters.stream().anyMatch(f -> evaluate(f, event));
	}

	private static boolean none(List<IFilter> filters, IEvent event) {
		return filters != null && filters.stream().noneMatch(f -> evaluate(f, event));
	}

	private static boolean evaluateField(IFilter field, IEvent event) {
		List<IFilter> ops = field.getFilters();
		if (ops == null || ops.size() != 1 || !(field.getValue() instanceof String name)) {
			return false;
		}
		Object resolved = resolveField(name, event);
		return compareLeaf(ops.get(0), resolved);
	}

	private static boolean compareLeaf(IFilter leaf, Object resolved) {
		String op = leaf.getName();
		if (op == null) {
			return false;
		}
		return switch (op) {
			case OP_EQ -> resolved != null && stringEquals(resolved, leaf.getValue());
			case OP_NE -> resolved == null || !stringEquals(resolved, leaf.getValue());
			case OP_EMPTY -> resolved == null || resolved.toString().isBlank();
			case OP_REGEX -> resolved != null && leaf.getValue() != null
					&& resolved.toString().matches(leaf.getValue().toString());
			case OP_IN -> resolved != null && inSet(leaf.getFilters(), resolved);
			case OP_NIN -> resolved == null || !inSet(leaf.getFilters(), resolved);
			case OP_GT -> compareOrdering(resolved, leaf.getValue()) > 0;
			case OP_GTE -> compareOrdering(resolved, leaf.getValue()) >= 0;
			case OP_LT -> compareOrdering(resolved, leaf.getValue()) < 0;
			case OP_LTE -> compareOrdering(resolved, leaf.getValue()) <= 0;
			default -> {
				LOG.debug("Unknown api event filter leaf operator {}", op);
				yield false;
			}
		};
	}

	private static boolean stringEquals(Object resolved, Object value) {
		return value != null && resolved.toString().equals(value.toString());
	}

	private static boolean inSet(List<IFilter> literals, Object resolved) {
		if (literals == null) {
			return false;
		}
		String text = resolved.toString();
		return literals.stream()
				.map(IFilter::getValue)
				.filter(java.util.Objects::nonNull)
				.anyMatch(value -> value.toString().equals(text));
	}

	private static int compareOrdering(Object resolved, Object value) {
		if (resolved == null || value == null) {
			// A null operand never satisfies an ordering operator: force a non-zero, non-matching result.
			return Integer.MIN_VALUE;
		}
		Optional<Double> left = asNumber(resolved);
		Optional<Double> right = asNumber(value);
		if (left.isPresent() && right.isPresent()) {
			return Double.compare(left.get(), right.get());
		}
		return resolved.toString().compareTo(value.toString());
	}

	private static Optional<Double> asNumber(Object value) {
		try {
			return Optional.of(Double.parseDouble(value.toString().trim()));
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	private static Object resolveField(String name, IEvent event) {
		String key = name.toLowerCase(Locale.ROOT);
		OperationDefinition operation = event.getOperation();
		return switch (key) {
			case "operation", "businessoperation" -> businessOperationName(operation);
			case "technicaloperation" -> operation == null || operation.technicalOperation() == null
					? null : operation.technicalOperation().name();
			case "domain", "domainname" -> operation == null ? null : operation.domainName();
			case "code" -> event.getCode() == null ? null : event.getCode().name();
			case "tenantid" -> event.getTenantId();
			case "ownerid" -> event.getOwnerId();
			case "userid" -> event.getUserId();
			default -> resolveDotted(name, event);
		};
	}

	private static String businessOperationName(OperationDefinition operation) {
		if (operation == null || operation.getBusinessOperation() == null) {
			return null;
		}
		return operation.getBusinessOperation().name();
	}

	private static Object resolveDotted(String name, IEvent event) {
		int dot = name.indexOf('.');
		if (dot <= 0 || dot == name.length() - 1) {
			return null;
		}
		String root = name.substring(0, dot).toLowerCase(Locale.ROOT);
		String property = name.substring(dot + 1);
		Object payload = switch (root) {
			case "in" -> event.getIn();
			case "out" -> event.getOut();
			default -> null;
		};
		return readProperty(payload, property);
	}

	private static Object readProperty(Object target, String property) {
		if (target == null) {
			return null;
		}
		String getter = "get" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
		return invokeGetter(target, getter)
				.or(() -> invokeGetter(target, property))
				.orElse(null);
	}

	private static Optional<Object> invokeGetter(Object target, String accessor) {
		try {
			Method method = target.getClass().getMethod(accessor);
			return Optional.ofNullable(method.invoke(target));
		} catch (ReflectiveOperationException | RuntimeException e) {
			return Optional.empty();
		}
	}
}
