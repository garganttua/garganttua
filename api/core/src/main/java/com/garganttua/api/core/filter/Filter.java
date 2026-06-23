package com.garganttua.api.core.filter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.geojson.GeoJsonObject;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.filter.IFilter;

@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class Filter implements IFilter {

	public static final String OPERATOR_PREFIX = "$";

	public static final String OPERATOR_FIELD = OPERATOR_PREFIX + "field";

	public static final String OPERATOR_EQUAL = OPERATOR_PREFIX + "eq";
	public static final String OPERATOR_NOT_EQUAL = OPERATOR_PREFIX + "ne";
	public static final String OPERATOR_GREATER_THAN = OPERATOR_PREFIX + "gt";
	public static final String OPERATOR_GREATER_THAN_EXCLUSIVE = OPERATOR_PREFIX + "gte";
	public static final String OPERATOR_LOWER_THAN = OPERATOR_PREFIX + "lt";
	public static final String OPERATOR_LOWER_THAN_EXCLUSIVE = OPERATOR_PREFIX + "lte";
	public static final String OPERATOR_REGEX = OPERATOR_PREFIX + "regex";
	public static final String OPERATOR_EMPTY = OPERATOR_PREFIX + "empty";
	public static final String OPERATOR_TEXT = OPERATOR_PREFIX + "text";
	public static final String OPERATOR_GEOLOC = OPERATOR_PREFIX + "geoWithin";
	public static final String OPERATOR_GEOLOC_SPHERE = OPERATOR_PREFIX + "geoWithinSphere";

	public static final String OPERATOR_IN = OPERATOR_PREFIX + "in";
	public static final String OPERATOR_NOT_IN = OPERATOR_PREFIX + "nin";

	public static final String OPERATOR_AND = OPERATOR_PREFIX + "and";
	public static final String OPERATOR_OR = OPERATOR_PREFIX + "or";
	public static final String OPERATOR_NOR = OPERATOR_PREFIX + "nor";

	public static final String CIRCLE_RADIUS = "radius";

	private static List<String> finalOperators = new ArrayList<String>();

	static {
		finalOperators.add(OPERATOR_EQUAL);
		finalOperators.add(OPERATOR_NOT_EQUAL);
		finalOperators.add(OPERATOR_GREATER_THAN);
		finalOperators.add(OPERATOR_GREATER_THAN_EXCLUSIVE);
		finalOperators.add(OPERATOR_LOWER_THAN);
		finalOperators.add(OPERATOR_LOWER_THAN_EXCLUSIVE);
		finalOperators.add(OPERATOR_REGEX);
		finalOperators.add(OPERATOR_EMPTY);
		finalOperators.add(OPERATOR_IN);
		finalOperators.add(OPERATOR_NOT_IN);
		finalOperators.add(OPERATOR_GEOLOC);
		finalOperators.add(OPERATOR_GEOLOC_SPHERE);
	}

	@JsonProperty
	private String name;

	@JsonProperty
	private Object value;

	@JsonProperty
	private List<Filter> literals;

	@Override
	public String getName() { return this.name; }
	@Override
	public Object getValue() { return this.value; }
	@Override
	public void setValue(Object value) { this.value = value; }

	private Filter() {

	}

	private Filter(String operator, Object value, List<Filter> subs) {
		this.name = operator;
		this.value = value;
		this.literals = subs;
	}

	@Override
	public void removeSubFilter(IFilter child) {
		if (literals != null) {
			Iterator<Filter> iterator = literals.iterator();
			while (iterator.hasNext()) {
				IFilter current = iterator.next();
				if (current.equals(child)) {
					iterator.remove();
					break;
				}
			}
		}
	}

	@Override
	public void replaceSubFilter(IFilter actual, IFilter futur) {
		if (literals != null && actual != null && futur != null) {
			this.replaceSubFilter(literals, actual, futur);
		}
	}

	private void replaceSubFilter(List<Filter> literals, IFilter actual, IFilter futur) {
		for (int i = 0; i < literals.size(); i++) {
			Filter subFilter = literals.get(i);
			if (subFilter.equals(actual)) {
				literals.set(i, (Filter) futur);
			} else {
				List<Filter> subFilters = subFilter.literals;
				if (subFilters != null) {
					replaceSubFilter(subFilters, actual, futur);
				}
			}
		}
	}

	public static void validate(Filter literal) throws FilterException {
		if (literal == null) {
			return;
		}

		if (literal.name != null && !literal.name.startsWith(OPERATOR_PREFIX)) {
			throw new FilterException("Invalid literal name, should start with $");
		}
		if (literal.name != null) {
			validateByOperator(literal);
		}

		if (literal.literals != null) {
			literal.literals.forEach(Filter::validate);
		}
	}

	private static void validateByOperator(Filter literal) throws FilterException {
		switch (literal.name) {
			case OPERATOR_EQUAL:
			case OPERATOR_NOT_EQUAL:
			case OPERATOR_GEOLOC:
			case OPERATOR_GEOLOC_SPHERE:
			case OPERATOR_GREATER_THAN:
			case OPERATOR_GREATER_THAN_EXCLUSIVE:
			case OPERATOR_LOWER_THAN:
			case OPERATOR_LOWER_THAN_EXCLUSIVE:
			case OPERATOR_REGEX:
				validateValuedLeaf(literal);
				break;
			case OPERATOR_IN:
			case OPERATOR_NOT_IN:
				validateInOperator(literal);
				break;
			case OPERATOR_TEXT:
				validateTextOperator(literal);
				break;
			case OPERATOR_EMPTY:
				validateEmptyOperator(literal);
				break;
			case OPERATOR_OR:
			case OPERATOR_AND:
			case OPERATOR_NOR:
				validateBooleanOperator(literal);
				break;
			case OPERATOR_FIELD:
				validateFieldOperator(literal);
				break;
			default:
				throw new FilterException("Invalid literal name " + literal.name);
		}
	}

	private static void validateValuedLeaf(Filter literal) throws FilterException {
		if (literal.value == null) {
			throw new FilterException("Value cannot be null with literal of type " + literal.name);
		}
		if (literal.literals != null && !literal.literals.isEmpty()) {
			throw new FilterException("Filter of type " + literal.name + " does not accept sub literals");
		}
	}

	private static void validateInOperator(Filter literal) throws FilterException {
		if (literal.value != null) {
			throw new FilterException("Value must be null with literal of type " + literal.value);
		}
		if (literal.literals == null || literal.literals.isEmpty()) {
			throw new FilterException("Filter of type " + literal.name + " needs at least 1 sub literals");
		}
		for (Filter sub : literal.literals) {
			if (sub.name != null && !sub.name.isEmpty()) {
				throw new FilterException("Filter of type " + literal.name + " cannot have sub literal with a name");
			}
			validatePlainValueSub(literal, sub);
		}
	}

	private static void validateTextOperator(Filter literal) throws FilterException {
		if (literal.value == null) {
			throw new FilterException("Value must not be null with literal of type " + literal.name);
		}
		if (literal.literals == null || literal.literals.isEmpty()) {
			throw new FilterException("Filter of type " + literal.name + " needs at least 1 sub literals");
		}
		for (Filter sub : literal.literals) {
			if (sub.name != null && !sub.name.isEmpty() && !OPERATOR_FIELD.equals(sub.name)) {
				throw new FilterException("Filter of type " + literal.name + " cannot have sub literal other than $field");
			}
			validatePlainValueSub(literal, sub);
		}
	}

	private static void validatePlainValueSub(Filter literal, Filter sub) throws FilterException {
		if (sub.value == null) {
			throw new FilterException("Filter of type " + literal.name + " cannot have sub literal without value");
		}
		if (sub.literals != null && !sub.literals.isEmpty()) {
			throw new FilterException("Filter of type " + literal.name + " cannot have sub literals with sub literals");
		}
	}

	private static void validateEmptyOperator(Filter literal) throws FilterException {
		if (literal.value != null) {
			throw new FilterException("Value must be null with literal of type " + literal.name);
		}
		if (literal.literals != null && !literal.literals.isEmpty()) {
			throw new FilterException("Filter of type " + literal.name + " does not accept sub literals");
		}
	}

	private static void validateBooleanOperator(Filter literal) throws FilterException {
		if (literal.value != null) {
			throw new FilterException("Value must be null with literal of type " + literal.name);
		}
		if (literal.literals == null || literal.literals.size() < 2) {
			throw new FilterException("Filter of type " + literal.name + " needs at least 2 sub literals");
		}
	}

	private static void validateFieldOperator(Filter literal) throws FilterException {
		if (literal.value == null) {
			throw new FilterException("Value cannot be null with literal of type " + literal.name);
		}
		if (literal.literals != null && literal.literals.size() > 1) {
			throw new FilterException("Filter of type " + literal.name + " needs 0 or 1 sub literals");
		}
		if (literal.literals != null && literal.literals.size() == 1 && !isFinal(literal.literals.get(0))) {
			throw new FilterException("Filter of type " + literal.name
					+ " needs exactly 1 sub literals of type equals, not equals, greater than, greater than exclusive, lower than, lower than exclusive, regex, empty, in, not in, geoWithin or geoWithinSphere.");
		}
	}

	public static boolean isFinal(Filter literal) {
		return finalOperators.contains(literal.name);
	}

	public static Filter and(Filter... filters) {
		return new Filter(Filter.OPERATOR_AND, null, new ArrayList<Filter>(Arrays.asList(filters)));
	}

	public static Filter eq(String fieldName, Object value) {
		return operator(Filter.OPERATOR_EQUAL, fieldName, value);
	}

	private static Filter operator(String operator, String fieldName, Object value) {
		Filter valueFilter = new Filter(operator, value, null);
		List<Filter> fieldFilters = new ArrayList<Filter>();
		fieldFilters.add(valueFilter);

		return new Filter(Filter.OPERATOR_FIELD, fieldName, fieldFilters);
	}

	public static Filter ne(String fieldName, Object value) {
		return operator(Filter.OPERATOR_NOT_EQUAL, fieldName, value);
	}

	public static Filter gt(String fieldName, Object value) {
		return operator(Filter.OPERATOR_GREATER_THAN, fieldName, value);
	}

	public static Filter gte(String fieldName, Object value) {
		return operator(Filter.OPERATOR_GREATER_THAN_EXCLUSIVE, fieldName, value);
	}

	public static Filter lt(String fieldName, Object value) {
		return operator(Filter.OPERATOR_LOWER_THAN, fieldName, value);
	}

	public static Filter lte(String fieldName, Object value) {
		return operator(Filter.OPERATOR_LOWER_THAN_EXCLUSIVE, fieldName, value);
	}

	public static Filter empty(String fieldName) {
		return operator(Filter.OPERATOR_EMPTY, fieldName, null);
	}

	public static Filter regex(String fieldName, String regex) {
		// Validate the regex eagerly (throws PatternSyntaxException if malformed);
		// the compiled Pattern is intentionally not retained — the raw string is stored.
		Pattern.compile(regex);
		return operator(Filter.OPERATOR_REGEX, fieldName, regex);
	}

	public static Filter in(String fieldName, Object... values) {
		return operatorWithManyValues(Filter.OPERATOR_IN, fieldName, values);
	}

	private static Filter operatorWithManyValues(String operator, String fieldName, Object... values) {
		Filter literal = operator(operator, fieldName, null);
		List<Filter> valuesFilters = new ArrayList<Filter>();
		for (Object value : values) {
			valuesFilters.add(new Filter(null, value, null));
		}
		literal.literals.get(0).literals = valuesFilters;
		return literal;
	}

	public static Filter nin(String fieldName, Object... values) {
		return operatorWithManyValues(Filter.OPERATOR_NOT_IN, fieldName, values);
	}

	public static Filter text(String fieldName, String value) {
		return operator(Filter.OPERATOR_TEXT, fieldName, value);
	}

	public static Filter or(Filter... filters) {
		return new Filter(Filter.OPERATOR_OR, null, new ArrayList<Filter>(Arrays.asList(filters)));
	}

	public static Filter nor(Filter... filters) {
		return new Filter(Filter.OPERATOR_NOR, null, new ArrayList<Filter>(Arrays.asList(filters)));
	}

	public Filter andOperator(Filter... filters) {
		if (Filter.OPERATOR_AND.equals(this.name)) {
			List<Filter> filterList = new ArrayList<Filter>(Arrays.asList(filters));
			this.literals.addAll(filterList);
			return this;
		} else {
			List<Filter> filterList = new ArrayList<Filter>(Arrays.asList(filters));
			filterList.add(this);
			Filter[] arr = new Filter[filterList.size()];
			return Filter.and(filterList.toArray(arr));
		}
	}

	public Filter orOperator(Filter... filters) {
		if (Filter.OPERATOR_OR.equals(this.name)) {
			List<Filter> filterList = new ArrayList<Filter>(Arrays.asList(filters));
			this.literals.addAll(filterList);
			return this;
		} else {
			List<Filter> filterList = new ArrayList<Filter>(Arrays.asList(filters));
			filterList.add(this);
			Filter[] arr = new Filter[filterList.size()];
			return Filter.or(filterList.toArray(arr));
		}
	}

	public Filter norOperator(Filter... filters) {
		if (Filter.OPERATOR_NOR.equals(this.name)) {
			List<Filter> filterList = new ArrayList<Filter>(Arrays.asList(filters));
			this.literals.addAll(filterList);
			return this;
		} else {
			List<Filter> filterList = new ArrayList<Filter>(Arrays.asList(filters));
			filterList.add(this);
			Filter[] arr = new Filter[filterList.size()];
			return Filter.nor(filterList.toArray(arr));
		}
	}

	public static Filter geolocWithin(String fieldName, GeoJsonObject object) {
		return operator(Filter.OPERATOR_GEOLOC, fieldName, object);
	}

	public static Filter geolocWithinSphere(String fieldName, GeoJsonObject object) {
		return operator(Filter.OPERATOR_GEOLOC_SPHERE, fieldName, object);
	}

	@Override
	public Filter clone() {
		try {
			Filter cloned = (Filter) super.clone();

			if (this.literals != null) {
				List<Filter> clonedFilters = new ArrayList<>();
				for (Filter literal : this.literals) {
					clonedFilters.add((Filter) literal.clone());
				}
				cloned.literals = clonedFilters;
			}

			return cloned;
		} catch (CloneNotSupportedException e) {
			throw new ApiException("Clone not supported", e);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		Filter that = (Filter) o;
		return Objects.equals(name, that.name) &&
				Objects.equals(value, that.value) &&
				Objects.equals(literals, that.literals);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name, value, literals);
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("Filter{name='").append(name).append('\'')
				.append(", value=").append(value)
				.append(", literals=").append(literalsToString())
				.append('}');
		return sb.toString();
	}

	private String literalsToString() {
		if (literals == null) {
			return "null";
		}
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < literals.size(); i++) {
			sb.append(literals.get(i).toString());
			if (i < literals.size() - 1) {
				sb.append(", ");
			}
		}
		sb.append(']');
		return sb.toString();
	}

	@Override
	public List<IFilter> getFilters() {
		List<IFilter> returnedList = new ArrayList<IFilter>();
		if (this.literals != null)
			this.literals.forEach(lit -> {
				returnedList.add(lit);
			});
		return returnedList;
	}

	@Override
	@JsonIgnore
	public void setFilters(List<IFilter> literals) {
		List<Filter> returnedList = new ArrayList<Filter>();
		if (this.literals != null)
			literals.forEach(lit -> {
				returnedList.add((Filter) lit);
			});
		this.literals = returnedList;
	}
}