package com.garganttua.api.commons.repository;

import java.util.List;
import java.util.Optional;

import com.garganttua.api.commons.filter.IFilter;
import com.garganttua.api.commons.pageable.IPageable;
import com.garganttua.api.commons.service.Page;
import com.garganttua.api.commons.sort.ISort;
import com.garganttua.api.commons.ApiException;

/**
 *
 * @author JérémyCOLOMBET
 *
 */
public interface IRepository {

	boolean doesExist(Object entity) throws ApiException;

	List<Object> getEntities(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort)
			throws ApiException;

	/**
	 * Get entities with an optional field projection (list of ENTITY field names) pushed down to the
	 * DAO for IO savings where supported. The default ignores the projection and delegates to the
	 * 3-arg variant, so callers that never project (unicity checks, lookups) are unaffected.
	 */
	default List<Object> getEntities(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort,
			Optional<List<String>> projection) throws ApiException {
		return getEntities(pageable, filter, sort);
	}

	/**
	 * The entities of a page AND the total its filter matches — the two answers of a paginated read.
	 * Without a page, nothing is counted: the total is {@code -1}. The default reads, then counts; a
	 * repository whose DAO answers both at once ({@code IDao.findPage}) overrides it.
	 *
	 * @param pageable   the page, or empty for every entity
	 * @param filter     the filter
	 * @param sort       the sort
	 * @param projection the entity fields to load
	 * @return the page
	 * @throws ApiException when either cannot be read
	 */
	default Page getEntitiesPage(Optional<IPageable> pageable, Optional<IFilter> filter, Optional<ISort> sort,
			Optional<List<String>> projection) throws ApiException {
		List<Object> entities = getEntities(pageable, filter, sort, projection);
		return new Page(pageable != null && pageable.isPresent() ? getCount(filter.orElse(null)) : -1L, entities);
	}

	void save(Object entity) throws ApiException;

	void delete(Object entity) throws ApiException;

	boolean doesExist(String uuid) throws ApiException;

	long getCount(IFilter filter) throws ApiException;

}
