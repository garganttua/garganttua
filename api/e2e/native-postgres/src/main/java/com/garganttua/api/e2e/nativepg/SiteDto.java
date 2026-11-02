package com.garganttua.api.e2e.nativepg;

import java.util.List;

import org.geojson.Point;

import com.garganttua.api.commons.dto.annotations.Dto;
import com.garganttua.api.commons.dto.annotations.DtoId;
import com.garganttua.api.commons.dto.annotations.DtoUuid;
import com.garganttua.core.reflection.annotations.Reflected;

/** The stored form of a {@link Site} — one PostgreSQL row, plus its {@code tags} child rows. */
@Dto(entityClass = Site.class)
@Reflected(allDeclaredFields = true, queryAllDeclaredMethods = true, queryAllDeclaredConstructors = true)
public class SiteDto {

	@DtoId
	private String id;

	@DtoUuid
	private String uuid;

	private String code;

	private String name;

	private Address address;

	private List<String> tags;

	private Object extra;

	private Point location;

	public String getId() {
		return this.id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getUuid() {
		return this.uuid;
	}

	public void setUuid(String uuid) {
		this.uuid = uuid;
	}

	public String getCode() {
		return this.code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Address getAddress() {
		return this.address;
	}

	public void setAddress(Address address) {
		this.address = address;
	}

	public List<String> getTags() {
		return this.tags;
	}

	public void setTags(List<String> tags) {
		this.tags = tags;
	}

	public Object getExtra() {
		return this.extra;
	}

	public void setExtra(Object extra) {
		this.extra = extra;
	}

	public Point getLocation() {
		return this.location;
	}

	public void setLocation(Point location) {
		this.location = location;
	}
}
