package com.garganttua.api.commons.sort;

public class Sort implements ISort {

	private String fieldName;
	private SortDirection direction;

	public Sort() {
	}

	public Sort(String fieldName, SortDirection direction) {
		this.fieldName = fieldName;
		this.direction = direction;
	}

	@Override
	public String getFieldName() {
		return this.fieldName;
	}

	@Override
	public SortDirection getDirection() {
		return this.direction;
	}
}
