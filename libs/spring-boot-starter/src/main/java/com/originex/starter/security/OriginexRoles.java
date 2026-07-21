package com.originex.starter.security;

/**
 * Authoritative catalog of realm <b>roles</b> (coarse, tenant-scoped) — see
 * {@code dev/AUTH_DESIGN.md} §4. Realm roles are mapped to {@code ROLE_<NAME>}
 * Spring authorities by {@link KeycloakRealmRoleConverter}; reference these
 * constants from {@code @PreAuthorize}, e.g.
 * {@code @PreAuthorize("hasRole('" + OriginexRoles.UNDERWRITER + "')")}.
 */
public final class OriginexRoles {

    /** Prefix Spring uses for role authorities (what {@code hasRole(x)} checks: {@code ROLE_x}). */
    public static final String AUTHORITY_PREFIX = "ROLE_";

    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String TENANT_ADMIN = "TENANT_ADMIN";
    public static final String OPERATIONS = "OPERATIONS";
    public static final String UNDERWRITER = "UNDERWRITER";
    public static final String COLLECTIONS = "COLLECTIONS";
    public static final String FINANCE = "FINANCE";
    public static final String CUSTOMER_SUPPORT = "CUSTOMER_SUPPORT";
    public static final String AUDITOR = "AUDITOR";
    public static final String CUSTOMER = "CUSTOMER";

    private OriginexRoles() {
    }

    /** The Spring authority for a role name (e.g. {@code UNDERWRITER} → {@code ROLE_UNDERWRITER}). */
    public static String authority(String role) {
        return AUTHORITY_PREFIX + role;
    }
}
