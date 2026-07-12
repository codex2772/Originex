package com.originex.common.security;

import java.util.Objects;

/**
 * Immutable authenticated-principal context — the "who" behind a request, derived
 * from verified JWT claims (companion to {@link com.originex.common.tenant.TenantContext},
 * the "which tenant"). Populated by the resource-server filter after the token is
 * validated; see {@code dev/AUTH_DESIGN.md} §4.1/§5.
 *
 * @param subject    the token {@code sub} — the authenticated principal id (user or client)
 * @param customerId the {@code customer_id} claim for borrower (CUSTOMER) principals,
 *                   used by the ownership authorization layer; {@code null} for staff
 *                   and service principals
 */
public record SubjectContext(String subject, String customerId) {

    public SubjectContext {
        Objects.requireNonNull(subject, "subject must not be null");
    }

    /** A principal with no subject-scoping (staff / service account). */
    public static SubjectContext of(String subject) {
        return new SubjectContext(subject, null);
    }

    public static SubjectContext of(String subject, String customerId) {
        return new SubjectContext(subject, customerId);
    }

    /** True when this principal is scoped to a single customer (borrower). */
    public boolean isCustomerScoped() {
        return customerId != null && !customerId.isBlank();
    }
}
