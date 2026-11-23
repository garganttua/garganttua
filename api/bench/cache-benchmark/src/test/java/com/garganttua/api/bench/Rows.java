package com.garganttua.api.bench;

import java.io.Serializable;

/**
 * The benchmark's entities and DTOs. The entities are Serializable, not only the DTOs: Java serialization skips the
 * fields of a non-serializable superclass, and RedisCache would hand back empty DTOs.
 */
final class Rows {

    private Rows() {
    }

    /** An entity read by uuid and searched by tenant and kind. */
    public static class Item implements Serializable {
        private static final long serialVersionUID = 1L;
        private String id;
        private String uuid;
        private String tenantId;
        private String kind;
        private String name;
        private String payload;
        private Boolean superTenant = false;
        public String getId() { return id; }
        public void setId(String v) { this.id = v; }
        public String getUuid() { return uuid; }
        public void setUuid(String v) { this.uuid = v; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String v) { this.tenantId = v; }
        public String getKind() { return kind; }
        public void setKind(String v) { this.kind = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getPayload() { return payload; }
        public void setPayload(String v) { this.payload = v; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean v) { this.superTenant = v; }
    }

    public static class ItemDto extends Item {
        private static final long serialVersionUID = 1L;
    }

    /** A point on the map. */
    public static class Place implements Serializable {
        private static final long serialVersionUID = 1L;
        private String id;
        private String uuid;
        private String tenantId;
        private String kind;
        private Double latitude;
        private Double longitude;
        private Boolean superTenant = false;
        public String getId() { return id; }
        public void setId(String v) { this.id = v; }
        public String getUuid() { return uuid; }
        public void setUuid(String v) { this.uuid = v; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String v) { this.tenantId = v; }
        public String getKind() { return kind; }
        public void setKind(String v) { this.kind = v; }
        public Double getLatitude() { return latitude; }
        public void setLatitude(Double v) { this.latitude = v; }
        public Double getLongitude() { return longitude; }
        public void setLongitude(Double v) { this.longitude = v; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean v) { this.superTenant = v; }
    }

    public static class PlaceDto extends Place {
        private static final long serialVersionUID = 1L;
    }

    /** A row of a list served over HTTP. */
    public static class Product extends Item {
        private static final long serialVersionUID = 1L;
    }

    public static class ProductDto extends ItemDto {
        private static final long serialVersionUID = 1L;
    }
}
