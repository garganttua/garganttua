package com.garganttua.api.e2e.nativepg;

import com.garganttua.core.reflection.annotations.Reflected;

/** An embedded POJO: flattened into {@code address__city} and {@code address__zip} columns. */
@Reflected(allDeclaredFields = true, queryAllDeclaredMethods = true, queryAllDeclaredConstructors = true)
public class Address {

	private String city;

	private String zip;

	public String getCity() {
		return this.city;
	}

	public void setCity(String city) {
		this.city = city;
	}

	public String getZip() {
		return this.zip;
	}

	public void setZip(String zip) {
		this.zip = zip;
	}
}
