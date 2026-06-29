package com.garganttua.events.connectors.api;

import java.util.List;

import com.garganttua.api.commons.filter.IFilter;

/**
 * A deliberately malformed {@link IFilter} whose top-level operator name is unknown to
 * {@link ApiEventFilter}; used to prove the evaluator returns {@code false} without throwing.
 */
final class MalformedFilter implements IFilter {

	@Override
	public Object getValue() {
		return null;
	}

	@Override
	public void setValue(Object value) {
		// unused
	}

	@Override
	public IFilter clone() {
		return this;
	}

	@Override
	public List<IFilter> getFilters() {
		return List.of();
	}

	@Override
	public String getName() {
		return "$bogusOperator";
	}

	@Override
	public void setFilters(List<IFilter> valuesFilters) {
		// unused
	}

	@Override
	public void removeSubFilter(IFilter filter) {
		// unused
	}

	@Override
	public void replaceSubFilter(IFilter literal, IFilter mappedFilter) {
		// unused
	}
}
