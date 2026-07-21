package com.originex.common.security;

import java.util.Objects;

/**
 * Immutable authenticated-principal context — the "who" behind a request, derived
 * from verified JWT claims (companion to {@link com.originex.common.tenant.TenantContext},
 * the "which tenant"). Populated by the resource-server filter after the token is
 * validated; see {@code dev/AUTH_DESIGN.md} §4.1.
 *
 * <p>Represents the complete v1 principal model — the three principal kinds the
 * platform authenticates:
 * <ul>
 *   <li>{@link PrincipalType#HUMAN_USER} — tenant (or platform) staff;</li>
 *   <li>{@link PrincipalType#CUSTOMER} — a borrower, scoped to its own
 *       {@code customerId} (used later by the ownership authorization layer);</li>
 *   <li>{@link PrincipalType#SERVICE_ACCOUNT} — a machine/client-credentials
 *       identity, whose {@code subject} is the client id.</li>
 * </ul>
 *
 * @param type       the principal kind
 * @param subject    the principal identity: the token {@code sub} for humans, or the
 *                   client id for a service account
 * @param customerId the {@code customer_id} claim for CUSTOMER principals; {@code null}
 *                   for staff and service principals
 */
public record SubjectContext(PrincipalType type, String subject, String customerId) {

    public enum PrincipalType {
        /** A human staff user of a tenant (or platform). */
        HUMAN_USER,
        /** A borrower / end-customer, scoped to its own customer record. */
        CUSTOMER,
        /** A machine identity (OAuth2 client-credentials); {@code subject} is the client id. */
        SERVICE_ACCOUNT
    }

    public SubjectContext {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        if (type == PrincipalType.CUSTOMER && (customerId == null || customerId.isBlank())) {
            throw new IllegalArgumentException("customerId is required for a CUSTOMER principal");
        }
    }

    /** A tenant/platform staff user (no subject-scoping). */
    public static SubjectContext user(String subject) {
        return new SubjectContext(PrincipalType.HUMAN_USER, subject, null);
    }

    /** A borrower scoped to its own {@code customerId} (ownership layer). */
    public static SubjectContext customer(String subject, String customerId) {
        return new SubjectContext(PrincipalType.CUSTOMER, subject, customerId);
    }

    /** A machine/service-account identity keyed by client id. */
    public static SubjectContext serviceAccount(String clientId) {
        return new SubjectContext(PrincipalType.SERVICE_ACCOUNT, clientId, null);
    }

    /** True when this principal is scoped to a single customer (borrower). */
    public boolean isCustomerScoped() {
        return type == PrincipalType.CUSTOMER;
    }

    /** True when this principal is a machine/service account rather than a human. */
    public boolean isMachine() {
        return type == PrincipalType.SERVICE_ACCOUNT;
    }

    /** True when this principal is a human (staff or customer). */
    public boolean isHuman() {
        return type == PrincipalType.HUMAN_USER || type == PrincipalType.CUSTOMER;
    }
}
