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
    public static final String APPLICATIONS_SUBMIT = "applications:submit";
    public static final String APPLICATIONS_UNDERWRITE = "applications:underwrite";
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

    // Partner integrations (external vendor verification / bureau)
    /**
     * Pull a credit-bureau report (CIBIL/Experian/Equifax/CRIF). A regulated, paid, sensitive-financial-data
     * operation invoked by los during underwriting — split from {@link #PARTNER_VERIFY} because it is a
     * distinct caller (svc-los) and a distinct data class from identity verification. Machine holder svc-los
     * is deferred until the token-less los→partner S2S call adopts client-credentials (threat T4,
     * {@code dev/AUTH_DESIGN.md}); the gate stays dormant until then.
     */
    public static final String PARTNER_CREDIT_PULL = "partner:credit-pull";
    /**
     * Verify an identity attribute via an external vendor — Aadhaar, PAN, or bank-account. One scope for all
     * three: same caller (svc-customer, during KYC/onboarding) and the same privilege class, so splitting
     * them further would be granularity without a boundary. Machine holder svc-customer is likewise deferred
     * to the T4 S2S-token work; customer→partner is token-less today, so this gate is dormant.
     */
    public static final String PARTNER_VERIFY = "partner:verify";

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
