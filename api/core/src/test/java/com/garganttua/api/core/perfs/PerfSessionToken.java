package com.garganttua.api.core.perfs;

import java.time.Instant;
import java.util.List;

import com.garganttua.core.mapper.annotations.FieldMappingRule;

/**
 * A session token and its stored form, carried by the security performance fixture.
 *
 * <p>
 * Copied rather than borrowed from the security integration tests: a measurement suite that breaks
 * when an unrelated test is refactored is a suite that gets deleted rather than fixed.
 * </p>
 */
final class PerfSessionToken {

    private PerfSessionToken() {
    }

    /** Every field the stored record must round-trip. */
    public static class Entity {
        private String id;
        private String uuid;
        private String tenantId;
        private String ownerId;
        private String tokenType;
        private List<String> authorities;
        private Instant createdAt;
        private Instant expiresAt;
        private Boolean revoked;
        private String signedBy;
        private Boolean superTenant = false;

        public Entity() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public String getOwnerId() { return ownerId; }
        public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
        public String getTokenType() { return tokenType; }
        public void setTokenType(String tokenType) { this.tokenType = tokenType; }
        public List<String> getAuthorities() { return authorities; }
        public void setAuthorities(List<String> authorities) { this.authorities = authorities; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
        public Boolean getRevoked() { return revoked; }
        public void setRevoked(Boolean revoked) { this.revoked = revoked; }
        public String getSignedBy() { return signedBy; }
        public void setSignedBy(String signedBy) { this.signedBy = signedBy; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }
    }

    /** Mirrors every entity field by name so the stored record round-trips fully. */
    public static class Dto {
        @FieldMappingRule(sourceFieldAddress = "id") private String id;
        @FieldMappingRule(sourceFieldAddress = "uuid") private String uuid;
        @FieldMappingRule(sourceFieldAddress = "tenantId") private String tenantId;
        @FieldMappingRule(sourceFieldAddress = "ownerId") private String ownerId;
        @FieldMappingRule(sourceFieldAddress = "tokenType") private String tokenType;
        @FieldMappingRule(sourceFieldAddress = "authorities") private List<String> authorities;
        @FieldMappingRule(sourceFieldAddress = "createdAt") private Instant createdAt;
        @FieldMappingRule(sourceFieldAddress = "expiresAt") private Instant expiresAt;
        @FieldMappingRule(sourceFieldAddress = "revoked") private Boolean revoked;
        @FieldMappingRule(sourceFieldAddress = "signedBy") private String signedBy;
        @FieldMappingRule(sourceFieldAddress = "superTenant") private Boolean superTenant;

        public Dto() {}

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getUuid() { return uuid; }
        public void setUuid(String uuid) { this.uuid = uuid; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }
        public String getOwnerId() { return ownerId; }
        public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
        public String getTokenType() { return tokenType; }
        public void setTokenType(String tokenType) { this.tokenType = tokenType; }
        public List<String> getAuthorities() { return authorities; }
        public void setAuthorities(List<String> authorities) { this.authorities = authorities; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
        public Boolean getRevoked() { return revoked; }
        public void setRevoked(Boolean revoked) { this.revoked = revoked; }
        public String getSignedBy() { return signedBy; }
        public void setSignedBy(String signedBy) { this.signedBy = signedBy; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }
    }
}
