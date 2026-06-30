package com.garganttua.events.connectors.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.garganttua.api.commons.filter.IFilter;

/**
 * Connector-local, immutable {@link IFilter} node used to carry a config-supplied filter tree.
 *
 * <p>It mirrors the JSON shape the api-core {@code Filter} serialises to — {@code name},
 * {@code value} and a {@code literals} sub-filter list — so a user can serialise a {@code Filter}
 * with a Jackson {@code ObjectMapper} and paste the result into a connector's {@code filter} config
 * key (see {@link JsonFilterParser}). Holding this lightweight impl in {@code connector-api} means
 * the connector reads a declarative filter tree <b>without a compile-time dependency on api-core</b>:
 * its main classpath only ever sees the {@link IFilter} interface (api-commons) plus Jackson.</p>
 *
 * <p>The evaluator ({@link ApiEventFilter}) only ever reads {@link #getName()}, {@link #getValue()}
 * and {@link #getFilters()}; the mutating contract methods are therefore implemented as defensive
 * no-ops / unsupported operations — this node is immutable and never structurally edited after
 * parsing.</p>
 */
public final class JsonFilter implements IFilter {

	private final String name;
	private Object value;
	private final List<IFilter> filters;

	/**
	 * Builds an immutable filter node.
	 *
	 * @param name    the operator name (e.g. {@code $field}, {@code $in}, {@code $and}), or
	 *                {@code null} for a value-only literal (the {@code $in}/{@code $nin} members)
	 * @param value   the literal value, or {@code null}
	 * @param filters the sub-filters; a {@code null} or empty list yields an empty, unmodifiable list
	 */
	public JsonFilter(String name, Object value, List<IFilter> filters) {
		this.name = name;
		this.value = value;
		this.filters = filters == null
				? Collections.emptyList()
				: Collections.unmodifiableList(new ArrayList<>(filters));
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public Object getValue() {
		return this.value;
	}

	/**
	 * Sets the carried value. Retained for the {@link IFilter} contract; the evaluator never calls it.
	 *
	 * @param value the new value
	 */
	@Override
	public void setValue(Object value) {
		this.value = value;
	}

	@Override
	public List<IFilter> getFilters() {
		// Defensive copy: never hand out the internal list, even though it is unmodifiable, so callers
		// cannot rely on identity and the node stays fully encapsulated.
		return new ArrayList<>(this.filters);
	}

	/**
	 * Unsupported: a {@code JsonFilter} tree is immutable once parsed.
	 *
	 * @param valuesFilters ignored
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public void setFilters(List<IFilter> valuesFilters) {
		throw new UnsupportedOperationException("JsonFilter is immutable");
	}

	/**
	 * No-op: a {@code JsonFilter} tree is immutable, so sub-filters are never removed.
	 *
	 * @param filter ignored
	 */
	@Override
	public void removeSubFilter(IFilter filter) {
		// immutable: nothing to remove
	}

	/**
	 * No-op: a {@code JsonFilter} tree is immutable, so sub-filters are never replaced.
	 *
	 * @param literal      ignored
	 * @param mappedFilter ignored
	 */
	@Override
	public void replaceSubFilter(IFilter literal, IFilter mappedFilter) {
		// immutable: nothing to replace
	}

	/**
	 * Returns a deep copy of this node. The copy is itself immutable.
	 *
	 * @return a clone of this filter tree
	 */
	@Override
	public JsonFilter clone() {
		List<IFilter> clonedChildren = new ArrayList<>(this.filters.size());
		for (IFilter child : this.filters) {
			clonedChildren.add(child.clone());
		}
		return new JsonFilter(this.name, this.value, clonedChildren);
	}
}
