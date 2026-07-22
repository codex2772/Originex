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
    /**
     * Create a loan record from an approved application (status {@code CREATED}, pre-disbursement).
     * Distinct from {@link #LOANS_DISBURSE}: creation and funding are separate lifecycle steps — a loan
     * exists in {@code CREATED} before any money moves — so the machine that creates loans cannot disburse.
     */
    public static final String LOANS_CREATE = "loans:create";
    public static final String LOANS_DISBURSE = "loans:disburse";
    /** Loan servicing — applying a <b>settlement-backed</b> repayment (driven by a verified payment event). */
    public static final String LOANS_SERVICE = "loans:service";
    /**
     * <b>Manually assert a repayment WITHOUT settlement verification.</b> This capability can mark a loan
     * (partially) paid — reducing the borrower's outstanding and emitting a downstream {@code RepaymentAllocated}
     * event — with <b>no proof any money was received</b> ({@code paymentReference} is unreconciled). It is a
     * fraud-sensitive privilege of the same class as loan approval: grant it with decision-level scrutiny in
     * role-gating, never to a machine/service account, and never bundle it with the backed {@link #LOANS_SERVICE}
     * path. The unbacked assertion is un-attributed today (no recorded actor) — see KI-19.
     */
    public static final String LOANS_REPAY_MANUAL = "loans:repay-manual";

    // Payments
    public static final String PAYMENTS_READ = "payments:read";
    /** Collection / inbound / mandate-setup actions (money-in and setup side). */
    public static final String PAYMENTS_INITIATE = "payments:initiate";
    /**
     * Disbursing funds (money out) — a distinct privilege boundary from the money-in actions above.
     * Dual-called: the human/service {@code POST /v1/payments/disbursements} endpoint enforces it, and
     * the LoanDisbursed consumer is granted exactly this (and only this) as its minimal machine actor.
     */
    public static final String PAYMENTS_DISBURSE = "payments:disburse";
    /**
     * The payment-gateway callback (webhook). MACHINE-ONLY: held only by the gateway's service-account
     * (client_credentials) token; no human persona holds it. Its presence on a human/interactive token
     * is therefore definitionally anomalous and alertable (see the role-gating gap, KI-14).
     */
    public static final String PAYMENTS_CALLBACK = "payments:callback";

    // Ledger
    public static final String LEDGER_READ = "ledger:read";
    public static final String LEDGER_POST = "ledger:post";
    /**
     * Reversing an already-committed journal entry is a corrective/exceptional action — a higher
     * privilege than routine posting — so it carries its own scope rather than folding into
     * {@link #LEDGER_POST}. This is the platform precedent: corrective/exceptional operations get a
     * distinct scope (e.g. a future {@code payments:refund}, {@code applications:override}). The
     * machine/consumer path only ever posts, never reverses, so it is never granted this authority.
     */
    public static final String LEDGER_REVERSE = "ledger:reverse";

    // Decisioning (BRE)
    /**
     * Run a business-rules evaluation of a loan application (BRE {@code POST /v1/bre/evaluate}). This is
     * a <b>read</b> of the decisioning engine — rules in, a decision out, no state change. It is distinct
     * from <i>authoring</i> the rules: mutating the lending rule sets is a far higher privilege (the
     * {@code applications:underwrite}/loan-approval tier), and when a rule-management surface is built it
     * MUST carry its own elevated scope(s) — possibly split for corrective ops, per the
     * {@link #LEDGER_REVERSE}-vs-{@link #LEDGER_POST} precedent. No such surface exists in the app today
     * (rule authoring is out-of-band via the owner/DBA), so there is nothing to gate here yet — see the
     * note on {@code EvaluationUseCase}. bre's only caller is los, service-to-service; that call is
     * currently token-less (threat T4, {@code dev/AUTH_DESIGN.md}), so this gate must stay dormant
     * (service {@code security.enabled=false}) until los adopts a client-credentials token bearing it.
     */
    public static final String DECISIONING_EVALUATE = "decisioning:evaluate";

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
