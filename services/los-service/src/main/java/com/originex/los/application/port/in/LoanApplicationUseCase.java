package com.originex.los.application.port.in;

import com.originex.los.domain.model.LoanApplication;
import com.originex.starter.security.OriginexScopes;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.UUID;

/**
 * Inbound port — Loan Application use cases.
 *
 * <p><b>Authorization boundary</b> (inert until {@code originex.security.enabled=true}). los is the
 * richest surface so far, and its scopes are split on <b>privilege</b>, not endpoints:
 * <ul>
 *   <li>{@code applications:read} — reads.</li>
 *   <li>{@code applications:submit} — applicant self-service: submit, add documents, accept own
 *       offer, withdraw (all pre-decision, borrower-initiated).</li>
 *   <li>{@code applications:underwrite} — underwriting <b>analysis</b>: initiating a credit-bureau
 *       pull and recording its result. Running the checks.</li>
 *   <li>{@code applications:decide} — the credit <b>decision</b>: manual approve (+offer) or reject.
 *       Elevated above analysis because it commits the lender.</li>
 * </ul>
 *
 * <p>The submit/underwrite/decide split <i>enables</i> segregation of duties; it does not yet
 * <i>enforce</i> it — ensuring no one persona is granted both {@code submit} and {@code decide} is
 * deferred to role-gating (KI-14), and a per-application "approver ≠ submitter" control is a separate
 * workflow concern (KI-16). Note also that the <b>automatic</b> BRE decision reached inside
 * {@link #initiateCreditCheck} is a domain-level state transition (not a call to
 * {@link #approveAndGenerateOffer}); it therefore runs under {@code applications:underwrite} by design.
 * {@code applications:decide} guards the <b>manual</b> approve/reject path only.
 */
public interface LoanApplicationUseCase {

    String REQUIRES_APPLICATIONS_READ =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.APPLICATIONS_READ + "')";
    String REQUIRES_APPLICATIONS_SUBMIT =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.APPLICATIONS_SUBMIT + "')";
    String REQUIRES_APPLICATIONS_UNDERWRITE =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.APPLICATIONS_UNDERWRITE + "')";
    String REQUIRES_APPLICATIONS_DECIDE =
            "hasAuthority('" + OriginexScopes.AUTHORITY_PREFIX + OriginexScopes.APPLICATIONS_DECIDE + "')";

    @PreAuthorize(REQUIRES_APPLICATIONS_SUBMIT)
    LoanApplication submitApplication(SubmitApplicationCommand command);

    @PreAuthorize(REQUIRES_APPLICATIONS_READ)
    LoanApplication getApplication(UUID tenantId, UUID applicationId);

    @PreAuthorize(REQUIRES_APPLICATIONS_SUBMIT)
    LoanApplication addDocument(AddDocumentCommand command);

    @PreAuthorize(REQUIRES_APPLICATIONS_SUBMIT)
    LoanApplication acceptOffer(UUID tenantId, UUID applicationId);

    @PreAuthorize(REQUIRES_APPLICATIONS_SUBMIT)
    LoanApplication withdrawApplication(UUID tenantId, UUID applicationId);

    /**
     * Record a credit-bureau result. <b>Currently unused</b> — no caller invokes it, and the "async
     * flow" its prior doc referenced does not exist: {@link #initiateCreditCheck} pulls the bureau
     * synchronously and records the result inline via the domain aggregate. Guarded as underwriting
     * work to satisfy deny-by-default (every port method must be authorized). If it is ever wired to a
     * real asynchronous bureau callback, it will need (a) a machine/callback scope rather than the
     * human {@code underwrite} scope, and (b) explicit {@code SecurityContext} propagation across the
     * async boundary — neither of which exists today. See KI-17.
     */
    @PreAuthorize(REQUIRES_APPLICATIONS_UNDERWRITE)
    LoanApplication recordCreditResult(RecordCreditResultCommand command);

    /**
     * Triggers a live credit bureau pull via the Partner Integration Service,
     * then automatically records the result on the application.
     */
    @PreAuthorize(REQUIRES_APPLICATIONS_UNDERWRITE)
    LoanApplication initiateCreditCheck(UUID tenantId, UUID applicationId, String consentArtifactId);

    /**
     * Approve an application and generate its offer using the supplied (manually decided) offer terms.
     * The auto-decision flow does <i>not</i> route through here — it transitions the aggregate directly
     * (see the class note) — so this guards the <b>manual</b> officer approval.
     */
    @PreAuthorize(REQUIRES_APPLICATIONS_DECIDE)
    LoanApplication approveAndGenerateOffer(ApproveCommand command);

    /**
     * Reject an application with a reason. Orchestration only — the state
     * transition and decision-notes rule live in {@code LoanApplication.reject}.
     */
    @PreAuthorize(REQUIRES_APPLICATIONS_DECIDE)
    LoanApplication rejectApplication(RejectCommand command);

    // ─── Commands ───

    record SubmitApplicationCommand(
            UUID tenantId,
            UUID customerId,
            String productCode,
            String amount,
            String currency,
            int tenureMonths,
            String purpose,
            String channel,
            String applicantName,
            String applicantPan,
            String employmentType,
            String monthlyIncome
    ) {}

    record AddDocumentCommand(
            UUID tenantId,
            UUID applicationId,
            String documentType,
            String fileName,
            String storageUrl
    ) {}

    record RecordCreditResultCommand(
            UUID tenantId,
            UUID applicationId,
            int creditScore,
            String bureau,
            String reportRef
    ) {}

    record ApproveCommand(
            UUID tenantId,
            UUID applicationId,
            String sanctionedAmount,
            String interestRate,
            int tenureMonths,
            String emi,
            String processingFee,
            String apr,
            String notes
    ) {}

    record RejectCommand(
            UUID tenantId,
            UUID applicationId,
            String reason
    ) {}
}
