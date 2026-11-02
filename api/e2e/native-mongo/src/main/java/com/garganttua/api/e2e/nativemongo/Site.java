package com.garganttua.api.e2e.nativemongo;

import java.util.List;

import org.geojson.Point;

import com.garganttua.api.commons.entity.annotations.Entity;
import com.garganttua.api.commons.entity.annotations.EntityId;
import com.garganttua.api.commons.entity.annotations.EntityIndexed;
import com.garganttua.api.commons.entity.annotations.EntityPublic;
import com.garganttua.api.commons.entity.annotations.EntityUuid;
import com.garganttua.api.commons.entity.annotations.IndexKind;
import com.garganttua.api.commons.entity.annotations.UnicityScope;
import com.garganttua.core.reflection.annotations.Reflected;

/**
 * The entity of the PostgreSQL proof, unchanged — the same shapes, the same checks: a unique code (a unique index, a
 * 409 on a duplicate), a flattened POJO ({@code address__city}), a list (a child table), an untyped
 * value (JSONB) and a GeoJSON point (a PostGIS geometry, with a geo index).
 */
@Entity
@EntityPublic
@Reflected(allDeclaredFields = true, queryAllDeclaredMethods = true, queryAllDeclaredConstructors = true)
public class Site {

	@EntityId
	private String id;

	@EntityUuid
	private String uuid;

	@EntityIndexed(unique = true, scope = UnicityScope.system)
	private String code;

	private String name;

	private Address address;

	private List<String> tags;

	private Object extra;

	@EntityIndexed(kind = IndexKind.geo, scope = UnicityScope.system)
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
