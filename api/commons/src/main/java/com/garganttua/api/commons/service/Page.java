package com.garganttua.api.commons.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-all result page: the matching entities plus the total count of entities
 * matching the query (ignoring pagination). The {@code entities} list is
 * defensively copied on construction and exposed as an unmodifiable view, so a
 * {@code Page} is a true immutable value object.
 */
public record Page(long totalCount, List<Object> entities) {

	public Page {
		entities = (entities == null)
				? Collections.emptyList()
				: Collections.unmodifiableList(new ArrayList<>(entities));
	}

	@Override
	public List<Object> entities() {
		return Collections.unmodifiableList(entities);
	}

}
