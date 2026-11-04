/*******************************************************************************
 * Copyright (c) 2022 Jérémy COLOMBET
 *******************************************************************************/
package com.garganttua.api.commons.dao;

import java.util.List;
import java.util.Optional;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.definition.IDomainDefinition;
import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.service.Page;
import com.garganttua.api.commons.sort.ISort;

public interface IDao {

	void registerDomain(IDomainDefinition<?> domainDefinition);

	List<Object> find(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort) throws ApiException;

	/**
	 * Find with an optional field projection — the list of ENTITY field names to return. A DAO that
	 * supports it (e.g. MongoDB) narrows the fetched fields for IO savings; the default ignores the
	 * projection and delegates to the 3-arg {@link #find(Optional, Optional, Optional)}, so existing
	 * implementations keep working unchanged. The projection is best-effort and never widens results.
	 */
	default List<Object> find(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort,
			Optional<List<String>> projection) throws ApiException {
		return find(pageable, filter, sort);
	}

	/**
	 * A page of a read, AND the number of entities the filter matches beyond it — what a paginated read
	 * answers. The default makes the two calls it always made, {@link #find(Optional, Optional, Optional,
	 * Optional)} then {@link #count(IFilter)}; a DAO that can answer both in ONE round trip (PostgreSQL:
	 * a {@code count(*) OVER ()} on the page) overrides it.
	 *
	 * @param pageable   the page
	 * @param filter     the filter, the same for the page and the count
	 * @param sort       the sort
	 * @param projection the fields to load, as for {@code find}
	 * @return the page, with the total of the filter
	 * @throws ApiException when either cannot be read
	 */
	default Page findPage(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort,
			Optional<List<String>> projection) throws ApiException {
		List<Object> entities = find(pageable, filter, sort, projection);
		return new Page(count(filter == null ? null : filter.orElse(null)), entities);
	}

	Object save(Object object) throws ApiException;

	void delete(Object object) throws ApiException;
	
	long count(IFilter filter) throws ApiException;
}
