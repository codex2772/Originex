package com.originex.starter.security;

/**
 * Authoritative catalog of OAuth2 <b>scopes</b> (fine-grained capabilities) — see
 * {@code dev/AUTH_DESIGN.md} §4.4. Scopes are mapped to {@code SCOPE_<scope>}
 * Spring authorities by the resource server; reference these constants from
 * {@code @PreAuthorize}, e.g.
 * {@code @PreAuthorize("hasAuthority('SCOPE_" + OriginexScopes.LOANS_DISBURSE + "')")}.
 *
 * <p>This is the single source of truth for the scope vocabulary; per-service
 * {@code @PreAuthorize} application and the ArchUnit deny-by-default check are a
 * later, per-service commit.
 */
public final class OriginexScopes {

    /** Prefix Spring uses for scope authorities. */
    public static final String AUTHORITY_PREFIX = "SCOPE_";

    // Customers / KYC
    public static final String CUSTOMERS_READ = "customers:read";
    public static final String CUSTOMERS_WRITE = "customers:write";
    public static final String KYC_SUBMIT = "kyc:submit";
    public static final String KYC_VERIFY = "kyc:verify";

    // Origination (LOS)
    public static final String APPLICATIONS_READ = "applications:read";
    /** Applicant self-service: submit, supplement (documents), accept own offer, withdraw. */
    public static final String APPLICATIONS_SUBMIT = "applications:submit";
    /** Underwriting <b>analysis</b> — e.g. initiating a credit-bureau pull (a process step). */
    public static final String APPLICATIONS_UNDERWRITE = "applications:underwrite";
    /**
     * The credit <b>decision</b> — approve (+ generate offer) or reject. A distinct, higher privilege
     * than {@link #APPLICATIONS_UNDERWRITE} (running the analysis): the decision commits the lender.
     * Separating it enables segregation of duties — running checks does not confer authority to approve.
     * (Enforcing that no one persona holds both submit and decide is deferred to role-gating; see KI-14.
     * A per-application "approver ≠ submitter" control is a separate workflow concern; see KI-16.)
     */
    public static final String APPLICATIONS_DECIDE = "applications:decide";
    public static final String OFFERS_MANAGE = "offers:manage";

    // Loans (LMS)
    public static final String LOANS_READ = "loans:read";
    public static final String LOANS_DISBURSE = "loans:disburse";
    public static final String LOANS_SERVICE = "loans:service";

    // Payments
    public static final String PAYMENTS_READ = "payments:read";
    public static final String PAYMENTS_INITIATE = "payments:initiate";

    // Ledger
    public static final String LEDGER_READ = "ledger:read";
    public static final String LEDGER_POST = "ledger:post";

    // Collections
    public static final String COLLECTIONS_READ = "collections:read";
    public static final String COLLECTIONS_ACT = "collections:act";

    // Notifications
    public static final String NOTIFICATIONS_READ = "notifications:read";
    public static final String NOTIFICATIONS_SEND = "notifications:send";

    // Administration / cross-cutting
    public static final String TENANT_ADMIN = "tenant:admin";
    public static final String PLATFORM_ADMIN = "platform:admin";
    public static final String AUDIT_READ = "audit:read";

    private OriginexScopes() {
    }

    /** The Spring authority for a scope (e.g. {@code loans:disburse} → {@code SCOPE_loans:disburse}). */
    public static String authority(String scope) {
        return AUTHORITY_PREFIX + scope;
    }
}
