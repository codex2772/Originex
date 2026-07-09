package com.originex.common.tenant;

import java.util.Objects;

/**
 * Immutable tenant context — carries tenant identity and metadata through
 * the entire request processing chain.
 *
 * @param tenantId   Unique tenant identifier (UUID)
 * @param tenantSlug Human-readable slug (e.g., "acme-bank") for URL routing
 * @param tier       Tenant tier for resource isolation decisions
 */
public record TenantContext(
        String tenantId,
        String tenantSlug,
        TenantTier tier
) {
    public TenantContext {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(tenantSlug, "tenantSlug must not be null");
        if (tier == null) {
            tier = TenantTier.STANDARD;
        }
    }

    /**
     * Convenience factory for simple cases (tests, internal usage).
     */
    public static TenantContext of(String tenantId, String tenantSlug) {
        return new TenantContext(tenantId, tenantSlug, TenantTier.STANDARD);
    }

    public enum TenantTier {
        /** Shared database with Row-Level Security */
        STANDARD,
        /** Shared database, dedicated schema */
        PREMIUM,
        /** Dedicated database instance */
        ENTERPRISE
    }
}
